package edu.sustech.mobile.service

import edu.sustech.mobile.widget.QuickShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The module ids are not private detail — they are the app's deep-link interface:
 * `adb shell am start -n edu.sustech.mobile/.ui.ServiceActivity --es service <id>`, the
 * widget shortcuts, and anything that looks a module up by name all speak them.
 *
 * These tests exist because an id drifted: the exchange module was declared `id = "ws"`
 * (with its string resources still named `service_ws`) while everything else called it
 * "exchange". Tapping the tile worked, so nothing looked wrong — but
 * `byId("exchange")` returned null and `ServiceActivity` silently `finish()`es on an
 * unknown id, so the deep link opened nothing at all and the widget's Exchange shortcut
 * pointed at a name no module had.
 */
class ServicesTest {

    /** The deep-link ids, in catalog order. Changing one is an interface change. */
    private val expectedIds = listOf(
        "ecard", "pms", "tis", "nces", "blackboard", "library", "rooms",
        "booking", "transit", "faculty", "exchange", "cle", "wifi",
    )

    @Test
    fun `module ids are exactly the documented interface`() {
        assertEquals(expectedIds, Services.all.map { it.id })
    }

    @Test
    fun `every module id is unique`() {
        val ids = Services.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `byId round-trips every module and rejects junk`() {
        Services.all.forEach { module ->
            assertEquals(module, Services.byId(module.id))
        }
        assertNull(Services.byId("nope"))
        assertNull(Services.byId(null))
        // `ws` was never a real id; keep it from creeping back in as an alias.
        assertNull(Services.byId("ws"))
    }

    @Test
    fun `every widget shortcut points at a module that exists`() {
        QuickShortcut.entries.forEach { shortcut ->
            if (shortcut.serviceId != null) {
                val module = Services.byId(shortcut.serviceId)
                assertNotNull(
                    "widget shortcut '${shortcut.key}' targets unknown id '${shortcut.serviceId}'",
                    module,
                )
            }
        }
    }

    @Test
    fun `widget shortcuts open exactly one destination`() {
        QuickShortcut.entries.forEach { shortcut ->
            val destinations = listOfNotNull(shortcut.serviceId, shortcut.portalUrl)
            assertTrue(
                "widget shortcut '${shortcut.key}' must have one destination, has ${destinations.size}",
                destinations.size == 1,
            )
        }
    }
}
