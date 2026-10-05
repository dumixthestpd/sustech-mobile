package edu.sustech.mobile.service

import edu.sustech.mobile.R
import edu.sustech.mobile.ui.ServicePortalFragment
import edu.sustech.mobile.ui.pms.PmsFragment
import edu.sustech.mobile.ui.tis.TisFragment
import edu.sustech.mobile.ui.nces.NcesFragment
import java.util.Locale

/**
 * The service catalog.
 *
 * Order is the display order in the Services tab: implemented services first,
 * then the roadmap. Official services linked below open in the school portals.
 */
object Services {

    val ecard = ServiceModule(
        id = "ecard",
        title = R.string.service_ecard,
        summary = R.string.service_ecard_summary,
        icon = R.drawable.ic_card,
        navId = R.id.nav_pms,
        available = true,
    ) { edu.sustech.mobile.ui.ecard.EcardFragment() }

    val printing = ServiceModule(
        id = "pms",
        title = R.string.service_pms,
        summary = R.string.service_pms_summary,
        icon = R.drawable.ic_printer,
        navId = R.id.nav_pms,
        available = true,
    ) { PmsFragment() }

    val courses = ServiceModule(
        id = "tis",
        title = R.string.service_tis,
        summary = R.string.service_tis_summary,
        icon = R.drawable.ic_school,
        navId = R.id.nav_tis,
        available = true,
    ) { TisFragment() }

    val blackboard = ServiceModule(
        id = "blackboard",
        title = R.string.service_blackboard,
        summary = R.string.service_blackboard_summary,
        icon = R.drawable.ic_doc,
        navId = R.id.nav_pms,
        available = true,
    ) { edu.sustech.mobile.ui.bb.BbFragment() }

    val library = ServiceModule(
        id = "library",
        title = R.string.service_library,
        summary = R.string.service_library_summary,
        icon = R.drawable.ic_history,
        navId = R.id.nav_pms,
        available = true,
    ) { edu.sustech.mobile.ui.library.LibraryFragment() }

    val booking = ServiceModule(
        id = "booking",
        title = R.string.service_booking,
        summary = R.string.service_booking_summary,
        icon = R.drawable.ic_grid,
        navId = R.id.nav_pms,
        available = true,
    ) { edu.sustech.mobile.ui.booking.BookingFragment() }

    val transit = ServiceModule(
        id = "transit",
        title = R.string.service_transit,
        summary = R.string.service_transit_summary,
        icon = R.drawable.ic_refresh,
        navId = R.id.nav_pms,
        available = true,
    ) { edu.sustech.mobile.ui.transit.TransitFragment() }

    val nces = ServiceModule(
        id = "nces",
        title = R.string.service_nces,
        summary = R.string.service_nces_summary,
        icon = R.drawable.ic_person,
        navId = R.id.nav_pms,
        available = true,
    ) { NcesFragment() }

    val faculty = ServiceModule(
        id = "faculty",
        title = R.string.service_faculty,
        summary = R.string.service_faculty_summary,
        icon = R.drawable.ic_person,
        navId = R.id.nav_pms,
        available = true,
    ) { edu.sustech.mobile.ui.faculty.FacultyDirectoryFragment() }

    val exchange = ServiceModule(
        id = "exchange",
        title = R.string.service_exchange,
        summary = R.string.service_exchange_summary,
        icon = R.drawable.ic_school,
        navId = R.id.nav_pms,
        available = true,
    ) { edu.sustech.mobile.ui.ws.ExchangeFragment() }

    val languageHelp = ServiceModule(
        id = "cle",
        title = R.string.service_cle,
        summary = R.string.service_cle_summary,
        icon = R.drawable.ic_scan,
        navId = R.id.nav_pms,
        available = true,
    ) { edu.sustech.mobile.ui.cle.CleFragment() }

    val wifi = ServiceModule(
        id = "wifi",
        title = R.string.service_wifi,
        summary = R.string.service_wifi_summary,
        icon = R.drawable.ic_refresh,
        navId = R.id.nav_pms,
        available = true,
    ) { ServicePortalFragment.newInstance("wifi") }

    val all: List<ServiceModule> = listOf(
        ecard,
        printing, courses, nces,
        blackboard, library, booking, transit, faculty, exchange,
        languageHelp, wifi,
    )

    val available: List<ServiceModule> = all.filter { it.available }

    fun byId(id: String?): ServiceModule? =
        all.firstOrNull { it.id.equals(id.orEmpty(), ignoreCase = true) }

    fun byTitle(text: String): ServiceModule? {
        val needle = text.lowercase(Locale.US)
        return all.firstOrNull { needle.contains(it.id.lowercase(Locale.US)) }
    }
}
