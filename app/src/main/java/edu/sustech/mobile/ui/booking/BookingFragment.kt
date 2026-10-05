package edu.sustech.mobile.ui.booking

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import edu.sustech.mobile.R
import edu.sustech.mobile.booking.BookingMeeting
import edu.sustech.mobile.booking.BookingRoom
import edu.sustech.mobile.core.App
import edu.sustech.mobile.ui.ListFragment
import edu.sustech.mobile.ui.ServicePage
import edu.sustech.mobile.ui.TabbedServiceFragment

/**
 * Venue booking, read natively.
 *
 * Two questions: which rooms can I book, and what have I already booked. Both
 * are plain JSON reads behind a CAS ticket → bearer-token handshake, so neither
 * needs the browser page the app used to open. Booking and cancelling a room
 * change a real calendar and stay on the official page until they can be
 * previewed and read back here.
 */
class BookingFragment : TabbedServiceFragment(R.layout.fragment_tabs) {

    override fun pages(): List<ServicePage> = listOf(
        ServicePage(R.string.booking_rooms) { BookingRoomsFragment() },
        ServicePage(R.string.booking_mine) { BookingMeetingsFragment() },
    )

    override fun onServiceViewReady(view: View) {
        banner(getString(R.string.booking_campus_only), null)
    }
}

/** Every bookable venue, searchable by name, type or building. */
class BookingRoomsFragment : ListFragment<BookingRoom>(R.layout.fragment_booking_rooms) {

    @Volatile
    private var query = ""

    override fun rowLayout() = R.layout.item_booking_room

    override fun cachePrefix() = "booking.rooms."

    override fun emptyText(): String = if (query.isBlank()) {
        getString(R.string.booking_no_rooms)
    } else {
        getString(R.string.booking_no_rooms_match, query)
    }

    override fun onReady(view: View) {
        val search = view.findViewById<EditText>(R.id.booking_search)
        query = search.text.toString()
        search.setOnEditorActionListener { field, action, _ ->
            val isSearch = action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_NULL
            if (isSearch) {
                query = field.text.toString()
                load(force = true)
            }
            isSearch
        }
    }

    override suspend fun fetch(): List<BookingRoom> = App.booking.rooms(keyword = query)

    override fun bindRow(view: View, item: BookingRoom, position: Int) {
        view.findViewById<TextView>(R.id.booking_room_name).text = item.name.ifBlank { item.id }

        view.findViewById<TextView>(R.id.booking_room_where).text = listOf(
            item.type,
            item.location,
            if (item.capacity > 0) getString(R.string.booking_capacity, item.capacity) else "",
        ).filter { it.isNotBlank() }.joinToString(" · ")

        val note = view.findViewById<TextView>(R.id.booking_room_note)
        note.text = when {
            item.approvalRequired -> getString(R.string.booking_needs_approval)
            item.daysAhead > 0 -> getString(R.string.booking_bookable_days, item.daysAhead)
            else -> ""
        }
        note.visibility = if (note.text.isBlank()) View.GONE else View.VISIBLE
    }
}

/** The reservations this account holds. */
class BookingMeetingsFragment : ListFragment<BookingMeeting>(R.layout.fragment_booking_meetings) {

    override fun rowLayout() = R.layout.item_booking_meeting

    override fun cachePrefix() = "booking.meetings."

    override fun emptyText() = getString(R.string.booking_no_meetings)

    override suspend fun fetch(): List<BookingMeeting> = App.booking.meetings()

    override fun bindRow(view: View, item: BookingMeeting, position: Int) {
        view.findViewById<TextView>(R.id.booking_meeting_title).text =
            item.title.ifBlank { item.roomName.ifBlank { item.id } }
        view.findViewById<TextView>(R.id.booking_meeting_where).text = item.roomName

        val hours = listOf(item.startAt, item.endAt).filter { it.isNotBlank() }.joinToString(" → ")
        val whenLine = listOf(hours, item.status.takeIf { it.isNotBlank() && !it.all(Char::isDigit) })
            .filterNotNull()
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        val line = view.findViewById<TextView>(R.id.booking_meeting_when)
        line.text = whenLine
        line.visibility = if (whenLine.isBlank()) View.GONE else View.VISIBLE
    }
}
