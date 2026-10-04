package app.lawnchair.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifiedBackupWriterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun publishesOnlyAfterClosingAndReadingBackExport() {
        val source = temporaryFolder.newFile().apply { writeText("complete archive") }
        val events = mutableListOf<String>()
        val output = object : ByteArrayOutputStream() {
            override fun close() { events += "closed"; super.close() }
        }
        VerifiedBackupWriter.publish(source, { output }, {
            events += "read"
            ByteArrayInputStream(output.toByteArray())
        }) { events += "published" }
        assertEquals(listOf("closed", "read", "published"), events)
    }

    @Test
    fun truncatedDestinationNeverPublishes() {
        val source = temporaryFolder.newFile().apply { writeText("complete archive") }
        var published = false
        assertThrows(IOException::class.java) {
            VerifiedBackupWriter.publish(source, { ByteArrayOutputStream() }, { ByteArrayInputStream(byteArrayOf()) }) {
                published = true
            }
        }
        assertFalse(published)
    }

    @Test
    fun failedCloseNeverPublishes() {
        val source = temporaryFolder.newFile().apply { writeText("complete archive") }
        var published = false
        val output = object : ByteArrayOutputStream() {
            override fun close() { throw IOException("disk full") }
        }
        assertThrows(IOException::class.java) {
            VerifiedBackupWriter.publish(source, { output }, { ByteArrayInputStream(output.toByteArray()) }) {
                published = true
            }
        }
        assertFalse(published)
    }
}
