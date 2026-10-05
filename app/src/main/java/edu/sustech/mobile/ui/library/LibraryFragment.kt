package edu.sustech.mobile.ui.library

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.library.LibraryApi
import edu.sustech.mobile.library.LoansApi
import edu.sustech.mobile.ui.ListFragment
import edu.sustech.mobile.ui.ServicePage
import edu.sustech.mobile.ui.TabbedServiceFragment
import edu.sustech.mobile.ui.hostShell
import java.util.Locale

/** The book whose "Where" tab is open, shared between the two fragments. */
object LibrarySelection {
    @Volatile var book: LibraryApi.Book? = null
}

/** The loan a renew action is for, shared between the list and the dialog. */
object LoanSelection {
    @Volatile var lastLoan: LoansApi.Loan? = null
}

private fun Fragment.libraryShell(): LibraryFragment? = hostShell() as? LibraryFragment

/**
 * The library, as three questions and nothing else:
 *
 * 1. **Inside** — how many people are in the building, and per building.
 * 2. **Books** — search the catalogue.
 * 3. Tap a hit → **Where**: every copy, its building, its collection, its call
 *    number and whether it is on the shelf.
 *
 * Nothing here needs an account, so the screens work on the way to the library
 * rather than after getting there.
 */
class LibraryFragment : TabbedServiceFragment(R.layout.fragment_tabs) {

    override fun pages(): List<ServicePage> = listOf(
        ServicePage(R.string.library_loans) { LibraryLoansFragment() },
        ServicePage(R.string.library_inside) { LibraryInsideFragment() },
        ServicePage(R.string.library_books) { LibraryBooksFragment() },
        ServicePage(R.string.library_where) { LibraryWhereFragment() },
    )

    override fun onServiceViewReady(view: View) {
        banner(getString(R.string.library_credit), null)
    }

    /** Called by a search hit: show that record's holdings. */
    fun open(book: LibraryApi.Book) {
        LibrarySelection.book = book
        selectTab(WHERE)
    }

    /** The Inside tab's summary line: the headcount is the headline. */
    fun showInside(inside: LibraryApi.Inside) {
        val parts = ArrayList<String>()
        if (inside.total > 0) parts.add(getString(R.string.library_people_total, inside.total))
        inside.hours.takeIf { it.isNotEmpty() }?.let { parts.add(getString(R.string.library_hours, it)) }
        parts.add(getString(R.string.library_credit))
        banner(parts.joinToString(" · "), null)
    }

    /** The Where tab's summary line: which record is on screen. */
    fun showRecord(record: LibraryApi.Record) {
        val parts = ArrayList<String>()
        if (record.title.isNotEmpty()) parts.add(record.title)
        if (record.year.isNotEmpty()) parts.add(record.year)
        parts.add(getString(R.string.library_credit))
        banner(parts.joinToString(" · "), null)
    }

    private companion object {
        const val WHERE = 2
    }
}

/** How full it is: one row per building, the total in the banner. */
class LibraryInsideFragment : ListFragment<LibraryApi.Place>(R.layout.fragment_list) {

    override fun cachePrefix() = "library.inside"

    override fun rowLayout() = R.layout.item_library_people

    override fun emptyText() = getString(R.string.library_no_people)

    override suspend fun fetch(): List<LibraryApi.Place> = App.library.inside().places

    override fun onLoaded(rows: List<LibraryApi.Place>) {
        // The rows are the split; the total is the number people actually
        // want, so it goes where the eye already is.
        libraryShell()?.showInside(App.library.inside())
    }

    override fun bindRow(view: View, item: LibraryApi.Place, position: Int) {
        view.findViewById<TextView>(R.id.library_place_name).text = item.name
        view.findViewById<TextView>(R.id.library_place_count).text = item.people.toString()
    }
}

/**
 * The catalogue, with its own search field.
 *
 * Search is explicit — the field's IME action or a pull — because the API is
 * somebody else's and a keystroke-per-request would hammer it.
 */
class LibraryBooksFragment : ListFragment<LibraryApi.Book>(R.layout.fragment_library_books) {

    override fun cachePrefix() = "library.search."

    override fun rowLayout() = R.layout.item_library_book

    override fun emptyText() = getString(R.string.library_no_results)

    override fun onReady(view: View) {
        val field = view.findViewById<EditText>(R.id.library_search)
        // ENTER on a single-line field arrives as IME_NULL, the keyboard's own
        // search key as IME_ACTION_SEARCH — both mean "search", so both do.
        field.setOnEditorActionListener { _, action, _ ->
            val searching = action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_NULL
            if (searching) load(force = true)
            searching
        }
    }

    override suspend fun fetch(): List<LibraryApi.Book> {
        val query = view?.findViewById<EditText>(R.id.library_search)?.text?.toString().orEmpty()
        return App.library.search(query)
    }

    override fun onLoaded(rows: List<LibraryApi.Book>) {
        val query = view?.findViewById<EditText>(R.id.library_search)?.text?.toString().orEmpty()
        showEmpty(rows.isEmpty() && query.isBlank(), getString(R.string.library_search_first))
    }

    override fun bindRow(view: View, item: LibraryApi.Book, position: Int) {
        view.findViewById<TextView>(R.id.library_book_title).text = item.title
        view.findViewById<TextView>(R.id.library_book_meta).text =
            listOf(item.creator, item.year, item.kind).filter { it.isNotEmpty() }.joinToString(" · ")
        // Opens the copy list for this record — the "where" half of the answer.
        view.setOnClickListener { libraryShell()?.open(item) }
    }
}

/** Where the chosen book is: one row per copy. */
class LibraryWhereFragment : ListFragment<LibraryApi.Shelf>(R.layout.fragment_list) {

    override fun cachePrefix() = "library.record."

    override fun rowLayout() = R.layout.item_library_shelf

    override fun emptyText() = getString(R.string.library_electronic)

    override suspend fun fetch(): List<LibraryApi.Shelf> {
        val book = LibrarySelection.book ?: return emptyList()
        val record = App.library.record(book)
        onFetched = record
        return record.shelves
    }

    @Volatile
    private var onFetched: LibraryApi.Record? = null

    override fun onLoaded(rows: List<LibraryApi.Shelf>) {
        onFetched?.let { libraryShell()?.showRecord(it) }
    }

    override fun bindRow(view: View, item: LibraryApi.Shelf, position: Int) {
        val place = listOf(item.library, item.collection).filter { it.isNotEmpty() }.joinToString(" · ")
        view.findViewById<TextView>(R.id.library_shelf_place).text = place
        view.findViewById<TextView>(R.id.library_shelf_status).text = when {
            item.onShelf -> getString(R.string.library_on_shelf)
            item.status.isBlank() -> getString(R.string.library_status_unknown)
            item.status.trim().lowercase(Locale.ROOT) in UNAVAILABLE_STATUSES ->
                getString(R.string.library_not_available)
            else -> getString(R.string.library_status_unknown)
        }
        view.findViewById<TextView>(R.id.library_shelf_call).text =
            if (item.callNumber.isEmpty()) "" else getString(R.string.library_call_number, item.callNumber)
    }

    override fun errorText(error: Throwable): String = context?.let { error.friendly(it) }
        ?: error.message.orEmpty()

    private companion object {
        val UNAVAILABLE_STATUSES = setOf(
            "unavailable",
            "not available",
            "not_available",
            "checked out",
            "loaned",
        )
    }
}

/**
 * What you have borrowed, and when each book is due — the account page's
 * loans tab, reduced to the two things a phone is asked on the way out the
 * door: the title and the date.
 *
 * Tap a book to see the renewal preview; tap again (preview shows the exact
 * request) — no, keep it honest and two-step: first tap shows the preview
 * dialog, the dialog's positive button sends it.
 */
class LibraryLoansFragment : ListFragment<LoansApi.Loan>(R.layout.fragment_list) {

    override fun cachePrefix() = "library.loans"

    override fun rowLayout() = R.layout.item_library_loan

    override fun emptyText() = getString(R.string.library_no_loans)

    override suspend fun fetch(): List<LoansApi.Loan> = App.loans.loans("active")

    override fun errorText(error: Throwable): String {
        if (error.message?.contains("No school account", ignoreCase = true) == true) {
            return getString(R.string.library_signin_needed)
        }
        return context?.let { error.friendly(it) } ?: error.message.orEmpty()
    }

    override fun bindRow(view: View, item: LoansApi.Loan, position: Int) {
        view.findViewById<TextView>(R.id.loan_title).text =
            listOf(item.title, item.author)
                .filter { it.isNotEmpty() }
                .joinToString(" · ")
                .let { if (item.year.isNotEmpty()) "$it · ${item.year}" else it }
        view.findViewById<TextView>(R.id.loan_date_label).text =
            getString(R.string.library_due, item.dueDate.ifEmpty { item.returnDate })
        view.findViewById<TextView>(R.id.loan_renewed).text =
            getString(R.string.library_renewed_flag, item.renewed.ifEmpty { "-" })
        view.findViewById<TextView>(R.id.loan_place).text =
            listOf(item.location, item.subLocation, item.callNumber)
                .filter { it.isNotEmpty() }
                .joinToString(" · ")

        view.setOnClickListener { confirmRenew(item) }
    }

    /**
     * Two-step renew: the first look shows exactly what would be sent
     * (nothing has gone out yet); confirming in the dialog commits it.
     */
    private fun confirmRenew(loan: LoansApi.Loan) {
        val activity = activity ?: return
        val message = getString(
            R.string.library_renew_preview,
            "$PRIMO_ACCOUNT/renew_loan",
            loan.title,
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.library_renew)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.library_renew) { _, _ ->
                runIo(
                    block = { App.loans.renew(loan.loanId, commit = true) },
                    onOk = { _ ->
                        if (!isAdded) return@runIo
                        App.toast(R.string.library_renew_done)
                        load(force = true)
                    },
                    onErr = { error ->
                        if (!isAdded) return@runIo
                        App.toast(getString(R.string.library_renew_failed, error.message.orEmpty()))
                    },
                )
            }
            .show()
    }

    private companion object {
        const val PRIMO_ACCOUNT =
            "https://sustc.primo.exlibrisgroup.com.cn/primaws/rest/priv/myaccount"
    }
}
