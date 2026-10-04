package app.lawnchair.backup

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import app.lawnchair.LawnchairProto.BackupInfo
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class RestorableWidgetBindingsTest {
    private val binding = RestorableWidgetBindings.Binding("example.widgets/example.widgets.Button", 0)
    private val owned = mapOf(42 to binding)
    private val identity = "installation-1"

    private fun database() = SQLiteDatabase.create(null).also { db ->
        Favorites.addTableToDb(db, 0, false)
        db.execSQL("INSERT INTO favorites (_id, itemType, appWidgetId, appWidgetProvider, profileId, restored) VALUES (1, 4, 42, 'example.widgets/.Button', 0, 0)")
    }

    private fun collect(db: SQLiteDatabase, archiveIdentity: String = identity) =
        RestorableWidgetBindings.collect(db, archiveIdentity, identity, owned)

    private fun status(db: SQLiteDatabase): Int = db.rawQuery("SELECT restored FROM favorites WHERE _id = 1", null).use {
        check(it.moveToFirst())
        it.getInt(0)
    }

    @Test
    fun sameInstallationPreservesBoundIdsAfterSanitizing() {
        database().use { db ->
            val rows = collect(db)
            assertEquals(1, rows.size)
            db.execSQL("UPDATE favorites SET restored = 7")
            RestorableWidgetBindings.apply(db, rows, owned)
            assertEquals(LauncherAppWidgetInfo.RESTORE_COMPLETED, status(db))
            db.rawQuery("SELECT appWidgetId FROM favorites", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(42, it.getInt(0))
            }
        }
    }

    @Test
    fun legacyOrDifferentInstallationCannotReuseLiveIds() {
        database().use { db ->
            assertTrue(collect(db, "").isEmpty())
            assertTrue(collect(db, "another-installation").isEmpty())
        }
        assertTrue(BackupInfo.getDefaultInstance().installationId.isEmpty())
    }

    @Test
    fun unownedIdsAndMismatchedProvidersOrProfilesCannotBePreserved() {
        database().use { db ->
            assertTrue(RestorableWidgetBindings.collect(db, identity, identity, emptyMap()).isEmpty())
            db.execSQL("UPDATE favorites SET appWidgetProvider = 'other.widgets/.Button'")
            assertTrue(collect(db).isEmpty())
            db.execSQL("UPDATE favorites SET appWidgetProvider = 'example.widgets/.Button', profileId = 10")
            assertTrue(collect(db).isEmpty())
        }
    }

    @Test
    fun invalidIdsRebindAndUnfinishedConfigurationRemainsUnfinished() {
        database().use { db ->
            db.execSQL("UPDATE favorites SET restored = 1")
            assertTrue(collect(db).isEmpty())
            db.execSQL("UPDATE favorites SET restored = 4")
            val rows = collect(db)
            db.execSQL("UPDATE favorites SET restored = 7")
            RestorableWidgetBindings.apply(db, rows, owned)
            assertEquals(LauncherAppWidgetInfo.FLAG_UI_NOT_READY, status(db))
        }
    }

    @Test
    fun losingOwnershipOrChangingRestoredRowPreventsReuse() {
        database().use { db ->
            val rows = collect(db)
            db.execSQL("UPDATE favorites SET restored = 7")
            RestorableWidgetBindings.apply(db, rows, emptyMap())
            assertEquals(7, status(db))
            db.execSQL("UPDATE favorites SET appWidgetProvider = 'other.widgets/.Button'")
            RestorableWidgetBindings.apply(db, rows, owned)
            assertEquals(7, status(db))
            db.execSQL("UPDATE favorites SET appWidgetProvider = 'example.widgets/.Button', appWidgetId = 43")
            RestorableWidgetBindings.apply(db, rows, owned)
            assertEquals(7, status(db))
        }
    }

    @Test
    fun installationIdentityLivesOutsideExportedFilesAndSurvivesNormalPreferenceRestore() {
        val context = RuntimeEnvironment.getApplication()
        val before = BackupInstallationIdentity.get(context)
        context.getSharedPreferences("com.android.launcher3.prefs", 0).edit().clear().commit()
        assertEquals(before, BackupInstallationIdentity.get(context))
        val stored = context.noBackupFilesDir.listFiles()!!.single { it.name == "launcher-backup-installation-id" }
        assertEquals(before, stored.readText())
        assertFalse(stored.toPath().startsWith(context.filesDir.toPath()))
        // Clearing app data removes noBackupFilesDir as well, giving a new installation identity.
        assertTrue(stored.delete())
        assertNotEquals(before, BackupInstallationIdentity.get(context))
    }
}
