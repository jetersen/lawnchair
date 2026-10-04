package app.lawnchair.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AutomaticBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = exportMutex.withLock {
        withContext(Dispatchers.IO) { export() }
    }

    private suspend fun export(): Result {
        val settings = AutomaticBackupSettings(applicationContext)
        val tree = settings.treeUri ?: return Result.success()
        val external = inputData.getBoolean("external", false)
        if (external && !settings.automationEnabled) return Result.success()
        if (!settings.enabled && !external && !inputData.getBoolean("manual", false)) return Result.success()
        val revision = settings.revision
        val resolver = applicationContext.contentResolver
        var pending: Uri? = null
        var stagingFile: File? = null
        try {
            val staged = File.createTempFile("automatic-backup-", ".zip", applicationContext.cacheDir)
            stagingFile = staged
            if (resolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission && it.isWritePermission }) {
                throw SecurityException("Backup folder permission was revoked")
            }
            staged.outputStream().use {
                LawnchairBackup.create(applicationContext, LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS, null, it)
            }
            currentCoroutineContext().ensureActive()
            val fingerprint = BackupContentFingerprint.calculate(staged)
            if (BackupChangePolicy.canSkip(
                    manual = inputData.getBoolean("manual", false),
                    fingerprint = fingerprint,
                    previousFingerprint = settings.preferences.getString("last_content_fingerprint", null),
                    tree = tree.toString(),
                    previousTree = settings.preferences.getString("last_content_tree", null),
                    lastError = settings.lastError,
                    previousExportExists = { previousExportExists(settings) },
                )
            ) {
                return Result.success()
            }
            val owner = settings.owner()
            val name = AutomaticBackupPolicy.fileName(owner)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            pending = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", "$name.partial")
                ?: throw IOException("Unable to create backup")
            val flags = resolver.query(pending, arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            } ?: 0
            if (flags and Document.FLAG_SUPPORTS_RENAME == 0) throw IOException("Folder does not support publishing completed backups")
            val destination = pending
            val coroutineContext = currentCoroutineContext()
            val completed = VerifiedBackupWriter.publish(
                source = staged,
                openOutput = { resolver.openOutputStream(destination, "wt") ?: throw IOException("Unable to write backup") },
                openInput = { resolver.openInputStream(destination) ?: throw IOException("Unable to verify backup") },
                publish = {
                    coroutineContext.ensureActive()
                    if (settings.revision != revision) throw CancellationException("Backup settings changed")
                    DocumentsContract.renameDocument(resolver, destination, name) ?: throw IOException("Unable to publish backup")
                },
            )
            pending = null
            // Advance the baseline only after the export has been verified and published.
            settings.preferences.edit()
                .putLong("last_success", System.currentTimeMillis())
                .putString("last_content_fingerprint", fingerprint)
                .putString("last_content_tree", tree.toString())
                .putString("last_export_uri", completed.toString())
                .remove("last_error")
                .commit()

            // Retention is reached only after a complete, verified export. Never delete other files.
            try {
                val children = listExports(tree)
                val completedName = children.firstOrNull { it.first == completed }?.second
                    ?: name
                if (settings.revision == revision) {
                    val obsolete = AutomaticBackupPolicy.obsolete(children.map { it.second }, owner, settings.retention, completedName).toSet()
                    children.filter { it.second in obsolete }.forEach { (uri, _) ->
                        currentCoroutineContext().ensureActive()
                        if (settings.revision != revision) return Result.success()
                        if (!DocumentsContract.deleteDocument(resolver, uri)) throw IOException("Unable to remove old export")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Backup saved, but retention failed", e)
                settings.preferences.edit().putString("last_error", "retention").commit()
            }
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Log.w(TAG, "Backup folder permission unavailable", e)
            settings.preferences.edit().putString("last_error", "permission").commit()
            return Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "Automatic backup failed", e)
            settings.preferences.edit().putString("last_error", "export").commit()
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                pending?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
                stagingFile?.delete()
            }
        }
    }

    private fun previousExportExists(settings: AutomaticBackupSettings): Boolean {
        val previous = settings.preferences.getString("last_export_uri", null) ?: return false
        return runCatching {
            applicationContext.contentResolver.query(
                Uri.parse(previous),
                arrayOf(Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    private fun listExports(tree: Uri): List<Pair<Uri, String>> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cursor = applicationContext.contentResolver.query(
            children,
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE),
            null,
            null,
            null,
        ) ?: throw IOException("Unable to list backup folder")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    if (it.getString(2) == Document.MIME_TYPE_DIR) continue
                    add(DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0)) to it.getString(1))
                }
            }
        }
    }

    companion object {
        private const val TAG = "AutomaticBackup"
        private val exportMutex = Mutex()
    }
}
