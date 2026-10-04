package app.lawnchair.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Device-local settings deliberately excluded from launcher exports (URI grants are not portable). */
internal class AutomaticBackupSettings(context: Context) {
    val preferences: SharedPreferences = context.getSharedPreferences("automatic_backups", Context.MODE_PRIVATE)
    val enabled get() = preferences.getBoolean("enabled", false)
    val treeUri get() = preferences.getString("tree", null)?.let(Uri::parse)
    val retention get() = preferences.getInt("retention", 14).coerceIn(1, 100)
    val intervalDays get() = preferences.getInt("interval_days", 1).coerceIn(1, 7)
    val revision get() = preferences.getString("revision", "")!!
    val lastSuccess get() = preferences.getLong("last_success", 0)
    val lastError get() = preferences.getString("last_error", null)

    fun owner(): String = synchronized(AutomaticBackupSettings::class.java) {
        preferences.getString("owner", null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString("owner", it).commit())
        }
    }

    fun update(change: SharedPreferences.Editor.() -> Unit) {
        check(preferences.edit().apply(change).putString("revision", UUID.randomUUID().toString()).commit())
    }

    companion object {
        private const val PERIODIC_WORK = "automatic-launcher-backup"
        private const val MANUAL_WORK = "manual-launcher-backup"

        fun schedule(context: Context) {
            val settings = AutomaticBackupSettings(context)
            val manager = WorkManager.getInstance(context)
            if (!settings.enabled || settings.treeUri == null) {
                manager.cancelUniqueWork(PERIODIC_WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<AutomaticBackupWorker>(settings.intervalDays.toLong(), TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
                .build()
            manager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun backUpNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AutomaticBackupWorker>().setInputData(workDataOf("manual" to true)).build(),
            )
        }
    }
}
