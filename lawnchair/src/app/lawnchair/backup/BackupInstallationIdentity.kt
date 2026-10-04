package app.lawnchair.backup

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID

/** Stays on this installation; neither launcher exports nor Android backup copy this file. */
internal object BackupInstallationIdentity {
    @Synchronized
    fun get(context: Context): String {
        val file = AtomicFile(File(context.noBackupFilesDir, "launcher-backup-installation-id"))
        runCatching { UUID.fromString(String(file.readFully(), Charsets.UTF_8)).toString() }
            .getOrNull()?.let { return it }
        val identity = UUID.randomUUID().toString()
        val output = file.startWrite()
        try {
            output.write(identity.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
        return identity
    }
}
