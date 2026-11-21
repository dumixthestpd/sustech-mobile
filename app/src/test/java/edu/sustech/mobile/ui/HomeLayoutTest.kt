package edu.sustech.mobile.ui

import org.junit.Assert.*
import org.junit.Test

class HomeLayoutTest {
    @Test fun `old or edited preferences cannot expose removed services or duplicate cards`() {
        val value = HomeLayout.normalize(HomeLayout(hero = "removed", order = listOf("bus", "bus", "removed"),
            visible = setOf("bus", "removed"), services = listOf("removed", "tis", "tis"),
            landing = "removed", schedule = "removed"), listOf("tis", "pms"))
        assertEquals(listOf("bus"), value.displayed())
        assertEquals(HomeLayout.MODULES.toSet(), value.order.toSet())
        assertEquals(HomeLayout.MODULES.size, value.order.size)
        assertEquals(listOf("tis"), value.services)
        assertEquals("home", value.landing)
        assertEquals("day", value.schedule)
    }

    @Test fun `hidden hero and deliberately empty choices do not reappear`() {
        val value = HomeLayout.normalize(HomeLayout(hero = "ddl", visible = emptySet(), services = emptyList()), emptyList())
        assertEquals("", value.hero)
        assertTrue(value.displayed().isEmpty())
        assertTrue(value.services.isEmpty())
    }

    @Test fun `hero is shown once and remaining cards retain the chosen order`() {
        val value = HomeLayout(hero = "schedule", order = listOf("weather", "schedule", "bus", "ddl"),
            visible = setOf("weather", "bus", "schedule"))
        assertEquals(listOf("schedule", "weather", "bus"), value.displayed())
    }
}
