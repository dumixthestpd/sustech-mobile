package edu.sustech.mobile.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.transit.BusApi

/**
 * Widget setup: pick what the card shows, and for the bus, which stop.
 *
 * The launcher hands us the widget id and waits for [Activity.RESULT_OK] plus
 * `EXTRA_APPWIDGET_ID` — without both, the widget is dropped, so a cancel here
 * finishes with [Activity.RESULT_CANCELED] and the card never appears.
 */
class WidgetConfigActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var stops: Spinner
    private lateinit var stopsLabel: TextView
    private var loadedStops: List<BusApi.Stop> = emptyList()
    private var stopsLoaded = false
    private var stopsLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        App.init(applicationContext)
        setContentView(R.layout.activity_widget_config)

        val kinds = findViewById<RadioGroup>(R.id.widget_kinds)
        stops = findViewById(R.id.widget_stops)
        stopsLabel = findViewById(R.id.widget_stops_label)

        val currentKind = WidgetPrefs.kind(this, widgetId)
        val currentRadio = when (currentKind) {
            WidgetKind.DEADLINES -> R.id.widget_kind_bb
            WidgetKind.CLASSES -> R.id.widget_kind_classes
            WidgetKind.WEATHER -> R.id.widget_kind_weather
            WidgetKind.CAMPUS_CARD_QR -> R.id.widget_kind_bus
            WidgetKind.BUS -> R.id.widget_kind_bus
        }
        kinds.setOnCheckedChangeListener { _, checkedId ->
            val bus = checkedId == R.id.widget_kind_bus
            stops.visibility = if (bus) View.VISIBLE else View.GONE
            stopsLabel.visibility = if (bus) View.VISIBLE else View.GONE
            if (bus) loadStops()
        }
        kinds.check(currentRadio)
        val showStops = currentKind == WidgetKind.BUS
        stops.visibility = if (showStops) View.VISIBLE else View.GONE
        stopsLabel.visibility = if (showStops) View.VISIBLE else View.GONE
        if (showStops) loadStops()

        findViewById<Button>(R.id.widget_cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.widget_save).setOnClickListener { save(kinds) }

    }

    private fun loadStops() {
        if (stopsLoaded || stopsLoading) return
        stopsLoading = true
        runIo(
            block = { App.bus.stops() },
            onOk = { list ->
                stopsLoading = false
                stopsLoaded = true
                loadedStops = list
                // "Automatic" is first and is the default: the card exists
                // for when you are walking, and then the nearest stop is the
                // only one you care about. Picking a stop pins it.
                val labels = listOf(getString(R.string.widget_stop_auto)) + list.map { it.display }
                stops.adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_dropdown_item,
                    labels,
                )
                // Re-open the config for an existing card: show what it uses.
                val current = WidgetPrefs.stopId(this, widgetId)
                val at = list.indexOfFirst { it.id == current }
                stops.setSelection(if (at >= 0) at + 1 else 0)
            },
            onErr = { error ->
                stopsLoading = false
                loadedStops = emptyList()
                stopsLabel.text = getString(R.string.widget_stops_failed, error.message.orEmpty())
            },
        )
    }

    private fun save(kinds: RadioGroup) {
        val kind = when (kinds.checkedRadioButtonId) {
            R.id.widget_kind_bb -> WidgetKind.DEADLINES
            R.id.widget_kind_classes -> WidgetKind.CLASSES
            R.id.widget_kind_weather -> WidgetKind.WEATHER
            else -> WidgetKind.BUS
        }
        // Position 0 is "Automatic (nearest stop)", which stores an empty
        // id — the provider reads that as "use wherever the phone is".
        val chosen = stops.selectedItemPosition - 1
        val stopId = if (kind != WidgetKind.BUS || chosen < 0) ""
        else loadedStops.getOrNull(chosen)?.id.orEmpty()
        WidgetPrefs.write(this, widgetId, kind, stopId)

        // The provider only registers its alarm on update, so nudge it now.
        sendBroadcast(
            Intent(this, InfoWidgetProvider::class.java).apply {
                action = InfoWidgetProvider.ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            },
        )
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}
