package edu.sustech.mobile.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WS text handling, with the exact shapes the live service returns.
 *
 * The menu lists several modules and each carries its own `ts`/`userToken`, so
 * taking the first match would use another module's token — which the service
 * answers with an HTML error page rather than JSON.
 */
class WsTextTest {

    private val menuWithOtherModulesFirst = """
        [{"FunctionList":[{"Pages":[{"PageUrl":"../QuestionAnswerManager/QuestionAnswerList.do?ts=1&userToken=DEADBEEF"}]}]},
         {"FunctionList":[{"Pages":[{"PageUrl":"../StudentExchange_2247/ProjectList2247.do?ts=869&userToken=37E039863438387B4FCD50325E3AC5D8"}]}]}]
    """.trimIndent()

    private val menuWithoutExchange = """
        [{"FunctionList":[{"Pages":[{"PageUrl":"../StudentAbroad/MyDetails.do"}]}]}]
    """.trimIndent()

    @Test
    fun `takes the exchange module's token, not the first one in the menu`() {
        val token = WsText.sessionToken(menuWithOtherModulesFirst)
        assertEquals("37E039863438387B4FCD50325E3AC5D8" to "869", token)
    }

    @Test
    fun `no token anywhere means no session`() {
        assertNull(WsText.sessionToken(menuWithoutExchange))
        assertNull(WsText.sessionToken("[]"))
    }

    @Test
    fun `decodes the numeric entities the listing embeds in names`() {
        assertEquals("自主申请", WsText.decode("&#33258;&#20027;&#30003;&#35831;"))
        assertEquals("墨西哥", WsText.decode("&#22696;&#35199;&#21733;"))
        assertEquals("美国", WsText.decode("&#32654;&#22269;"))
    }

    @Test
    fun `decodes named entities, hex entities and trims`() {
        assertEquals("A & B", WsText.decode("  A &amp; B  "))
        assertEquals("café <x>", WsText.decode("caf&#xE9; &lt;x&gt;"))
        assertEquals("", WsText.decode(""))
    }

    @Test
    fun `plain text passes through unchanged`() {
        assertEquals("Exchange 2026", WsText.decode("Exchange 2026"))
    }
}
