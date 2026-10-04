package app.lawnchair.backup

import android.app.Application
import android.content.Intent
import android.net.Uri
import app.lawnchair.ui.preferences.navigation.RestoreBackup
import app.lawnchair.ui.preferences.navigation.RestoreNovaBackup
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class BackupAutomationTest {
    @Test
    fun backupsRequireDeviceLocalOptIn() {
        val context = RuntimeEnvironment.getApplication()
        val settings = AutomaticBackupSettings(context)
        settings.preferences.edit().clear().commit()
        assertFalse(settings.automationEnabled)
        BackupAutomationReceiver().onReceive(context, Intent(BackupAutomationReceiver.ACTION_BACKUP))
        assertFalse(settings.preferences.contains("last_automation_request"))
    }

    @Test
    fun restoreWithoutUriOpensDocumentPicker() {
        val activity = Robolectric.buildActivity(BackupRestoreActivity::class.java, Intent(BackupRestoreActivity.ACTION_RESTORE)).create().get()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, shadowOf(activity).nextStartedActivityForResult.intent.action)
    }

    @Test
    fun restoreAcceptsContentUriForEitherReviewScreen() {
        val uri = "content://example.provider/backups/version.lawnchairbackup"
        val intent = Intent(BackupRestoreActivity.ACTION_RESTORE, Uri.parse(uri))
        val encoded = Base64.getEncoder().encodeToString(uri.toByteArray())
        assertEquals(RestoreBackup(encoded), BackupRestoreActivity.routeFor(intent))
        intent.putExtra(BackupRestoreActivity.EXTRA_FORMAT, "nova")
        assertEquals(RestoreNovaBackup(encoded), BackupRestoreActivity.routeFor(intent))
    }

    @Test
    fun restoreRejectsRawPathsAndUnknownFormatsOrActions() {
        listOf("file:///data/data/app.lawnchair.debug/databases/launcher.db", "/sdcard/backup.zip", "https://example.com/backup.zip").forEach {
            assertNull(BackupRestoreActivity.routeFor(Intent(BackupRestoreActivity.ACTION_RESTORE, Uri.parse(it))))
        }
        val intent = Intent(BackupRestoreActivity.ACTION_RESTORE, Uri.parse("content://example.provider/backup"))
        intent.putExtra(BackupRestoreActivity.EXTRA_FORMAT, "unknown")
        assertNull(BackupRestoreActivity.routeFor(intent))
        intent.removeExtra(BackupRestoreActivity.EXTRA_FORMAT)
        intent.action = Intent.ACTION_VIEW
        assertNull(BackupRestoreActivity.routeFor(intent))
    }
}
