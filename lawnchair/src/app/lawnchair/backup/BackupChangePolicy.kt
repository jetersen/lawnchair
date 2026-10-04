package app.lawnchair.backup

/** A failed or missing export never becomes an unchanged-content baseline. */
internal object BackupChangePolicy {
    fun canSkip(
        manual: Boolean,
        fingerprint: String,
        previousFingerprint: String?,
        tree: String,
        previousTree: String?,
        lastError: String?,
        previousExportExists: () -> Boolean,
    ): Boolean = !manual && lastError == null && fingerprint == previousFingerprint &&
        tree == previousTree && previousExportExists()
}
