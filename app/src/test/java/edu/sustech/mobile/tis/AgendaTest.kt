package edu.sustech.mobile.tis
import org.junit.Assert.*
import org.junit.Test
class AgendaTest {
    @Test fun `exam placement preserves published date room and seat and leaves unknown times outside grid`() {
        val record = ExamRecord("Physics", "PHY101", "2026-12-02", "Final", "09:30-11:30",
            1, 2, "Teaching 1", "301", "Campus", "12")
        val event = Agenda.exam(record)!!
        assertEquals(java.time.LocalDate.of(2026, 12, 2), event.date)
        assertEquals(570, event.start)
        assertEquals(690, event.end)
        assertEquals("Teaching 1 301", event.room)
        assertTrue(event.exam)
        assertTrue(event.detail.contains("12"))
        assertNull(Agenda.exam(record.copy(timeText = "")))
        assertNull(Agenda.exam(record.copy(date = "unpublished")))
    }
    @Test fun `exam clock accepts separators but rejects guessed periods`() {
        assertEquals(540 to 660, Agenda.clockRange("09:00~11:00"))
        listOf("", "25:00-26:00", "11:00-09:00", "09:00").forEach { assertNull(Agenda.clockRange(it)) }
    }
    @Test fun `fetched teaching periods replace defaults in next class selection`() {
        val previous = (1..11).associateWith { n -> PeriodTimes.of(n).substringBefore('-') to PeriodTimes.of(n).substringAfter('-') }
        PeriodTimes.useFetched(mapOf(1 to ("23:00" to "23:50")))
        val now = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 22); set(java.util.Calendar.MINUTE, 0) }
        assertEquals(1, PeriodTimes.currentPeriod(now))
        PeriodTimes.useFetched(previous)
    }
}
