package edu.sustech.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Spinner
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import edu.sustech.mobile.ui.*

/** Native controls -> saved preferences -> real home layout; original choices restored. */
class HomeLayoutCheck(private val instrument: Instrumentation) {
    fun run() {
        val result = Bundle()
        val context = instrument.targetContext
        val original = HomePreferences.read(context)
        var error: Throwable? = null
        try {
            HomePreferences.write(context, HomeLayout())
            val activity = instrument.startActivitySync(Intent(context, HomeSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AppCompatActivity
            instrument.waitForIdleSync()
            instrument.runOnMainSync {
                activity.findViewById<Spinner>(R.id.home_hero).setSelection(0)
            }
            instrument.waitForIdleSync()
            instrument.runOnMainSync {
                activity.findViewById<View>(R.id.home_module_rows).findViewWithTag<SwitchMaterial>("toggle:weather").isChecked = false
                activity.findViewById<View>(R.id.home_module_rows).findViewWithTag<View>("up:services").performClick()
                activity.findViewById<SwitchMaterial>(R.id.home_emphasis).isChecked = true
                activity.findViewById<Spinner>(R.id.home_landing).setSelection(1)
            }
            instrument.waitForIdleSync()
            val configured = HomePreferences.read(context)
            check(configured.hero == "" && "weather" !in configured.visible)
            check(configured.order.indexOf("services") < configured.order.indexOf("bus"))
            check(configured.schedule == "day" && configured.landing == "bb")
            instrument.runOnMainSync {
                activity.findViewById<View>(R.id.home_settings_services_tab).performClick()
                activity.findViewById<View>(R.id.home_service_rows).findViewWithTag<SwitchMaterial>("toggle:blackboard").isChecked = false
                activity.findViewById<View>(R.id.home_service_rows).findViewWithTag<View>("up:tis").performClick()
            }
            instrument.waitForIdleSync()
            val saved = HomePreferences.read(context)
            check(saved.services.first() == "tis" && "blackboard" !in saved.services)
            instrument.runOnMainSync {
                activity.supportFragmentManager.beginTransaction().replace(R.id.service_container, TodayFragment()).commitNow()
            }
            instrument.waitForIdleSync()
            instrument.runOnMainSync {
                val column = activity.findViewById<LinearLayout>(R.id.today_cards)
                val actual = (0 until column.childCount).map { column.getChildAt(it) }
                    .filter { it.id in listOf(R.id.today_card_ddl, R.id.today_card_schedule, R.id.today_card_services,
                        R.id.today_card_bus, R.id.today_card_weather, R.id.today_card_exams) && it.visibility == View.VISIBLE }.map { it.id }
                check(R.id.today_card_weather !in actual)
                check(actual.first() == R.id.today_card_ddl)
                check(activity.findViewById<View>(R.id.today_schedule_preview).visibility == View.VISIBLE)
                val grid = activity.findViewById<android.widget.GridLayout>(R.id.today_shortcuts)
                check(grid.getChildAt(0).contentDescription == context.getString(R.string.service_tis))
                check(edu.sustech.mobile.service.Services.all.size == 13)
                activity.finish()
            }
        } catch (t: Throwable) { error = t }
        finally {
            HomePreferences.write(context, original)
            // Finish waits for the restored choices to reach disk before the process exits.
            check(context.getSharedPreferences("home_layout", android.content.Context.MODE_PRIVATE).edit().commit())
        }
        result.putString("stream", if (error == null) "PASS: native layout selectors, card visibility/reorder, shortcut selection/reorder, saved preferences and actual home/day layout. Original preferences restored." else "FAIL: $error")
        instrument.finish(if (error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
