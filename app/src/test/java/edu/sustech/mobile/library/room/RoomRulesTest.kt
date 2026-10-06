package edu.sustech.mobile.library.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * The rules the booking sheet enforces, pinned. Each one was measured against the
 * service or read off its own page — and each has been wrong at least once.
 */
class RoomRulesTest {

    @Test
    fun `a 3+ person room needs co-applicants and a 1-3 one does not`() {
        assertTrue(RoomApi.needsCoApplicants("305（3-6人）"))
        assertTrue(RoomApi.needsCoApplicants("308（3-10人）"))
        assertTrue(RoomApi.needsCoApplicants("G104（3-7人）"))
        assertFalse(RoomApi.needsCoApplicants("306（1-3人）"))
        assertFalse(RoomApi.needsCoApplicants("C105（1-3人）"))
        // No capacity in the name is not a 3+ room either.
        assertFalse(RoomApi.needsCoApplicants("会议室"))
        assertFalse(RoomApi.needsCoApplicants("3D打印（3D printing）"))
    }

    @Test
    fun `today is offered, and so is the day two days out`() {
        val days = RoomApi.bookableDays()
        assertEquals((RoomApi.MAX_DAYS_AHEAD + 1).toLong(), days.size.toLong())

        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
        val last = Calendar.getInstance().apply {
            time = midnight
            add(Calendar.DAY_OF_MONTH, RoomApi.MAX_DAYS_AHEAD)
        }.time

        // The first day offered is today — booking today is allowed.
        assertEquals(midnight.time, days.first().time)
        assertEquals(last.time, days.last().time)
    }

    @Test
    fun `a clock time belongs to the day it was asked about`() {
        val day = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
        val parsed = RoomApi.scopeStamp("08:30", day)
        requireNotNull(parsed)

        val calendar = Calendar.getInstance().apply { time = parsed }
        assertEquals(8L, calendar.get(Calendar.HOUR_OF_DAY).toLong())
        assertEquals(30L, calendar.get(Calendar.MINUTE).toLong())
        // 08:30 minus midnight is 8.5 hours, never a day out.
        val hours = TimeUnit.MILLISECONDS.toMinutes(parsed.time - day.time) / 60.0
        assertEquals(8.5, hours, 0.01)
    }

    @Test
    fun `a full datetime parses and an empty one is unknown`() {
        assertNotNull(RoomApi.scopeStamp("2026/10/07 08:00:00", Date()))
        assertNotNull(RoomApi.scopeStamp("2026-10-07 08:00", Date()))
        assertNull(RoomApi.scopeStamp("", Date()))
        assertNull(RoomApi.scopeStamp("   ", Date()))
        assertNull(RoomApi.scopeStamp("not a time", Date()))
    }

    @Test
    fun `equipment lending is a lone device named after its own lab`() {
        // The studio, the printer and the scanner, as the service lists them.
        assertTrue(RoomApi.isLending("3D打印（3D printing）", "3D打印（3D printing）", 1))
        assertTrue(RoomApi.isLending("录音棚（Recording Studio）", "录音棚（Recording Studio）", 1))
        // A floor: several devices, and never named after one of them.
        assertFalse(RoomApi.isLending("一丹三层(Yidan 3rd floor)", "304（3-6人）", 8))
        assertFalse(RoomApi.isLending("涵泳一层(Learning Nexus 1st floor)", "C105（1-3人）", 2))
        // A lone device NOT named after its lab is not one either.
        assertFalse(RoomApi.isLending("一丹三层(Yidan 3rd floor)", "304（3-6人）", 1))
    }
}
