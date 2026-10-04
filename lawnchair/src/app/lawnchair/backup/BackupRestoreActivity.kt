package app.lawnchair.backup

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.navigation.PreferenceRoute
import app.lawnchair.ui.preferences.navigation.RestoreBackup
import app.lawnchair.ui.preferences.navigation.RestoreNovaBackup
import com.android.launcher3.R
import java.util.Base64

/** Opens the existing restore review. Restoring still requires the user's on-screen action. */
class BackupRestoreActivity : ComponentActivity() {
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            openReview(Intent(intent).setData(uri))
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == ACTION_RESTORE && intent.data == null) {
            if (savedInstanceState == null) {
                picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"))
            }
            return
        }
        openReview(intent)
    }

    private fun openReview(request: Intent) {
        val route = routeFor(request)
        if (route == null) {
            Toast.makeText(this, R.string.backup_automation_invalid_uri, Toast.LENGTH_LONG).show()
        } else {
            val uri = request.data!!
            try {
                startActivity(
                    PreferenceActivity.createIntent(this, route).apply {
                        data = uri
                        clipData = ClipData.newRawUri("Backup", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                )
            } catch (_: SecurityException) {
                Toast.makeText(this, R.string.backup_automation_invalid_uri, Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }

    companion object {
        const val ACTION_RESTORE = "app.lawnchair.action.RESTORE"
        const val EXTRA_FORMAT = "format"

        internal fun routeFor(intent: Intent): PreferenceRoute? {
            if (intent.action != ACTION_RESTORE) return null
            val uri = intent.data?.takeIf { it.scheme == "content" && !it.authority.isNullOrEmpty() } ?: return null
            val encoded = Base64.getEncoder().encodeToString(uri.toString().toByteArray(Charsets.UTF_8))
            return when (intent.getStringExtra(EXTRA_FORMAT) ?: "lawnchair") {
                "lawnchair" -> RestoreBackup(encoded)
                "nova" -> RestoreNovaBackup(encoded)
                else -> null
            }
        }
    }
}
