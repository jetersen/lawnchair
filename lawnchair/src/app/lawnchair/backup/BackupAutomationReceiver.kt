package app.lawnchair.backup

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Explicit, opt-in trigger for Tasker and adb. No caller can choose the export destination. */
class BackupAutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BACKUP) {
            respond(Activity.RESULT_CANCELED, "Unknown action")
            return
        }
        val settings = AutomaticBackupSettings(context)
        val now = System.currentTimeMillis()
        val previous = settings.preferences.getLong("last_automation_request", 0)
        val rejection = when {
            !settings.automationEnabled -> "Automation intents are disabled"
            settings.treeUri == null -> "Choose a backup folder first"
            now >= previous && now - previous < COOLDOWN_MILLIS -> "Rate limited; wait 60 seconds"
            else -> null
        }
        if (rejection != null) {
            respond(Activity.RESULT_CANCELED, rejection)
            return
        }
        try {
            AutomaticBackupSettings.backUpNow(context, force = intent.getBooleanExtra(EXTRA_FORCE, false), external = true)
            settings.preferences.edit().putLong("last_automation_request", now).apply()
            respond(Activity.RESULT_OK, "Backup check queued")
        } catch (e: Exception) {
            Log.e("BackupAutomation", "Unable to enqueue backup", e)
            respond(Activity.RESULT_CANCELED, "Unable to enqueue backup")
        }
    }

    private fun respond(code: Int, message: String) {
        if (isOrderedBroadcast) setResult(code, message, null)
    }

    companion object {
        const val ACTION_BACKUP = "app.lawnchair.action.BACKUP"
        const val EXTRA_FORCE = "force"
        private const val COOLDOWN_MILLIS = 60_000L
    }
}
