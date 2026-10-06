package edu.sustech.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a failed probe is reported. The distinction that matters: a service that only
 * answers on campus must not be called broken off campus — it said plain "网络错误"
 * for a while, which reads as "printing is down" while printing works fine there.
 */
class SessionProbeTest {

    @Test
    fun `no error is a healthy session`() {
        assertEquals(SessionProbe.OK, sessionProbe(null))
    }

    @Test
    fun `a transport failure is unreachable, not a broken session`() {
        // What OkHttp's failures surface as: no HTTP status at all.
        assertEquals(SessionProbe.UNREACHABLE, sessionProbe(ApiException("timeout")))
        assertEquals(
            SessionProbe.UNREACHABLE,
            sessionProbe(ApiException("off campus", offCampus = true, httpStatus = 403)),
        )
    }

    @Test
    fun `a refusal is told apart from a failure`() {
        assertEquals(
            SessionProbe.REFUSED,
            sessionProbe(ApiException("method not allowed", httpStatus = 405)),
        )
        assertEquals(
            SessionProbe.FAILED,
            sessionProbe(ApiException("bad reply", httpStatus = 500)),
        )
        assertEquals(SessionProbe.FAILED, sessionProbe(IllegalStateException("boom")))
    }
}
