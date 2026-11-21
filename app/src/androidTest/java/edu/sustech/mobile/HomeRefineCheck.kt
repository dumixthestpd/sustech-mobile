package edu.sustech.mobile

import android.app.*
import android.content.Intent
import android.graphics.Rect
import android.os.*
import android.view.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import edu.sustech.mobile.bb.*
import edu.sustech.mobile.core.Cache
import edu.sustech.mobile.ui.*
import edu.sustech.mobile.ui.bb.BbAssessmentText
import edu.sustech.mobile.ui.tis.TimetableGrid
import org.json.JSONObject
import java.time.*
import java.io.File
import android.graphics.Bitmap

/** Synthetic wire records, then actual colors, touch paging and a complete day pointer. */
class HomeRefineCheck(private val instrument: Instrumentation) {
    private fun shot(name: String) {
        SystemClock.sleep(700) // Wait for WSA's next compositor frame after changing the root view.
        val folder = instrument.targetContext.getExternalFilesDir("refine-check")!!; folder.mkdirs()
        val bitmap = instrument.uiAutomation.takeScreenshot()!!
        File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    fun run() {
        val result = Bundle(); val context = instrument.targetContext
        val original = HomePreferences.read(context)
        try {
            val reader = BbAssessmentReader { path ->
                when {
                    path == "/page2" -> JSONObject("""{"results":[{"id":"b","userId":"me","status":"Completed","score":0,"feedback":"<p>Teacher feedback</p>"},{"id":"other","userId":"other","status":"Completed","score":100,"feedback":"PRIVATE OTHER"}]}""")
                    else -> JSONObject("""{"results":[{"id":"a","userId":"me","status":"InProgress"}],"paging":{"nextPage":"/page2"}}""")
                }
            }
            val assessment = reader.assessment("1", JSONObject("""{"id":"col","contentId":"2","name":"Assignment","score":{"possible":100}}"""), "me", emptyList())
            check(assessment.state == SubmissionState.SUBMITTED && assessment.attempts.size == 2)
            val text = BbAssessmentText.detail(context, assessment)
            check(text.contains("0 / 100") && text.contains("Teacher feedback") && !text.contains("PRIVATE OTHER"))
            Cache.clear()
            HomePreferences.write(context, HomeLayout(hero = "schedule"))
            val activity = instrument.startActivitySync(Intent(context, HomeSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AppCompatActivity
            instrument.waitForIdleSync()
            fun seed() { Cache.get("bb.pending", 600000) { BbApi.Pending(listOf(
                BbApi.Deadline("Fixture", "Unsubmitted A", "11-25 12:00", Instant.now().plusSeconds(86400), "1", "2"),
                BbApi.Deadline("Fixture", "Unsubmitted B", "11-26 12:00", Instant.now().plusSeconds(172800), "1", "3")), 0) } }
            seed()
            instrument.runOnMainSync { activity.supportFragmentManager.beginTransaction().replace(R.id.service_container, TodayFragment()).commitNow() }
            instrument.waitForIdleSync()
            instrument.runOnMainSync {
                check(activity.findViewById<com.google.android.material.card.MaterialCardView>(R.id.today_card_schedule).cardBackgroundColor.defaultColor == context.getColor(R.color.brand))
                check(activity.findViewById<com.google.android.material.card.MaterialCardView>(R.id.today_card_ddl).cardBackgroundColor.defaultColor == context.getColor(R.color.surface))
                HomePreferences.write(context, HomeLayout(hero = "ddl"))
            }
            // Recreate the fragment so the real home reads the new ordered presentation.
            seed()
            instrument.runOnMainSync { activity.supportFragmentManager.beginTransaction().replace(R.id.service_container, TodayFragment()).commitNow() }
            instrument.waitForIdleSync(); SystemClock.sleep(500)
            check(activity.findViewById<TextView>(R.id.today_deadline_name).text.toString() == "Unsubmitted A")
            val bounds = Rect(); instrument.runOnMainSync {
                val card = activity.findViewById<View>(R.id.today_card_ddl)
                val location = IntArray(2); card.getLocationOnScreen(location)
                bounds.set(location[0], location[1], location[0]+card.width, location[1]+card.height)
            }
            val y = bounds.centerY().toFloat(); val start = bounds.right-50f; val end = bounds.left+50f; val down = SystemClock.uptimeMillis()
            fun touch(action: Int, x: Float, time: Long) { val event = MotionEvent.obtain(down, time, action, x, y, 0); instrument.uiAutomation.injectInputEvent(event, true); event.recycle() }
            touch(MotionEvent.ACTION_DOWN, start, down)
            for (i in 1..12) { SystemClock.sleep(20); touch(MotionEvent.ACTION_MOVE, start+(end-start)*i/12, SystemClock.uptimeMillis()) }
            touch(MotionEvent.ACTION_UP, end, SystemClock.uptimeMillis()); instrument.waitForIdleSync()
            check(activity.findViewById<TextView>(R.id.today_deadline_name).text.toString() == "Unsubmitted B")
            shot("swiped.png")
            instrument.runOnMainSync {
                val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                activity.setContentView(TimetableGrid(activity).apply { show(listOf(today), listOf(
                    edu.sustech.mobile.tis.AgendaEvent(today, 480, 600, "Morning", "A"),
                    edu.sustech.mobile.tis.AgendaEvent(today, 1080, 1200, "Evening", "B")), true, activity.window.decorView.width, true, LocalTime.NOON) })
            }
            instrument.waitForIdleSync()
            shot("complete-day.png")
            instrument.runOnMainSync {
                check(activity.findViewById<TextView>(R.id.home_now_pointer).text.toString() == "12:00")
                activity.finish()
            }
            result.putString("stream", "PASS: paginated own attempts, submitted filtering, grade zero and teacher feedback; first-card colors follow type; real horizontal assignment swipe; complete day and current-time pointer.")
        } catch (error: Throwable) { result.putString("stream", "FAIL: ${error.stackTraceToString()}") }
        finally { HomePreferences.write(context, original); context.getSharedPreferences("home_layout", 0).edit().commit() }
        instrument.finish(if (result.getString("stream")!!.startsWith("PASS")) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
