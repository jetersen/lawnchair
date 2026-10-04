package app.lawnchair.backup

import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import app.lawnchair.LawnchairProto.BackupInfo
import com.google.protobuf.Timestamp
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BackupContentFingerprintTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun archive(
        time: Long = 1,
        installationId: String = "this-installation",
        entries: List<Pair<String, ByteArray>> = emptyList(),
        reverse: Boolean = false,
    ): File {
        val info = BackupInfo.newBuilder()
            .setBackupVersion(1)
            .setInstallationId(installationId)
            .setLawnchairVersion(time.toInt())
            .setCreatedAt(Timestamp.newBuilder().setSeconds(time))
            .setPreviewDarkText(time % 2 == 0L)
            .build()
        val contents = entries + listOf(
            "info.pb" to info.toByteArray(),
            "screenshot.png" to time.toString().toByteArray(),
        )
        return temporaryFolder.newFile().also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                (if (reverse) contents.reversed() else contents).forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name).apply { this.time = time * 1000 })
                    zip.write(contents)
                }
            }
        }
    }

    private fun fingerprint(vararg entries: Pair<String, ByteArray>) = runBlocking { BackupContentFingerprint.calculate(archive(entries = entries.toList())) }

    @Test
    fun timestampsPreviewsAppVersionAndZipEntryOrderDoNotCreateANewVersion() = runBlocking {
        assertEquals(
            BackupContentFingerprint.calculate(archive()),
            BackupContentFingerprint.calculate(archive(time = 86400, reverse = true)),
        )
    }

    @Test
    fun installationIdentityChangesRestoreBehaviorAndCreatesANewVersion() = runBlocking {
        val current = BackupContentFingerprint.calculate(archive())
        assertNotEquals(current, BackupContentFingerprint.calculate(archive(installationId = "")))
        assertNotEquals(current, BackupContentFingerprint.calculate(archive(installationId = "another-installation")))
    }

    @Test
    fun sqliteSnapshotsIgnoreBookkeepingAndDetectLayoutChanges() {
        val source = temporaryFolder.newFile("source.db")
        SQLiteDatabase.openOrCreateDatabase(source, null).use { db ->
            db.enableWriteAheadLogging()
            db.execSQL("CREATE TABLE favorites (_id INTEGER PRIMARY KEY, cellY REAL, icon BLOB, title TEXT)")
            db.execSQL("INSERT INTO favorites VALUES (1, 1.5, X'0102', NULL)")
            fun current(): String {
                val snapshot = temporaryFolder.newFile()
                BackupDatabaseSnapshot.create(source, snapshot)
                return fingerprint("launcher.db" to snapshot.readBytes())
            }
            val original = current()
            db.execSQL("UPDATE favorites SET cellY = 2")
            db.execSQL("UPDATE favorites SET cellY = 1.5")
            db.execSQL("VACUUM")
            assertEquals(original, current())
            db.execSQL("UPDATE favorites SET cellY = 2")
            assertNotEquals(original, current())
        }
    }

    @Test
    fun databaseRowOrderDoesNotCreateANewVersionButSchemaAndTypesDo() {
        val dbFile = temporaryFolder.newFile("source.db")
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE settings (name TEXT, value)")
            db.execSQL("INSERT INTO settings VALUES ('one', 1), ('two', 2)")
            fun current(): String {
                val snapshot = temporaryFolder.newFile()
                BackupDatabaseSnapshot.create(dbFile, snapshot)
                return fingerprint("preferences" to snapshot.readBytes())
            }
            val original = current()
            db.execSQL("DELETE FROM settings")
            db.execSQL("INSERT INTO settings VALUES ('two', 2), ('one', 1)")
            assertEquals(original, current())
            db.execSQL("UPDATE settings SET value = '1' WHERE name = 'one'")
            assertNotEquals(original, current())
            db.execSQL("UPDATE settings SET value = 1 WHERE name = 'one'")
            db.execSQL("CREATE INDEX setting_names ON settings(name)")
            assertNotEquals(original, current())
        }
    }

    @Test
    fun preferenceOrderAndSetOrderDoNotCreateANewVersion() {
        fun preferences(reverse: Boolean): ByteArray {
            val strings = listOf("A", "B").let { if (reverse) it.reversed() else it }
            val values = mutablePreferencesOf()
            if (reverse) values[booleanPreferencesKey("lock")] = true
            values[stringSetPreferencesKey("hidden")] = strings.toSet()
            values[booleanPreferencesKey("lock")] = true
            return ByteArrayOutputStream().also { output ->
                runBlocking { PreferencesFileSerializer.writeTo(values, output) }
            }.toByteArray()
        }
        assertEquals(
            fingerprint("preferences.preferences_pb" to preferences(false)),
            fingerprint("preferences.preferences_pb" to preferences(true)),
        )
    }

    @Test
    fun settingsChangeCreatesANewVersion() {
        fun preferences(locked: Boolean) = ByteArrayOutputStream().also { output ->
            runBlocking { PreferencesFileSerializer.writeTo(mutablePreferencesOf(booleanPreferencesKey("lock") to locked), output) }
        }.toByteArray()
        assertNotEquals(
            fingerprint("preferences.preferences_pb" to preferences(false)),
            fingerprint("preferences.preferences_pb" to preferences(true)),
        )
    }

    @Test
    fun sharedPreferencesXmlIgnoresFormattingAndOrderButPreservesStringSpaces() {
        val first = "<map><boolean name='locked' value='true'/><set name='hidden'><string>A</string><string>B</string></set><string name='label'> Maps </string></map>"
        val reordered = "<map>\n<string name='label'> Maps </string><set name='hidden'><string>B</string><string>A</string></set><boolean value='true' name='locked'/>\n</map>"
        assertEquals(
            fingerprint("launcher.xml" to first.toByteArray()),
            fingerprint("launcher.xml" to reordered.toByteArray()),
        )
        assertNotEquals(
            fingerprint("launcher.xml" to first.toByteArray()),
            fingerprint("launcher.xml" to first.replace(" Maps ", "Maps").toByteArray()),
        )
    }
}
