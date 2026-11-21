package edu.sustech.mobile

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import edu.sustech.mobile.ui.*
import java.io.File

/** Real drag input -> disk preferences -> real home. No campus writes. */
class HomeLayoutCheck(private val instrument: Instrumentation) {
    private fun shot(name: String) {
        SystemClock.sleep(700)
        val folder = instrument.targetContext.getExternalFilesDir("drag-check")!!; folder.mkdirs()
        val bitmap = instrument.uiAutomation.takeScreenshot()!!
        File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
    fun run() {
        val result = Bundle(); val context = instrument.targetContext
        val original = HomePreferences.read(context)
        var activity: AppCompatActivity? = null
        try {
            HomePreferences.write(context, HomeLayout())
            val host = instrument.startActivitySync(Intent(context, HomeSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AppCompatActivity
            activity = host; instrument.waitForIdleSync(); SystemClock.sleep(500)
            fun drag(from: String, to: String) {
                val start = IntArray(2); val end = IntArray(2)
                instrument.runOnMainSync {
                    val list = host.findViewById<RecyclerView>(R.id.home_reorder_list)
                    val a = list.findViewWithTag<View>("drag:$from"); val b = list.findViewWithTag<View>("drag:$to")
                    check(a != null && b != null) { "Drag row missing: $from/$to" }
                    a.getLocationOnScreen(start); b.getLocationOnScreen(end)
                    start[0] += a.width/2; start[1] += a.height/2
                    end[0] += b.width/2; end[1] += b.height/2
                    // Cross the target row instead of stopping on ItemTouchHelper's exact boundary.
                    end[1] += (if (end[1] < start[1]) -1 else 1) * (16 * context.resources.displayMetrics.density).toInt()
                }
                val down = SystemClock.uptimeMillis()
                fun touch(action: Int, x: Float, y: Float) {
                    val event = MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    check(instrument.uiAutomation.injectInputEvent(event,true)); event.recycle()
                }
                touch(MotionEvent.ACTION_DOWN,start[0].toFloat(),start[1].toFloat());SystemClock.sleep(120)
                for (i in 1..24) { touch(MotionEvent.ACTION_MOVE,start[0]+(end[0]-start[0])*i/24f,start[1]+(end[1]-start[1])*i/24f);SystemClock.sleep(30) }
                touch(MotionEvent.ACTION_UP,end[0].toFloat(),end[1].toFloat())
                instrument.waitForIdleSync();SystemClock.sleep(500)
            }
            drag("schedule","ddl")
            check(HomePreferences.read(context).hero == "" && HomePreferences.read(context).displayed().first() == "schedule")
            instrument.runOnMainSync {
                host.findViewById<RecyclerView>(R.id.home_reorder_list).findViewWithTag<SwitchMaterial>("toggle:weather").isChecked = false
                host.findViewById<Spinner>(R.id.home_landing).setSelection(1)
            }
            instrument.waitForIdleSync();shot("cards.png")
            instrument.runOnMainSync { host.findViewById<View>(R.id.home_settings_services_tab).performClick() }
            instrument.waitForIdleSync();SystemClock.sleep(300)
            drag("tis","blackboard")
            instrument.runOnMainSync {
                host.findViewById<RecyclerView>(R.id.home_reorder_list).findViewWithTag<SwitchMaterial>("toggle:blackboard").isChecked = false
            }
            val saved = HomePreferences.read(context)
            check(saved.services.first() == "tis" && "blackboard" !in saved.services)
            check("weather" !in saved.visible && saved.landing == "bb" && saved.schedule == "day")
            shot("services.png")
            instrument.runOnMainSync { host.supportFragmentManager.beginTransaction().replace(R.id.service_container,TodayFragment()).commitNow() }
            instrument.waitForIdleSync();SystemClock.sleep(500)
            instrument.runOnMainSync {
                val column = host.findViewById<LinearLayout>(R.id.today_cards)
                val actual = (0 until column.childCount).map { column.getChildAt(it) }.filter { it is com.google.android.material.card.MaterialCardView && it.visibility == View.VISIBLE }
                check(actual.first().id == R.id.today_card_schedule)
                check(host.findViewById<View>(R.id.today_card_weather).visibility == View.GONE)
                check(context.resources.getIdentifier("today_layout","id",context.packageName) == 0)
                check(host.findViewById<android.widget.GridLayout>(R.id.today_shortcuts).getChildAt(0).contentDescription == context.getString(R.string.service_tis))
            }
            shot("home.png")
            result.putString("stream","PASS: real card and shortcut drag gestures; visibility and launch settings; saved order drives home; no home title/layout entry. Original preferences restored.")
        } catch (error: Throwable) { result.putString("stream","FAIL: ${error.stackTraceToString()}");runCatching { shot("failure.png") } }
        finally {
            activity?.let { host -> instrument.runOnMainSync { host.finish() } }
            HomePreferences.write(context,original);context.getSharedPreferences("home_layout",0).edit().commit()
        }
        instrument.finish(if (result.getString("stream")!!.startsWith("PASS")) Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
    }
}
