package edu.sustech.mobile.ui.room

import android.view.View
import android.widget.TextView
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.library.room.RoomCategory
import edu.sustech.mobile.ui.ListFragment

/**
 * The IC library's discussion rooms, right now.
 *
 * Read-only on purpose: creating or cancelling a reservation changes a real room
 * calendar, so those stay on the official page. This screen answers the question
 * people actually ask in the corridor — is anything free.
 *
 * Needs the campus network: off campus the service refuses before any sign-in, and
 * that is reported as a location problem rather than a login one.
 */
class RoomFragment : ListFragment<RoomCategory>(R.layout.fragment_rooms) {

    private var note: TextView? = null

    /** How many reservations this account is holding, shown with the occupancy. */
    private var mine = 0

    override fun rowLayout() = R.layout.item_room_category

    override fun cachePrefix() = "rooms.idle."

    override fun emptyText() = getString(R.string.rooms_empty)

    override fun onReady(view: View) {
        note = view.findViewById(R.id.rooms_note)
        note?.text = getString(R.string.rooms_loading)
    }

    override suspend fun fetch(): List<RoomCategory> {
        val categories = App.rooms.categories()
        mine = App.rooms.count()
        return categories
    }

    override fun onLoaded(rows: List<RoomCategory>) {
        val free = rows.sumOf { it.idle }
        val total = rows.sumOf { it.total }
        note?.text = getString(R.string.rooms_note, free, total, mine)
    }

    override fun errorText(error: Throwable): String =
        context?.let { error.friendly(it) } ?: error.message.orEmpty()

    override fun bindRow(view: View, item: RoomCategory, position: Int) {
        view.findViewById<TextView>(R.id.room_category_name).text = item.name
        val count = view.findViewById<TextView>(R.id.room_category_count)
        count.text = getString(R.string.rooms_free, item.idle, item.total)
        // Nothing free in this category: it stays listed, because "all taken" is
        // the answer, not a reason to hide the row.
    }
}
