package edu.sustech.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import edu.sustech.mobile.core.Cache
import edu.sustech.mobile.library.room.*
import edu.sustech.mobile.ui.room.RoomFragment
import edu.sustech.mobile.ui.ws.ExchangeFragment
import edu.sustech.mobile.ui.pms.UploadActivity
import edu.sustech.mobile.ws.WsProgram
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Actual search/filter controls with synthetic records; no authentication or submissions. */
class SearchCheck(private val instrument: Instrumentation) {
    private lateinit var activity: AppCompatActivity
    private fun ui(action: () -> Unit) = instrument.runOnMainSync(action)
    fun run() {
        val result = Bundle()
        try {
            Cache.clear()
            Cache.get("ws.programs.catalog", 600000) { listOf(
                WsProgram("1", "Oxford research", "UK", "Oxford", "Short", "", "", true, "", "", "2027"),
                WsProgram("2", "Oxford summer", "UK", "Oxford", "Short", "", "", false, "", "", "2026"),
                WsProgram("3", "Late page project", "US", "Yale", "Long", "", "", true, "", "", "2027")) }
            activity = instrument.startActivitySync(Intent(instrument.targetContext, UploadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AppCompatActivity
            ui {
                activity.setContentView(FrameLayout(activity).apply { id = R.id.service_container })
                activity.supportFragmentManager.beginTransaction().replace(R.id.service_container, ExchangeFragment()).commitNow()
            }
            await("Late page project")
            ui { activity.findViewById<EditText>(R.id.ws_search).setText("Oxford") }
            waitCount(2)
            select(R.id.ws_year, "2027")
            ui { activity.findViewById<CheckBox>(R.id.ws_eligible).isChecked = true }
            waitCount(1); await("Oxford research"); shot("ws-search.png")
            ui { activity.findViewById<android.view.View>(R.id.ws_clear).performClick() }
            waitCount(3)
            ui { activity.findViewById<EditText>(R.id.ws_search).setText("Late page") }
            waitCount(1); await("Late page project")

            val rooms = listOf(
                RoomInfo(1, "Free tomorrow", "North", 4, "1-3", false, 10, false),
                RoomInfo(2, "Busy tomorrow", "South", 5, "1-3", false, 10, true),
                RoomInfo(3, "Unconfirmed room", "North", 4, "1-3", false, 10, true))
            Cache.get("rooms.all.search", 600000) { rooms }
            Cache.get("rooms.count", 600000) { 0 }
            val date = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE)
            Cache.get("rooms.calendar.4.$date", 600000) { mapOf(1 to listOf(RoomSlot("14:00", "15:00", RoomSlotState.AVAILABLE))) }
            Cache.get("rooms.calendar.5.$date", 600000) { mapOf(2 to listOf(RoomSlot("14:00", "15:00", RoomSlotState.RESERVED))) }
            ui { activity.supportFragmentManager.beginTransaction().replace(R.id.service_container, RoomFragment()).commitNow() }
            await("Free tomorrow")
            ui {
                activity.findViewById<CheckBox>(R.id.rooms_future).isChecked = true
                activity.findViewById<android.view.View>(R.id.rooms_find).performClick()
            }
            waitCount(1); await(instrument.targetContext.getString(R.string.rooms_interval_available)); await("Free tomorrow")
            select(R.id.rooms_floor, "South"); waitCount(0)
            select(R.id.rooms_floor, instrument.targetContext.getString(R.string.rooms_floor_all)); waitCount(1)
            ui { val group = activity.findViewById<ChipGroup>(R.id.rooms_filters); group.check(group.getChildAt(0).id) }
            waitCount(3); await("Unconfirmed room")
            await(instrument.targetContext.getString(R.string.rooms_interval_unknown)); shot("future-rooms.png")
            ui { activity.findViewById<EditText>(R.id.rooms_search).setText("Free tomorrow") }
            waitCount(1)
            result.putString("stream", "PASS: native WS keyword/year/eligibility/reset controls; future room availability differs from current occupancy; floor and text combine; missing calendars remain unknown. Synthetic data, no writes.")
            instrument.finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: $error")
            instrument.finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun select(id: Int, text: String) = ui {
        val spinner = activity.findViewById<Spinner>(id)
        spinner.setSelection((0 until spinner.count).first { spinner.getItemAtPosition(it).toString() == text })
    }
    private fun waitCount(expected: Int) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            var count = -1
            ui { count = activity.findViewById<RecyclerView>(R.id.list).adapter!!.itemCount }
            if (count == expected) return
            SystemClock.sleep(100)
        }
        error("Expected $expected result rows")
    }
    private fun find(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        if (node.text?.contains(text) == true) return true
        return (0 until node.childCount).any { find(node.getChild(it), text) }
    }
    private fun await(text: String) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            if (find(instrument.uiAutomation.rootInActiveWindow, text)) return
            SystemClock.sleep(150)
        }
        error("Missing text: $text")
    }
    private fun shot(name: String) {
        SystemClock.sleep(700)
        val folder = instrument.targetContext.getExternalFilesDir("search-check")!!; folder.mkdirs()
        val bitmap = instrument.uiAutomation.takeScreenshot()!!
        File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}
