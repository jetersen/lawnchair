package app.lawnchair.backup

import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticBackupPolicyTest {
    private val owner = UUID.randomUUID().toString()
    private fun name(day: Int) = AutomaticBackupPolicy.fileName(owner, Instant.parse("2026-10-${day.toString().padStart(2, '0')}T12:00:00Z"))

    @Test
    fun retentionKeepsNewestAndIgnoresManualForeignAndPartialFiles() {
        val old = name(1)
        val recent = name(2)
        val newest = name(3)
        val foreign = AutomaticBackupPolicy.fileName(UUID.randomUUID().toString())
        val files = listOf(old, recent, newest, foreign, "$old.partial", "Nova.novabackup", "manual.lawnchairbackup")
        assertEquals(listOf(old), AutomaticBackupPolicy.obsolete(files, owner, 2, newest))
    }

    @Test
    fun clockRollbackCannotDeleteJustCompletedExport() {
        val justCompleted = name(1)
        val future = name(20)
        assertEquals(listOf(future), AutomaticBackupPolicy.obsolete(listOf(future, justCompleted), owner, 1, justCompleted))
    }

    @Test
    fun retentionLeavesSmallerCollectionsAlone() {
        val newest = name(1)
        assertTrue(AutomaticBackupPolicy.obsolete(listOf(newest), owner, 14, newest).isEmpty())
    }

    @Test
    fun filenamesAreUniqueAtTheSameTimestamp() {
        val time = Instant.EPOCH
        assertTrue(AutomaticBackupPolicy.fileName(owner, time) != AutomaticBackupPolicy.fileName(owner, time))
    }
}
