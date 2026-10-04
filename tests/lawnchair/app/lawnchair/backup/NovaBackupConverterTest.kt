package app.lawnchair.backup

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.android.launcher3.LauncherSettings.Favorites
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class NovaBackupConverterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun novaSettingsShortcutIsNotImported() {
        SQLiteDatabase.create(null).use { source ->
            SQLiteDatabase.create(null).use { target ->
                Favorites.addTableToDb(source, 0, false)
                Favorites.addTableToDb(target, 0, false)
                source.execSQL("INSERT INTO favorites (_id, itemType, container, screen, cellX, cellY, spanX, spanY, intent) VALUES (1, 0, -100, 0, 0, 0, 1, 1, ?)",
                    arrayOf("#Intent;action=android.intent.action.MAIN;component=com.teslacoilsw.launcher/.preferences.SettingsActivity;end"))
                NovaBackupConverter(RuntimeEnvironment.getApplication(), Uri.EMPTY)
                    .insertNovaItems(source, target, 0, mutableMapOf())
                target.rawQuery("SELECT COUNT(*) FROM favorites", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
            }
        }
    }

    @Test
    fun readsNovaGridAndAppearanceBeforeImport() = runBlocking {
        val database = temporaryFolder.newFile("nova.db")
        SQLiteDatabase.openOrCreateDatabase(database, null).use {
            it.execSQL("CREATE TABLE favorites (itemType INTEGER, container INTEGER, cellX REAL, cellY REAL, spanX REAL, spanY REAL)")
            it.execSQL("INSERT INTO favorites VALUES (0, -100, 0, 0, 1, 1), (2, -101, 0, 0, 1, 1), (4, -100, 4, 1.5, 1, 0.5)")
        }
        val archive = temporaryFolder.newFile("test.novabackup")
        ZipOutputStream(archive.outputStream()).use {
            it.putNextEntry(ZipEntry("nova.db"))
            database.inputStream().use { input -> input.copyTo(it) }
            it.putNextEntry(ZipEntry("nova.xml"))
            it.write("""<map><string name="desktop_grid">7x5 subgrid</string><int name="dock_grid_cols" value="5"/><string name="desktop_cellspecs">1.2:false:13.0:262914:true:sans-serif-condensed:true:false</string><string name="searchbar_placement">NONE</string><boolean name="desktop_lock" value="true"/></map>""".toByteArray())
        }
        val info = NovaBackupConverter(RuntimeEnvironment.getApplication(), Uri.fromFile(archive)).parseInfo()
        assertEquals(5, info.columns)
        assertEquals(7, info.rows)
        assertEquals(5, info.hotseatCount)
        assertEquals(1, info.appCount)
        assertEquals(1, info.folderCount)
        assertEquals(1, info.widgetCount)
        assertEquals(1.2f, info.appearance.iconScale)
        assertEquals(false, info.appearance.showLabels)
        assertEquals(true, info.appearance.lockDesktop)
        assertTrue(info.appearance.hideSearchBar)
    }
}
