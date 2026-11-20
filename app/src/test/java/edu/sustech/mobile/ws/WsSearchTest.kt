package edu.sustech.mobile.ws

import org.junit.Assert.*
import org.junit.Test

class WsSearchTest {
    private fun project(name: String, year: String, region: String, eligible: Boolean) =
        WsProgram(name, name, region, "Oxford", "Short", "", "", eligible, "", "", year)

    @Test fun `keywords and facets combine across the complete loaded catalog`() {
        val rows = listOf(project("Other", "2026", "US", true), project("Oxford research", "2027", "UK", true), project("Oxford summer", "2027", "UK", false))
        assertEquals(listOf(rows[1]), WsSearch.filter(rows, "ＯＸＦＯＲＤ research", "2027", "UK", "Short", true))
        assertEquals(emptyList<WsProgram>(), WsSearch.filter(rows, "research", "2026"))
        assertEquals(listOf(rows[1]), WsSearch.filter(rows, region = "UK", appliableOnly = true))
        assertEquals(rows, WsSearch.filter(rows))
        val bilingual = rows[1].copy(name = "中文项目名称", school = "中文校名", englishName = "Oxford research", agency = "Global office")
        assertEquals(listOf(bilingual), WsSearch.filter(listOf(bilingual), "oxford global"))
    }
}
