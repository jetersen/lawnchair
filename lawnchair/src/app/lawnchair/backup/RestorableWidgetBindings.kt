package app.lawnchair.backup

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.UserManager
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.widget.LauncherWidgetHolder

/** Reuses live bindings only for an archive made by this installation. */
internal object RestorableWidgetBindings {
    data class Binding(val provider: String, val profileId: Long)
    data class Row(val itemId: Long, val widgetId: Int, val binding: Binding, val restoreStatus: Int)

    fun capture(context: Context): Map<Int, Binding> {
        val manager = AppWidgetManager.getInstance(context)
        val users = context.getSystemService(UserManager::class.java)
        val host = AppWidgetHost(context, LauncherWidgetHolder.APPWIDGET_HOST_ID)
        return host.appWidgetIds.asSequence().mapNotNull { id ->
            val provider = manager.getAppWidgetInfo(id) ?: return@mapNotNull null
            val profileId = users.getSerialNumberForUser(provider.profile)
            if (profileId < 0) return@mapNotNull null
            id to Binding(provider.provider.flattenToString(), profileId)
        }.toMap()
    }

    fun collect(
        db: SQLiteDatabase,
        archiveIdentity: String,
        installationIdentity: String,
        ownedBindings: Map<Int, Binding>,
    ): List<Row> {
        if (archiveIdentity.isEmpty() || archiveIdentity != installationIdentity) return emptyList()
        return db.query(
            Favorites.TABLE_NAME,
            arrayOf(Favorites._ID, Favorites.APPWIDGET_ID, Favorites.APPWIDGET_PROVIDER, Favorites.PROFILE_ID, Favorites.RESTORED),
            "${Favorites.ITEM_TYPE} = ?",
            arrayOf(Favorites.ITEM_TYPE_APPWIDGET.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getInt(1)
                    val binding = ownedBindings[id] ?: continue
                    val provider = ComponentName.unflattenFromString(cursor.getString(2) ?: "")
                    val status = cursor.getInt(4)
                    if (provider?.flattenToString() == binding.provider && cursor.getLong(3) == binding.profileId &&
                        status and LauncherAppWidgetInfo.FLAG_ID_NOT_VALID == 0
                    ) {
                        add(Row(cursor.getLong(0), id, binding, status))
                    }
                }
            }
        }
    }

    /** Call after sanitizing the restored database, before any workspace loader sees it. */
    fun apply(db: SQLiteDatabase, rows: List<Row>, ownedBindings: Map<Int, Binding>) {
        rows.forEach { row ->
            // Recheck ownership in case a provider was removed while the archive was restoring.
            if (ownedBindings[row.widgetId] != row.binding) return@forEach
            db.update(
                Favorites.TABLE_NAME,
                ContentValues().apply { put(Favorites.RESTORED, row.restoreStatus) },
                "${Favorites._ID} = ? AND ${Favorites.APPWIDGET_ID} = ? AND ${Favorites.PROFILE_ID} = ? AND ${Favorites.ITEM_TYPE} = ? AND ${Favorites.APPWIDGET_PROVIDER} IN (?, ?)",
                arrayOf(
                    row.itemId.toString(),
                    row.widgetId.toString(),
                    row.binding.profileId.toString(),
                    Favorites.ITEM_TYPE_APPWIDGET.toString(),
                    row.binding.provider,
                    ComponentName.unflattenFromString(row.binding.provider)!!.flattenToShortString(),
                ),
            )
        }
    }
}
