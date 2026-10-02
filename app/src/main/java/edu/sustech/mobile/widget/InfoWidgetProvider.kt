package edu.sustech.mobile.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The home-screen widget: one configurable card per widget instance.
 *
 * ## How it stays current
 *
 * A widget's own `updatePeriodMillis` floor is 30 minutes, so the card drives
 * itself:
 *
 *   1. **on tap** — the case that matters, standing at the stop;
 *   2. **on an alarm** — each card reaches for the network on its cadence
 *      (bus: 60s, the rest: 15 min). The alarm deliberately does **not** skip refreshes while the
 *      screen is off: `ELAPSED_REALTIME` alarms are deferred by the platform
 *      while idle and fire promptly once the device is picked up, so gating on
 *      the screen only ever made the card look frozen;
 *   3. **on system update** ([onUpdate]) — reboot, re-add, package replace.
 *
 * ## Why it never goes blank
 *
 * The provider process is started for a broadcast and torn down again, so an
 * in-memory cache cannot serve it. Every successful fetch is written to
 * [WidgetStore]; a failed fetch falls back to that snapshot and says how old
 * it is. A card therefore always shows the newest data it has — an error is
 * never the *content*, only a footer.
 */
class InfoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        widgetIds: IntArray,
    ) {
        App.init(context.applicationContext)
        scheduleTicks(context)
        refresh(context, manager, widgetIds.toList(), force = true)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val manager = AppWidgetManager.getInstance(context)
        when (intent.action) {
            ACTION_REFRESH -> {
                App.init(context.applicationContext)
                scheduleTicks(context)
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                // A tap is an explicit "show me now" — always hit the network.
                if (id != -1) refresh(context, manager, listOf(id), force = true)
                return
            }
            ACTION_TICK -> {
                App.init(context.applicationContext)
                refresh(context, manager, ids(context).toList(), force = false)
                return
            }
        }
        super.onReceive(context, intent)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach {
            WidgetPrefs.clear(context, it)
            WidgetStore.clear(context, it)
        }
        scheduleTicks(context)
        super.onDeleted(context, appWidgetIds)
    }

    override fun onDisabled(context: Context) {
        cancelTicks(context)
        super.onDisabled(context)
    }

    /**
     * Paints the given widgets in one go.
     *
     * The fetch runs off the main thread because a widget broadcast gets about
     * ten seconds before the system kills it — but `goAsync()` may only be
     * taken **once per broadcast** and finished exactly once
     * (`BroadcastReceiver` hands the same, then-null token afterwards, which
     * used to crash this provider). Every entry point funnels through here:
     * one token, one background job, one finish.
     */
    private fun refresh(
        context: Context,
        manager: AppWidgetManager,
        widgetIds: List<Int>,
        force: Boolean,
    ) {
        if (widgetIds.isEmpty()) return
        val pending = goAsync()
        executor.execute {
            try {
                widgetIds.forEach { paint(context, manager, it, force) }
            } finally {
                pending?.finish()
            }
        }
    }

    /** Fetches (or reuses) one widget's data and hands the card to the launcher. */
    private fun paint(context: Context, manager: AppWidgetManager, widgetId: Int, force: Boolean) {
        val kind = WidgetPrefs.kind(context, widgetId)
        val stopId = WidgetPrefs.stopId(context, widgetId)
        val stored = WidgetStore.load(context, widgetId)
        val due = force || stored == null ||
            System.currentTimeMillis() - stored.at >= kind.cadenceMillis

        val fresh = if (due) runCatching { WidgetData.snapshot(context, kind, stopId) }
            .getOrElse { error ->
                // Keep the reason for the footer this render is about to show.
                WidgetPrefs.rememberReason(context, widgetId, WidgetData.shortReason(context, error))
                null
            } else null
        if (fresh != null) WidgetStore.save(context, widgetId, fresh)

        val snapshot = fresh ?: stored
        val footer = when {
            fresh != null -> stamp(fresh.at)
            // Stale but present: the numbers stay, the age is stated.
            stored != null -> "⚠ ${lastReason(context, widgetId) ?: "update failed"} · data ${age(stored.at)}"
            else -> "no data yet · tap to retry"
        }
        val title = snapshot?.title ?: WidgetData.titleOf(context, kind)
        val lines = snapshot?.lines ?: emptyList()
        manager.updateAppWidget(
            widgetId,
            views(context, widgetId, title, lines, footer),
        )
    }

    private fun views(
        context: Context,
        widgetId: Int,
        title: String,
        lines: List<String>,
        footer: String,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_info).apply {
        setTextViewText(R.id.widget_title, title)
        setTextViewText(R.id.widget_footer, footer)
        // RemoteViews has no list; three fixed rows, hidden when unused.
        val rowIds = intArrayOf(R.id.widget_line1, R.id.widget_line2, R.id.widget_line3)
        rowIds.forEachIndexed { index, rowId ->
            val line = lines.getOrNull(index)
            setTextViewText(rowId, line.orEmpty())
            setViewVisibility(rowId, if (line == null) View.GONE else View.VISIBLE)
        }
        setOnClickPendingIntent(R.id.widget_refresh, refreshIntent(context, widgetId))
        setOnClickPendingIntent(R.id.widget_root, openAppIntent(context, widgetId))
    }

    private fun refreshIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, InfoWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = android.net.Uri.parse("sustech-widget://refresh/$widgetId")
        }
        return PendingIntent.getBroadcast(
            context,
            widgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Tapping the card opens whatever the card mirrors. */
    private fun openAppIntent(context: Context, widgetId: Int): PendingIntent {
        val serviceId = when (WidgetPrefs.kind(context, widgetId)) {
            WidgetKind.BUS -> "transit"
            WidgetKind.DEADLINES -> "blackboard"
            WidgetKind.CLASSES -> "tis"
            WidgetKind.CAMPUS_CARD_QR -> "ecard"
            // Weather is a Today-panel number, and Today is the app's front door.
            WidgetKind.WEATHER -> null
        }
        val intent = if (serviceId != null) {
            Intent().setClassName(context, "edu.sustech.mobile.ui.ServiceActivity")
                .putExtra("service", serviceId)
        } else {
            Intent().setClassName(context, "edu.sustech.mobile.ui.LoginActivity")
        }
        return PendingIntent.getActivity(
            context,
            widgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // -- Alarm ----------------------------------------------------------------

    /**
     * A repeating heartbeat while any widget exists; QR cards shorten it to 30 seconds.
     *
     * It is a cheap heartbeat, not a fetch: each card decides from its own
     * cadence whether this tick is worth a request, so slow cards still update
     * only once per 15 minutes.
     */
    private fun scheduleTicks(context: Context) {
        val widgetIds = ids(context)
        if (widgetIds.isEmpty()) {
            cancelTicks(context)
            return
        }
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + MINUTE,
            MINUTE,
            tickIntent(context),
        )
    }

    private fun cancelTicks(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.cancel(tickIntent(context))
    }

    private fun tickIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, InfoWidgetProvider::class.java).apply { action = ACTION_TICK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ids(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, InfoWidgetProvider::class.java))

    /** The reason the last fetch failed, kept for the next failure footer. */
    private fun lastReason(context: Context, widgetId: Int): String? =
        WidgetPrefs.lastReason(context, widgetId)

    private fun stamp(at: Long): String =
        "updated " + SimpleDateFormat("HH:mm", Locale.US).format(Date(at))

    private fun age(at: Long): String {
        val minutes = (System.currentTimeMillis() - at) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            else -> "${minutes / 60} h ago"
        }
    }

    companion object {
        const val ACTION_REFRESH = "edu.sustech.mobile.WIDGET_REFRESH"
        const val ACTION_TICK = "edu.sustech.mobile.WIDGET_TICK"
        private const val MINUTE = 60_000L
        private val executor = Executors.newSingleThreadExecutor()
    }
}

/** Per-widget configuration, keyed by the launcher-assigned widget id. */
object WidgetPrefs {

    private const val FILE = "widgets"
    private const val KEY_KIND = "kind"
    private const val KEY_STOP = "stop"
    private const val KEY_REASON = "reason"

    private fun store(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun write(context: Context, widgetId: Int, kind: WidgetKind, stopId: String) {
        store(context).edit()
            .putString("$KEY_KIND$widgetId", kind.key)
            .putString("$KEY_STOP$widgetId", stopId)
            .apply()
    }

    fun kind(context: Context, widgetId: Int): WidgetKind =
        WidgetKind.of(store(context).getString("$KEY_KIND$widgetId", null))

    fun stopId(context: Context, widgetId: Int): String =
        store(context).getString("$KEY_STOP$widgetId", "").orEmpty()

    fun rememberReason(context: Context, widgetId: Int, reason: String) {
        store(context).edit().putString("$KEY_REASON$widgetId", reason).apply()
    }

    fun lastReason(context: Context, widgetId: Int): String? =
        store(context).getString("$KEY_REASON$widgetId", null)

    fun clear(context: Context, widgetId: Int) {
        store(context).edit()
            .remove("$KEY_KIND$widgetId")
            .remove("$KEY_STOP$widgetId")
            .remove("$KEY_REASON$widgetId")
            .apply()
    }
}
