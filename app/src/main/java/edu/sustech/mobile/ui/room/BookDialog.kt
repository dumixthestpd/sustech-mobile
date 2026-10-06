package edu.sustech.mobile.ui.room

import android.app.Dialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import edu.sustech.mobile.R
import edu.sustech.mobile.library.room.RoomApi
import edu.sustech.mobile.library.room.RoomInfo
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Books one room: pick the day, the start time and the length, name the co-applicants
 * if the room needs them, then confirm the exact slot. The confirmation is not a
 * formality — the room is taken the moment this is sent, and the library's rules (two
 * days ahead, two hours a session, a 3+ person room needs the booker plus two
 * co-applicants, cancel at least ten minutes before the start) are the service's, not
 * this screen's.
 *
 * 🔴 A room whose *minimum* capacity is 3+ is refused by the service unless the
 * booking carries co-applicants, so this asks for their student ids and the screen
 * resolves them (and fails closed if one cannot be found). Rooms named `（1-3人）` need
 * none.
 *
 * 🔴 The start is a plain time picker, not a list built from the room's `openTimes`:
 * the service returns `07:00–07:00` with `openLimit: 0` for every room measured, so
 * those values carry no window and a list built from them would be an invention. The
 * service is the authority on what is bookable and its refusal is shown as-is.
 */
class BookDialog : DialogFragment() {

    /** Whoever opened the sheet does the sending, so it runs on a live lifecycle. */
    interface Listener {
        fun onBook(
            room: RoomInfo,
            begin: Date,
            end: Date,
            title: String,
            studentIds: List<String>,
        )
    }

    private lateinit var startButton: TextView
    private var startHour = 0
    private var startMinute = 0

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val room = RoomInfo(
            devId = requireArguments().getInt(ARG_DEV),
            name = requireArguments().getString(ARG_NAME).orEmpty(),
            lab = requireArguments().getString(ARG_LAB).orEmpty(),
            people = requireArguments().getString(ARG_PEOPLE).orEmpty(),
            needsMembers = requireArguments().getBoolean(ARG_NEEDS),
            minMinutes = requireArguments().getInt(ARG_MIN),
            free = true,
        )
        val view = layoutInflater.inflate(R.layout.dialog_book, null)
        view.findViewById<TextView>(R.id.book_room).text = listOf(
            room.name,
            room.lab,
            room.people,
        ).filter { it.isNotBlank() }.joinToString(" · ")
        view.findViewById<TextView>(R.id.book_policy).text = getString(R.string.rooms_policy)

        val days = RoomApi.bookableDays()
        val daySpinner = view.findViewById<Spinner>(R.id.book_day)
        daySpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            days.map { DAY.format(it) },
        )

        // Default to the next half hour from now.
        val now = Calendar.getInstance()
        startHour = now.get(Calendar.HOUR_OF_DAY)
        startMinute = if (now.get(Calendar.MINUTE) < 30) 30 else 0
        if (startMinute == 0) startHour = (startHour + 1) % 24
        startButton = view.findViewById(R.id.book_start)
        showStart()
        startButton.setOnClickListener {
            TimePickerDialog(
                requireContext(),
                { _, hour, minute -> startHour = hour; startMinute = minute; showStart() },
                startHour,
                startMinute,
                true,
            ).show()
        }

        val lengths = LENGTHS.filter { it <= RoomApi.MAX_MINUTES }
        val lengthSpinner = view.findViewById<Spinner>(R.id.book_length)
        lengthSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            lengths.map { getString(R.string.rooms_minutes, it) },
        )

        val topic = view.findViewById<EditText>(R.id.book_topic)
        topic.setText(RoomApi.DEFAULT_TITLE)

        // A 3+ person room will be refused without co-applicants, so ask for them here.
        val memberLabel = view.findViewById<TextView>(R.id.book_members_label)
        val members = view.findViewById<EditText>(R.id.book_members)
        if (room.needsMembers) {
            memberLabel.visibility = View.VISIBLE
            members.visibility = View.VISIBLE
            memberLabel.text = getString(
                R.string.rooms_members_label,
                RoomApi.MIN_CO_APPLICANTS,
            )
        }

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.rooms_book_title)
            .setView(view)
            .setPositiveButton(R.string.rooms_book, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val day = days[daySpinner.selectedItemPosition]
                        val begin = at(day, startHour, startMinute)
                        val end = Date(
                            begin.time + lengths[lengthSpinner.selectedItemPosition] * 60_000L,
                        )
                        confirm(
                            room = room,
                            begin = begin,
                            end = end,
                            title = topic.text.toString().trim(),
                            studentIds = parseIds(members.text.toString()),
                        )
                    }
                }
            }
    }

    /** Student ids as typed: separated by commas, spaces or newlines. */
    private fun parseIds(text: String): List<String> = text
        .split(',', '，', '、', ' ', '\n', '\t')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    private fun showStart() {
        startButton.text = String.format(Locale.US, "%02d:%02d", startHour, startMinute)
    }

    /** States exactly what will be sent before it is sent. */
    private fun confirm(
        room: RoomInfo,
        begin: Date,
        end: Date,
        title: String,
        studentIds: List<String>,
    ) {
        val lines = mutableListOf(
            room.name,
            "${DAY.format(begin)} ${SLOT.format(begin)}–${SLOT.format(end)}",
        )
        if (studentIds.isNotEmpty()) {
            lines += getString(R.string.rooms_members_confirm, studentIds.joinToString("、"))
        }
        lines += getString(R.string.rooms_book_confirm_warning)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rooms_book_confirm_title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.rooms_book) { _, _ ->
                (parentFragment as? Listener)?.onBook(room, begin, end, title, studentIds)
                dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** [day] with the chosen hour and minute. */
    private fun at(day: Date, hour: Int, minute: Int): Date {
        val calendar = Calendar.getInstance()
        calendar.time = day
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.time
    }

    companion object {
        private const val ARG_DEV = "devId"
        private const val ARG_NAME = "name"
        private const val ARG_LAB = "lab"
        private const val ARG_PEOPLE = "people"
        private const val ARG_NEEDS = "needsMembers"
        private const val ARG_MIN = "minMinutes"

        private val LENGTHS = listOf(30, 60, 90, 120)

        private val DAY = SimpleDateFormat("yyyy-MM-dd EEE", Locale.US)
        private val SLOT = SimpleDateFormat("HH:mm", Locale.US)

        fun newInstance(room: RoomInfo): BookDialog = BookDialog().apply {
            arguments = Bundle().apply {
                putInt(ARG_DEV, room.devId)
                putString(ARG_NAME, room.name)
                putString(ARG_LAB, room.lab)
                putString(ARG_PEOPLE, room.people)
                putBoolean(ARG_NEEDS, room.needsMembers)
                putInt(ARG_MIN, room.minMinutes)
            }
        }
    }
}
