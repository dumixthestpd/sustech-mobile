package edu.sustech.mobile.tis
import java.time.LocalDate
data class AgendaEvent(val date: LocalDate, val start: Int, val end: Int, val name: String, val room: String, val exam: Boolean = false, val detail: String = "")
object Agenda {
    fun events(entries: List<ClassEntry>, exams: List<ExamRecord>, week: Int?, days: List<LocalDate>,
        term: edu.sustech.mobile.calendar.Term?, reference: LocalDate): List<AgendaEvent> {
        val monday = reference.minusDays((reference.dayOfWeek.value - 1).toLong())
        val out = mutableListOf<AgendaEvent>()
        for (entry in entries) {
            val dates = if (term != null) term.meetings(entry.effectiveWeeks.ifEmpty { (1..term.totalTeachingWeeks).toList() }, entry.weekday)
            else days.filter { date -> date.dayOfWeek.value == entry.weekday && week?.let {
                entry.meets(it + java.time.temporal.ChronoUnit.WEEKS.between(monday, date.minusDays((date.dayOfWeek.value - 1).toLong())).toInt())
            } == true }
            val range = clockRange(entry.timeText) ?: continue
            dates.filter { it in days }.forEach { date -> out += AgendaEvent(date, range.first, range.second, entry.name, entry.room, detail = entry.timeText + "\n" + entry.teacher) }
        }
        out += exams.mapNotNull(::exam).filter { it.date in days }
        return out.distinct()
    }
    fun clockRange(raw: String): Pair<Int, Int>? {
        val times = Regex("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)").findAll(raw).toList()
        if (times.size != 2) return null
        val minutes = times.map {
            val h = it.groupValues[1].toInt(); val m = it.groupValues[2].toInt()
            if (h !in 0..23 || m !in 0..59) return null
            h * 60 + m
        }
        return (minutes[0] to minutes[1]).takeIf { it.first < it.second }
    }
    fun exam(record: ExamRecord): AgendaEvent? {
        val date = runCatching { LocalDate.parse(record.date.take(10)) }.getOrNull() ?: return null
        val range = clockRange(record.timeText) ?: return null
        return AgendaEvent(date, range.first, range.second, record.course,
            listOf(record.building, record.room).filter { it.isNotBlank() }.distinct().joinToString(" "), true,
            listOf(record.session, record.timeText, record.seat).filter { it.isNotBlank() }.joinToString("\n"))
    }
}
