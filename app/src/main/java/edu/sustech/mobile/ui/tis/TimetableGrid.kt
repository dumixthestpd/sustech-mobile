package edu.sustech.mobile.ui.tis
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import edu.sustech.mobile.R
import edu.sustech.mobile.tis.AgendaEvent
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class TimetableGrid(context: Context) : LinearLayout(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    fun show(days: List<LocalDate>, events: List<AgendaEvent>, dayOnly: Boolean, availableWidth: Int = resources.displayMetrics.widthPixels) {
        orientation = VERTICAL
        val start = minOf(480, events.minOfOrNull { it.start / 60 * 60 } ?: 480)
        val end = maxOf(1320, events.maxOfOrNull { (it.end + 59) / 60 * 60 } ?: 1320)
        val hourHeight = dp(64)
        val width = if (dayOnly) maxOf(dp(240), availableWidth - dp(52)) else dp(112)
        val heading = LinearLayout(context)
        heading.addView(TextView(context), LayoutParams(dp(52), dp(48)))
        for (day in days) heading.addView(TextView(context).apply {
            text = day.format(DateTimeFormatter.ofPattern("EEE\nMM-dd", resources.configuration.locales[0]))
            gravity = Gravity.CENTER; setBackgroundColor(Color.rgb(230, 241, 233))
        }, LayoutParams(width, dp(48)))
        addView(heading)
        val row = LinearLayout(context)
        val clock = FrameLayout(context)
        for (minute in start until end step 60) clock.addView(TextView(context).apply {
            text = "%02d:00".format(minute / 60); textSize = 11f; gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }, FrameLayout.LayoutParams(dp(52), hourHeight).apply { topMargin = (minute - start) * hourHeight / 60 })
        row.addView(clock, LayoutParams(dp(52), (end - start) * hourHeight / 60))
        for (day in days) {
            val column = FrameLayout(context)
            for (minute in start until end step 60) column.addView(android.view.View(context).apply { setBackgroundColor(Color.rgb(220, 228, 221)) }, FrameLayout.LayoutParams(width, dp(1)).apply { topMargin = (minute - start) * hourHeight / 60 })
            val groups = mutableListOf<MutableList<AgendaEvent>>()
            for (event in events.filter { it.date == day }.sortedBy { it.start }) {
                val group = groups.lastOrNull()
                if (group == null || event.start >= group.maxOf { it.end }) groups.add(mutableListOf(event)) else group.add(event)
            }
            for (group in groups) {
                val ends = mutableListOf<Int>(); val positions = group.map { event ->
                    var lane = ends.indexOfFirst { it <= event.start }; if (lane < 0) { lane = ends.size; ends.add(event.end) } else ends[lane] = event.end
                    event to lane
                }
                for ((event, lane) in positions) column.addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(4), dp(3), dp(3), 0)
                    setBackgroundColor(if (event.exam) Color.rgb(255, 225, 191) else Color.rgb(216, 237, 225))
                    addView(TextView(context).apply {
                        text = if (event.exam) context.getString(R.string.agenda_exam) + " · " + event.name else event.name
                        textSize = 11f; maxLines = if (event.end-event.start < 60) 1 else 2
                        ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(Color.rgb(20,62,47))
                    })
                    addView(TextView(context).apply {
                        text = event.room; textSize = 10f; maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(Color.rgb(20,62,47))
                    })
                    contentDescription = "${event.date} ${event.name} ${event.room} ${event.detail}"
                    setOnClickListener { AlertDialog.Builder(context).setTitle(event.name).setMessage((if(event.exam) context.getString(R.string.agenda_exam)+"\n" else "")+"${event.date}\n${event.room}\n${event.detail}").setPositiveButton(android.R.string.ok, null).show() }
                }, FrameLayout.LayoutParams(width / ends.size - dp(3), maxOf(dp(28), (event.end - event.start) * hourHeight / 60 - dp(2))).apply {
                    leftMargin = lane * width / ends.size + dp(2); topMargin = (event.start - start) * hourHeight / 60
                })
            }
            row.addView(column, LayoutParams(width, (end - start) * hourHeight / 60))
        }
        addView(row)
    }
}
