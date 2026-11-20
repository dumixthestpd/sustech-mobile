package edu.sustech.mobile.weather
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
class AirReadingTest {
    @Test fun `invalid and stale modeled readings never become a valid low AQI`() {
        val now = Instant.parse("2026-11-21T04:00:00Z")
        assertEquals(350, AirReading.valid(350.0, "2026-11-21T12:00", now))
        listOf(null, Double.NaN, -1.0, 501.0).forEach { assertNull(AirReading.valid(it, "2026-11-21T12:00", now)) }
        assertNull(AirReading.valid(30.0, "2026-11-20T12:00", now))
    }
}
