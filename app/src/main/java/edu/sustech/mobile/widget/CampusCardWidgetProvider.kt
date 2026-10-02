package edu.sustech.mobile.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import edu.sustech.mobile.R
import edu.sustech.mobile.ui.ServiceActivity
import edu.sustech.mobile.ui.ServicePortalActivity

/** The configurable 2×2 quick-access widget (keeps its original component id for upgrades). */
class CampusCardWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        widgetIds.forEach { updateWidget(context, it) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { QuickAccessWidgetPrefs.clear(context, it) }
        super.onDeleted(context, appWidgetIds)
    }

    companion object {
        private val TILE_IDS = intArrayOf(
            R.id.widget_shortcut_tile_1, R.id.widget_shortcut_tile_2,
            R.id.widget_shortcut_tile_3, R.id.widget_shortcut_tile_4,
        )
        private val ICON_IDS = intArrayOf(
            R.id.widget_shortcut_icon_1, R.id.widget_shortcut_icon_2,
            R.id.widget_shortcut_icon_3, R.id.widget_shortcut_icon_4,
        )
        private val LABEL_IDS = intArrayOf(
            R.id.widget_shortcut_label_1, R.id.widget_shortcut_label_2,
            R.id.widget_shortcut_label_3, R.id.widget_shortcut_label_4,
        )

        fun updateWidget(context: Context, widgetId: Int) {
            val manager = AppWidgetManager.getInstance(context)
            val actions = QuickAccessWidgetPrefs.actions(context, widgetId)
            val views = RemoteViews(context.packageName, R.layout.widget_ecard)
            val tint = ContextCompat.getColor(context, R.color.brand)
            actions.forEachIndexed { index, action ->
                views.setImageViewResource(ICON_IDS[index], action.icon)
                views.setInt(ICON_IDS[index], "setColorFilter", tint)
                views.setTextViewText(LABEL_IDS[index], context.getString(action.label))
                views.setContentDescription(TILE_IDS[index], context.getString(action.label))
                views.setOnClickPendingIntent(
                    TILE_IDS[index], shortcutPendingIntent(context, widgetId, index, action),
                )
            }
            manager.updateAppWidget(widgetId, views)
        }

        private fun shortcutPendingIntent(
            context: Context,
            widgetId: Int,
            slot: Int,
            action: QuickShortcut,
        ): PendingIntent {
            val intent = action.intent(context).apply {
                data = Uri.parse("sustech-shortcut://widget/$widgetId/$slot/${action.key}")
            }
            return PendingIntent.getActivity(
                context, widgetId * 4 + slot, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}

/** Destinations users can assign to the four shortcut tiles. */
enum class QuickShortcut(
    val key: String,
    val label: Int,
    val icon: Int,
    private val serviceId: String? = null,
    private val portalUrl: String? = null,
) {
    ELECTRONIC_CARD(
        "ecard_face", R.string.shortcut_ecard_face, R.drawable.ic_card,
        portalUrl = "https://campuscard.sustech.edu.cn/epay/vcard/index",
    ),
    CAMPUS_QR("campus_qr", R.string.shortcut_campus_qr, R.drawable.ic_scan, "ecard"),
    PRINT("print", R.string.shortcut_print, R.drawable.ic_printer, "pms"),
    BUS("bus", R.string.shortcut_bus, R.drawable.ic_bus, "transit"),
    LIBRARY("library", R.string.shortcut_library, R.drawable.ic_history, "library"),
    BLACKBOARD("blackboard", R.string.shortcut_blackboard, R.drawable.ic_doc, "blackboard"),
    EXCHANGE("exchange", R.string.shortcut_exchange, R.drawable.ic_school, "ws"),
    FACULTY("faculty", R.string.shortcut_faculty, R.drawable.ic_person, "faculty"),
    ;

    fun intent(context: Context): Intent = if (portalUrl != null) {
        ServicePortalActivity.intent(context, portalUrl)
    } else {
        Intent(context, ServiceActivity::class.java).putExtra(
            ServiceActivity.EXTRA_SERVICE, requireNotNull(serviceId),
        )
    }

    companion object {
        val defaults = listOf(ELECTRONIC_CARD, CAMPUS_QR, PRINT, BUS)
    }
}

/** Per-widget shortcut choices. Empty preferences preserve the requested defaults. */
object QuickAccessWidgetPrefs {
    private const val FILE = "quick_access_widgets"

    fun actions(context: Context, widgetId: Int): List<QuickShortcut> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return QuickShortcut.defaults.indices.map { slot ->
            val key = prefs.getString(key(widgetId, slot), null)
            QuickShortcut.entries.firstOrNull { it.key == key } ?: QuickShortcut.defaults[slot]
        }
    }

    fun save(context: Context, widgetId: Int, actions: List<QuickShortcut>) {
        require(actions.size == QuickShortcut.defaults.size)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            actions.forEachIndexed { slot, action -> putString(key(widgetId, slot), action.key) }
        }.apply()
    }

    fun clear(context: Context, widgetId: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            QuickShortcut.defaults.indices.forEach { remove(key(widgetId, it)) }
        }.apply()
    }

    private fun key(widgetId: Int, slot: Int) = "widget_${widgetId}_slot_$slot"
}
