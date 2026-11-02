package edu.sustech.mobile.bb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The wire rules that a malformed or surprising Blackboard reply must not break.
 * JSON parsing itself is exercised against the live server by the device test.
 */
class BbWireTest {

    @Test
    fun `coreId keeps ids that end in one`() {
        assertEquals("637881", BbWire.coreId("_637881_1"))
        assertEquals("6378811", BbWire.coreId("_6378811_1"))
        assertEquals("1", BbWire.coreId("_1_1"))
    }

    @Test
    fun `bracket leaves an already bracketed id alone`() {
        assertEquals("_637881_1", BbWire.bracket("_637881_1"))
        assertEquals("_637881_1", BbWire.bracket("637881"))
    }

    @Test
    fun `handlers map to their kinds`() {
        assertEquals(BbItem.Kind.FOLDER, BbWire.kindOf("resource/x-bb-folder"))
        assertEquals(BbItem.Kind.DOCUMENT, BbWire.kindOf("resource/x-bb-document"))
        assertEquals(BbItem.Kind.FILE, BbWire.kindOf("resource/x-bb-file"))
        assertEquals(BbItem.Kind.ASSIGNMENT, BbWire.kindOf("resource/x-bb-assignment"))
        assertEquals(BbItem.Kind.LINK, BbWire.kindOf("resource/x-bb-link"))
        assertEquals(BbItem.Kind.VIDEO, BbWire.kindOf("resource/x-bb-video"))
        assertEquals(BbItem.Kind.UNKNOWN, BbWire.kindOf("resource/x-bb-something-new"))
        assertEquals(BbItem.Kind.UNKNOWN, BbWire.kindOf(""))
    }

    @Test
    fun `body html flattens to one line`() {
        val html = "<div><p>Read the <b>manual</b> &amp; bring it.</p><script>x()</script></div>"
        assertEquals("Read the manual & bring it.", BbWire.bodyText(html))
    }

    @Test
    fun `mime comes from the name first and the bytes second`() {
        val pdf = "%PDF-1.6".toByteArray()
        assertEquals("application/pdf", BbFiles.mimeOf("manual.pdf", ByteArray(0)))
        assertEquals("application/pdf", BbFiles.mimeOf("xid-12345", pdf))
        assertFalse(BbFiles.mimeOf("xid-12345", byteArrayOf(1, 2, 3)) == "application/pdf")
    }

    @Test
    fun `an extensionless name gains one from its bytes`() {
        val pdf = "%PDF-1.6".toByteArray()
        assertEquals("xid-12345.pdf", BbFiles.withExtension("xid-12345", BbFiles.mimeOf("xid-12345", pdf)))
        assertEquals("manual.pdf", BbFiles.withExtension("manual.pdf", "application/pdf"))
    }
}
