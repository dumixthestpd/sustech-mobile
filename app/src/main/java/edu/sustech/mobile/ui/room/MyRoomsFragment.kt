package edu.sustech.mobile.ui.room

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.library.room.RoomReservation
import edu.sustech.mobile.ui.ListFragment

/**
 * The room reservations this account holds.
 *
 * Cancelling asks first and states which room and which slot — the cancellation is
 * immediate and cannot be undone, and the library's rule is that cancelling later
 * than ten minutes before the start can still count as a no-show.
 */
class MyRoomsFragment : ListFragment<RoomReservation>(R.layout.fragment_rooms) {

    private var note: TextView? = null

    override fun rowLayout() = R.layout.item_room_reservation

    override fun cachePrefix() = "rooms.mine."

    override fun emptyText() = getString(R.string.rooms_mine_empty)

    override fun onReady(view: View) {
        note = view.findViewById(R.id.rooms_note)
    }

    override suspend fun fetch(): List<RoomReservation> = App.rooms.reservations()

    override fun onLoaded(rows: List<RoomReservation>) {
        note?.text = getString(R.string.rooms_mine_note, rows.size)
    }

    override fun bindRow(view: View, item: RoomReservation, position: Int) {
        view.findViewById<TextView>(R.id.room_reservation_title).text = item.room
        view.findViewById<TextView>(R.id.room_reservation_when).text = getString(
            R.string.rooms_reservation_when,
            item.begin,
            item.end,
        )
        view.findViewById<TextView>(R.id.room_reservation_where).text = listOf(
            item.lab,
            item.title,
        ).filter { it.isNotBlank() }.joinToString(" · ")

        val state = view.findViewById<TextView>(R.id.room_reservation_state)
        state.text = stateWord(item)

        view.setOnClickListener { confirmCancel(item) }
    }

    private fun stateWord(item: RoomReservation): String = getString(
        when (item.state) {
            "violated" -> R.string.rooms_state_violated
            "finished" -> R.string.rooms_state_finished
            "started" -> R.string.rooms_state_started
            else -> R.string.rooms_state_upcoming
        },
    )

    private fun confirmCancel(item: RoomReservation) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rooms_cancel_title)
            .setMessage(
                getString(R.string.rooms_cancel_message, item.room, item.begin, item.end),
            )
            .setPositiveButton(R.string.rooms_cancel) { _, _ ->
                if (item.uuid.isBlank()) {
                    showError(IllegalStateException(getString(R.string.rooms_cancel_nokey)))
                    return@setPositiveButton
                }
                viewLifecycleOwner.runIo(
                    block = { App.rooms.cancel(item.uuid) },
                    onOk = {
                        if (!isAdded) return@runIo
                        android.widget.Toast.makeText(
                            requireContext(),
                            R.string.rooms_cancelled,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        load(force = true)
                    },
                    onErr = { error ->
                        if (!isAdded) return@runIo
                        showError(error)
                    },
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
