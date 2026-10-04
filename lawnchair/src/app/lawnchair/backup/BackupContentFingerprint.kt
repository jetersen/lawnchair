package app.lawnchair.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferencesFileSerializer
import app.lawnchair.LawnchairProto.BackupInfo
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** Compares restore contents, excluding export timestamps and storage bookkeeping. */
internal object BackupContentFingerprint {
    suspend fun calculate(archive: File): String {
        val digest = newDigest()
        // Version the comparison format independently of the restorable backup format.
        digest.field("content-v1")
        ZipFile(archive).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name != LawnchairBackup.SCREENSHOT_FILE_NAME }
                .sortedBy { it.name }.forEach { entry ->
                    digest.field(entry.name)
                    zip.getInputStream(entry).use { input ->
                        val content = when (entry.name) {
                            LawnchairBackup.INFO_FILE_NAME -> {
                                val info = BackupInfo.parseFrom(input)
                                BackupInfo.newBuilder()
                                    .setBackupVersion(info.backupVersion)
                                    .setInstallationId(info.installationId)
                                    .setContents(info.contents)
                                    .setGridState(info.gridState)
                                    .build().toByteArray()
                            }

                            LawnchairBackup.LAUNCHER_DB_FILE_NAME, "preferences" -> database(input, archive.parentFile)

                            "preferences.preferences_pb" -> preferences(input)

                            else -> if (entry.name.endsWith(".xml")) xml(input) else input.readBytes()
                        }
                        digest.field(content)
                    }
                }
        }
        return digest.digest().hex()
    }

    private fun database(input: InputStream, directory: File?): ByteArray {
        val file = File.createTempFile("backup-fingerprint-", ".db", directory)
        try {
            file.outputStream().use { input.copyTo(it) }
            return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val digest = newDigest()
                digest.field(db.version.toString())
                val tables = mutableListOf<String>()
                db.rawQuery("SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL AND name != 'android_metadata' AND name NOT LIKE 'sqlite_stat%' ORDER BY type, name", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        digest.field(cursor.getString(0))
                        digest.field(cursor.getString(1))
                        digest.field(cursor.getString(2))
                        if (cursor.getString(0) == "table") tables += cursor.getString(1)
                    }
                }
                tables.forEach { table ->
                    digest.field(table)
                    val quoted = "\"${table.replace("\"", "\"\"")}\""
                    db.rawQuery("SELECT * FROM $quoted", null).use { cursor ->
                        cursor.columnNames.forEach { digest.field(it) }
                        val rows = mutableListOf<String>()
                        while (cursor.moveToNext()) {
                            val row = newDigest()
                            repeat(cursor.columnCount) { column ->
                                val type = cursor.getType(column)
                                row.update(type.toByte())
                                when (type) {
                                    Cursor.FIELD_TYPE_NULL -> Unit
                                    Cursor.FIELD_TYPE_BLOB -> row.field(cursor.getBlob(column))
                                    Cursor.FIELD_TYPE_INTEGER -> row.field(cursor.getLong(column).toString())
                                    Cursor.FIELD_TYPE_FLOAT -> row.field(cursor.getDouble(column).toString())
                                    else -> row.field(cursor.getString(column))
                                }
                            }
                            rows += row.digest().hex()
                        }
                        // Physical row order and SQLite page allocation do not affect restoration.
                        digest.field(rows.size.toString())
                        rows.sorted().forEach { digest.field(it) }
                    }
                }
                digest.digest()
            }
        } finally {
            SQLiteDatabase.deleteDatabase(file)
        }
    }

    private suspend fun preferences(input: InputStream): ByteArray {
        val digest = newDigest()
        PreferencesFileSerializer.readFrom(input).asMap().entries.sortedBy { it.key.name }.forEach { (key, value) ->
            digest.field(key.name)
            when (value) {
                is Set<*> -> {
                    digest.field("set")
                    value.filterIsInstance<String>().sorted().forEach { digest.field(it) }
                }

                is ByteArray -> {
                    digest.field("bytes")
                    digest.field(value)
                }

                else -> {
                    digest.field(value.javaClass.name)
                    digest.field(value.toString())
                }
            }
        }
        return digest.digest()
    }

    private fun xml(input: InputStream): ByteArray {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(input, "UTF-8")
        parser.nextTag()
        return xmlElement(parser)
    }

    private fun xmlElement(parser: XmlPullParser): ByteArray {
        val digest = newDigest()
        val name = parser.name
        digest.field(name)
        (0 until parser.attributeCount).map { parser.getAttributeName(it) to parser.getAttributeValue(it) }
            .sortedBy { it.first }.forEach { (key, value) ->
                digest.field(key)
                digest.field(value)
            }
        val children = mutableListOf<String>()
        val text = StringBuilder()
        while (parser.next() != XmlPullParser.END_TAG) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> children += xmlElement(parser).hex()
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_DOCUMENT -> error("Incomplete preferences XML")
            }
        }
        if (name == "map" || name == "set") {
            children.sort()
        } else {
            digest.field(text.toString())
        }
        children.forEach { digest.field(it) }
        return digest.digest()
    }

    private fun newDigest() = MessageDigest.getInstance("SHA-256")
    private fun MessageDigest.field(value: String) = field(value.toByteArray(Charsets.UTF_8))
    private fun MessageDigest.field(value: ByteArray) {
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
        update(value)
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
