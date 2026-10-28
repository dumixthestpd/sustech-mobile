package edu.sustech.mobile.pms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The print host answers outside the campus network with a **200 and a page**,
 * and that page was being reported as an expired session — the message came
 * from the app, not the server. These cases pin the distinction down.
 */
class PmsWireTest {

    /** The real gateway page body (excerpt, HTTP 200, 18983 bytes) from 2026-10-28. */
    private val notice = """
        <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
        <title>联创打印访问提示 | Unifound Printing</title></head>
        <body><h1>该系统仅限校内访问</h1>
        <p>This service is available only from the campus network</p></body></html>
    """.trimIndent()

    /** A CAS sign-in page, which is a real expiry. */
    private val casPage = """
        <!doctype html><html><head><title>统一身份认证</title></head>
        <body><form action="/cas/login" method="post">
        <input type="hidden" name="execution" value="e1s1">
        <input name="username"><input name="password"></form></body></html>
    """.trimIndent()

    @Test
    fun `the campus gateway page is not an expired session`() {
        assertEquals(PmsWire.Html.ACCESS_NOTICE, PmsWire.html(notice))
    }

    @Test
    fun `a cas page is an expired session`() {
        assertEquals(PmsWire.Html.SIGN_IN_PAGE, PmsWire.html(casPage))
    }

    @Test
    fun `a json envelope is never a page`() {
        assertEquals(PmsWire.Html.OTHER, PmsWire.html("""{"code":0,"result":[]}"""))
    }

    @Test
    fun `the gateway page does not look like a sign-in page`() {
        // The check order matters: a page that mentions neither must stay OTHER,
        // and the gateway must never be read as a session failure.
        assertFalse(PmsWire.html(notice) == PmsWire.Html.SIGN_IN_PAGE)
    }

    @Test
    fun `stale envelope messages are recognised`() {
        assertTrue(PmsWire.isStale("请用户重新登录"))
        assertTrue(PmsWire.isStale("无效会话"))
        assertFalse(PmsWire.isStale("文件过大"))
    }
}
