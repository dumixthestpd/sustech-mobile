package edu.sustech.mobile.booking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The booking envelope, tested away from Android.
 *
 * Every booking call is a POST of this exact shape; the service answers a
 * malformed one with `IsSuccess: false` and no explanation, so the shape is
 * worth pinning rather than discovering on a device.
 */
class BookingWireTest {

    @Test
    fun `envelope wraps the data with its message type and id`() {
        assertEquals(
            """{"MessageType":5004,"MessageID":"abc-123","Data":{"limit":5,"offset":0}}""",
            BookingWire.envelope("""{"limit":5,"offset":0}""", 5004, "abc-123"),
        )
    }

    @Test
    fun `profile and web call envelopes use the current message types`() {
        val profile = BookingWire.envelope("""{"Url":"x","St":"t"}""", 1001, "id")
        val call = BookingWire.envelope("""{"Url":"x","St":"t"}""", 5005, "id")
        assertTrue(profile.contains("\"MessageType\":1001"))
        assertTrue(call.contains("\"MessageType\":5005"))
    }

    @Test
    fun `message ids are unique uuids`() {
        val first = BookingWire.newMessageId()
        val second = BookingWire.newMessageId()
        assertNotEquals(first, second)
        assertTrue(
            "not a uuid: $first",
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
                .matches(first),
        )
    }

    @Test
    fun `a rejected session is recognised in either language`() {
        assertTrue(BookingWire.looksLikeAuthError("""{"IsSuccess":false,"Message":"Authorization is NULL"}"""))
        assertTrue(BookingWire.looksLikeAuthError("""{"IsSuccess":false,"Message":"请先登录"}"""))
        assertFalse(BookingWire.looksLikeAuthError("""{"IsSuccess":true,"Data":{"rows":[]}}"""))
    }
}
