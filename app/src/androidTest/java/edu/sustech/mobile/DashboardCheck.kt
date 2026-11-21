package edu.sustech.mobile

import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import edu.sustech.mobile.bb.*
import edu.sustech.mobile.core.Cache
import edu.sustech.mobile.tis.AgendaEvent
import edu.sustech.mobile.ui.bb.*
import edu.sustech.mobile.ui.pms.UploadActivity
import edu.sustech.mobile.ui.tis.TimetableGrid
import java.time.LocalDate
import java.time.ZoneId
import java.io.File

/** Synthetic records only: real fragments and views without authenticated network requests. */
class DashboardCheck(private val instrument: Instrumentation) {
    private val targetContext get() = instrument.targetContext
    private val uiAutomation get() = instrument.uiAutomation
    private fun runOnMainSync(block: Runnable) = instrument.runOnMainSync(block)
    private fun startActivitySync(intent: Intent) = instrument.startActivitySync(intent)
    private fun finish(code: Int, result: Bundle) = instrument.finish(code, result)
    fun run() {
        val result = Bundle()
        try {
            val activity = startActivitySync(Intent(targetContext, UploadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as AppCompatActivity
            val date = LocalDate.now(ZoneId.of("Asia/Shanghai"))
            val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
            val events = listOf(AgendaEvent(monday, 540, 660, "Physics", "Room 203", detail="09:00–11:00"),
                AgendaEvent(monday, 570, 690, "Materials exam", "Exam hall 301", true, "09:30–11:30 · Seat 12"),
                AgendaEvent(monday.plusDays(2), 840, 940, "Lab practice", "Engineering 205"))
            runOnMainSync { activity.setContentView(TimetableGrid(activity).apply { show((0L..6L).map { monday.plusDays(it) }, events, false) }) }
            await("Materials exam"); shot("week.png")
            val windowWidth = activity.window.decorView.width
            runOnMainSync { activity.setContentView(TimetableGrid(activity).apply { show(listOf(monday), events.filter { it.date==monday }, true, windowWidth) }) }
            await("Room 203"); shot("day.png")
            Cache.get("bb.tree.1.root", 600000) { listOf(BbItem("2", "_2_1", "Course materials remain visible", BbItem.Kind.DOCUMENT, false)) }
            Cache.get("bb.notices.1", 600000) { (1..20).map { BbApi.Notice("Notice $it", "2026-11-21", "Announcement text $it") } }
            runOnMainSync {
                activity.setContentView(FrameLayout(activity).apply { id=R.id.service_container })
                activity.supportFragmentManager.beginTransaction().replace(R.id.service_container, BbContentsFragment.newInstance("1", "Fixture course")).commitNow()
            }
            await("Course materials remain visible"); shot("notices.png")
            runOnMainSync {
                check(activity.findViewById<android.widget.LinearLayout>(R.id.bb_notice_list).childCount == 1)
                check(activity.findViewById<android.view.View>(R.id.bb_notice_list).height < activity.window.decorView.height / 3)
            }
            Cache.get("bb.deadlines", 600000) { listOf(BbApi.Deadline("Fixture course", "Open this assignment", "11-22 12:00", null, "1", "2")) }
            Cache.get("bb.item.1.2", 600000) { BbApi.ItemDetail("Assignment detail", BbItem.Kind.DOCUMENT, "Correct assignment destination 1/2", emptyList()) }
            runOnMainSync { activity.supportFragmentManager.beginTransaction().replace(R.id.service_container, BbDeadlinesFragment()).commitNow() }
            await("Open this assignment"); click("Open this assignment"); await("Correct assignment destination 1/2"); shot("deadline-target.png")
            val direct = startActivitySync(edu.sustech.mobile.ui.ServiceActivity.intent(targetContext,
                edu.sustech.mobile.service.Services.blackboard).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("bb_course", "1").putExtra("bb_content", "2").putExtra("bb_course_name", "Fixture course"))
            await("Correct assignment destination 1/2")
            runOnMainSync { direct.finish() }
            val deadlines = startActivitySync(edu.sustech.mobile.ui.ServiceActivity.intent(targetContext,
                edu.sustech.mobile.service.Services.blackboard).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("bb_deadlines", true))
            await("Open this assignment")
            runOnMainSync { deadlines.finish() }
            result.putString("stream", "PASS: real day/week grids, overlapping class and exam, bounded 20-notice summary, BB deadline-to-item navigation. Synthetic records; no submissions.")
            finish(Activity.RESULT_OK,result)
        } catch(t:Throwable) { result.putString("stream","FAIL: $t"); runCatching { shot("failure.png") }; finish(Activity.RESULT_CANCELED,result) }
    }
    private fun find(node:AccessibilityNodeInfo?, text:String):AccessibilityNodeInfo? {
        if(node==null)return null
        if(node.text?.contains(text)==true)return node
        for(i in 0 until node.childCount) find(node.getChild(i),text)?.let{return it}
        return null
    }
    private fun await(text:String) {
        val until=SystemClock.uptimeMillis()+15000
        while(SystemClock.uptimeMillis()<until) { if(find(uiAutomation.rootInActiveWindow,text)!=null)return;SystemClock.sleep(250) }
        error("Missing text: $text")
    }
    private fun click(text:String) {
        var node=find(uiAutomation.rootInActiveWindow,text)
        while(node!=null&&!node.isClickable)node=node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
    }
    private fun shot(name:String) {
        SystemClock.sleep(700)
        val folder=targetContext.getExternalFilesDir("dashboard-check")!!;folder.mkdirs()
        val image=uiAutomation.takeScreenshot()!!
        File(folder,name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
    }
}
