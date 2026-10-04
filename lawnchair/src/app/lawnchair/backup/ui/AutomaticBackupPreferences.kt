package app.lawnchair.backup.ui

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.lawnchair.backup.AutomaticBackupSettings
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.controls.ListPreference
import app.lawnchair.ui.preferences.components.controls.ListPreferenceEntry
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import com.android.launcher3.R
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AutomaticBackupPreferences() {
    val context = LocalContext.current
    val settings = remember { AutomaticBackupSettings(context) }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        settings.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { settings.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val enabled = remember(revision) { settings.enabled }
    val tree = remember(revision) { settings.treeUri }
    val retention = remember(revision) { settings.retention }
    val intervalDays = remember(revision) { settings.intervalDays }
    val lastSuccess = remember(revision) { settings.lastSuccess }
    val lastError = remember(revision) { settings.lastError }

    fun update(change: SharedPreferences.Editor.() -> Unit) {
        scope.launch(Dispatchers.IO) {
            settings.update(change)
            AutomaticBackupSettings.schedule(context)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val flags = result.data!!.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        context.contentResolver.takePersistableUriPermission(uri, flags)
                        require(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
                        settings.update { putString("tree", uri.toString()) }
                        AutomaticBackupSettings.schedule(context)
                    }
                } catch (_: Exception) {
                    Toast.makeText(context, R.string.automatic_backup_permission_error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    PreferenceGroup(heading = stringResource(R.string.automatic_backup_title)) {
        SwitchPreference(
            checked = enabled,
            onCheckedChange = { update { putBoolean("enabled", it) } },
            label = stringResource(R.string.automatic_backup_title),
            description = stringResource(R.string.automatic_backup_description),
            enabled = tree != null,
        )
        ClickablePreference(
            label = stringResource(R.string.automatic_backup_folder),
            subtitle = tree?.let { DocumentsContract.getTreeDocumentId(it) }
                ?: stringResource(R.string.automatic_backup_choose_folder),
            onClick = {
                picker.launch(
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        tree?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
                    },
                )
            },
        )
        ListPreference(
            entries = listOf(
                ListPreferenceEntry(1) { stringResource(R.string.automatic_backup_daily) },
                ListPreferenceEntry(7) { stringResource(R.string.automatic_backup_weekly) },
            ),
            value = intervalDays,
            onValueChange = { update { putInt("interval_days", it) } },
            label = stringResource(R.string.automatic_backup_frequency),
        )
        ListPreference(
            entries = listOf(7, 14, 30).map { count ->
                ListPreferenceEntry(count) { stringResource(R.string.automatic_backup_keep_count, count) }
            },
            value = retention,
            onValueChange = { update { putInt("retention", it) } },
            label = stringResource(R.string.automatic_backup_retention),
        )
        ClickablePreference(
            label = stringResource(R.string.automatic_backup_now),
            subtitle = if (lastSuccess == 0L) {
                stringResource(R.string.automatic_backup_never)
            } else {
                stringResource(R.string.automatic_backup_last_success, DateFormat.getDateTimeInstance().format(Date(lastSuccess)))
            },
            onClick = {
                if (tree == null) {
                    picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
                    return@ClickablePreference
                }
                AutomaticBackupSettings.backUpNow(context)
                Toast.makeText(context, R.string.automatic_backup_queued, Toast.LENGTH_SHORT).show()
            },
        )
        if (lastError != null) {
            ClickablePreference(
                label = stringResource(
                    when (lastError) {
                        "permission" -> R.string.automatic_backup_permission_error
                        "retention" -> R.string.automatic_backup_retention_error
                        else -> R.string.automatic_backup_export_error
                    },
                ),
                onClick = { AutomaticBackupSettings.backUpNow(context) },
            )
        }
    }
}
