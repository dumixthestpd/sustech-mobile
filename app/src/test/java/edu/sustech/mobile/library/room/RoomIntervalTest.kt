package edu.sustech.mobile.library.room

import org.junit.Assert.*
import org.junit.Test

class RoomIntervalTest {
    private fun slot(from: String, to: String, state: RoomSlotState = RoomSlotState.AVAILABLE) = RoomSlot(from, to, state)

    @Test fun `whole requested interval must be covered without gaps`() {
        assertEquals(RoomAvailability.AVAILABLE, RoomInterval.state(listOf(slot("14:00", "14:30"), slot("14:30", "15:00")), 840, 900))
        assertEquals(RoomAvailability.UNKNOWN, RoomInterval.state(listOf(slot("14:00", "14:29"), slot("14:30", "15:00")), 840, 900))
        assertEquals(RoomAvailability.UNKNOWN, RoomInterval.state(null, 840, 900))
        assertEquals(RoomAvailability.UNKNOWN, RoomInterval.state(emptyList(), 840, 900))
    }

    @Test fun `touching reservations do not block free interval but overlap and closure do`() {
        val adjacent = listOf(slot("13:30", "14:00", RoomSlotState.RESERVED), slot("14:00", "15:00"), slot("15:00", "16:00", RoomSlotState.RESERVED))
        assertEquals(RoomAvailability.AVAILABLE, RoomInterval.state(adjacent, 840, 900))
        assertEquals(RoomAvailability.OCCUPIED, RoomInterval.state(adjacent, 840, 901))
        assertEquals(RoomAvailability.CLOSED, RoomInterval.state(listOf(slot("14:00", "15:00", RoomSlotState.CLOSED)), 840, 900))
        assertEquals(RoomAvailability.UNKNOWN, RoomInterval.state(listOf(slot("invalid", "15:00")), 840, 900))
    }

    @Test fun `end at midnight is supported`() {
        assertEquals(RoomAvailability.AVAILABLE, RoomInterval.state(listOf(slot("23:00", "00:00")), 1380, 1440))
    }

    @Test fun `campus booking dates stay in Shanghai when the phone is abroad`() {
        val previous = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
            val day = RoomApi.bookableDays().first()
            assertEquals(java.time.LocalTime.MIDNIGHT,
                day.toInstant().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalTime())
            val clock = RoomApi.scopeStamp("14:30", day)!!
            assertEquals(java.time.LocalTime.of(14, 30),
                clock.toInstant().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalTime())
        } finally { java.util.TimeZone.setDefault(previous) }
    }
}
