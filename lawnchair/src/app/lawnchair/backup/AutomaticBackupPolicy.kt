package app.lawnchair.backup

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Only exports made by this installation are eligible for retention cleanup. */
internal object AutomaticBackupPolicy {
    private val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS").withZone(ZoneOffset.UTC)

    fun fileName(owner: String, now: Instant = Instant.now(), id: UUID = UUID.randomUUID()): String = "Lawnchair_Auto_${owner}_${timestamp.format(now)}_$id.lawnchairbackup"

    fun obsolete(names: List<String>, owner: String, keep: Int, newest: String): List<String> {
        require(keep >= 1)
        val pattern = Regex("Lawnchair_Auto_${Regex.escape(owner)}_[0-9]{8}-[0-9]{9}_[0-9a-f-]{36}\\.lawnchairbackup")
        // Always retain the export just completed, even if the device clock moved backwards.
        return names.filter { it != newest && pattern.matches(it) }.sortedDescending().drop(keep - 1)
    }
}
