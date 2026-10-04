package app.lawnchair.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupChangePolicyTest {
    private fun canSkip(
        manual: Boolean = false,
        fingerprint: String = "current",
        previousFingerprint: String? = "current",
        tree: String = "destination",
        previousTree: String? = "destination",
        lastError: String? = null,
        exists: Boolean = true,
    ) = BackupChangePolicy.canSkip(manual, fingerprint, previousFingerprint, tree, previousTree, lastError) { exists }

    @Test
    fun onlyUnchangedScheduledExportsWithAnExistingBaselineAreSkipped() {
        assertTrue(canSkip())
        assertFalse(canSkip(manual = true))
        assertFalse(canSkip(fingerprint = "changed"))
        assertFalse(canSkip(previousFingerprint = null))
    }

    @Test
    fun changedOrMissingDestinationIsExportedAgain() {
        assertFalse(canSkip(tree = "new destination"))
        assertFalse(canSkip(previousTree = null))
        assertFalse(canSkip(exists = false))
    }

    @Test
    fun failedExportOrRetentionIsRetriedEvenWhenContentIsUnchanged() {
        assertFalse(canSkip(lastError = "export"))
        assertFalse(canSkip(lastError = "permission"))
        assertFalse(canSkip(lastError = "retention"))
    }
}
