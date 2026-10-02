package edu.sustech.mobile.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import edu.sustech.mobile.R

/** Lets the launcher configure all four quick-access tiles as the widget is added. */
class QuickAccessWidgetConfigActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var selectors: List<Spinner>

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

        setContentView(R.layout.activity_widget_shortcuts_config)
        findViewById<TextView>(R.id.widget_shortcuts_config_title)
            .setText(R.string.widget_shortcuts_config_title)
        findViewById<TextView>(R.id.widget_shortcuts_config_hint)
            .setText(R.string.widget_shortcuts_config_hint)

        selectors = listOf(
            R.id.widget_shortcut_select_1, R.id.widget_shortcut_select_2,
            R.id.widget_shortcut_select_3, R.id.widget_shortcut_select_4,
        ).map { findViewById(it) }
        val labels = QuickShortcut.entries.map { getString(it.label) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val selected = QuickAccessWidgetPrefs.actions(this, widgetId)
        selectors.forEachIndexed { slot, spinner ->
            spinner.adapter = adapter
            spinner.setSelection(QuickShortcut.entries.indexOf(selected[slot]).coerceAtLeast(0))
        }

        findViewById<Button>(R.id.widget_cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.widget_save).setOnClickListener { save() }
    }

    private fun save() {
        val actions = selectors.map { QuickShortcut.entries[it.selectedItemPosition] }
        QuickAccessWidgetPrefs.save(this, widgetId, actions)
        CampusCardWidgetProvider.updateWidget(this, widgetId)
        setResult(Activity.RESULT_OK, intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
