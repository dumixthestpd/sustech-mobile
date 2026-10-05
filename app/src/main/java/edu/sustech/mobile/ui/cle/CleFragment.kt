package edu.sustech.mobile.ui.cle

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import edu.sustech.mobile.R
import edu.sustech.mobile.cle.CleApi
import edu.sustech.mobile.cle.CleReservation
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.WebCookies
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.ui.ListFragment

/**
 * Language-centre tutoring (CLE): what this account has booked, and how much of
 * the per-semester allowance is left.
 *
 * 🔴 This is the one E-Hall app whose data endpoints refuse a CAS ticket alone
 * (403). Its JavaScript adapter has to run first, so the screen drives a
 * one-pixel WebView through the app index, then hands that page's cookies to the
 * app's HTTP jar — after which the JSON queries answer. The WebView is invisible
 * and exists only for that.
 *
 * Nothing is booked from here: a reservation posts the model's entire control set
 * and consumes one of three per-semester slots, so it stays on the official page.
 */
class CleFragment : ListFragment<CleReservation>(R.layout.fragment_cle) {

    private var bootstrap: WebView? = null
    private var note: TextView? = null
    private var allowed = 0

    override fun rowLayout() = R.layout.item_cle_reservation

    override fun cachePrefix() = "cle.mine."

    override fun emptyText() = getString(R.string.cle_no_reservations)

    override fun onReady(view: View) {
        note = view.findViewById(R.id.cle_note)
        note?.text = getString(R.string.cle_bootstrapping)
        startBootstrap(view)
    }

    override suspend fun fetch(): List<CleReservation> {
        val reservations = App.cle.reservations()
        allowed = App.cle.allowedPerSemester()
        return reservations
    }

    override fun onLoaded(rows: List<CleReservation>) {
        note?.text = if (allowed > 0) {
            getString(R.string.cle_quota, rows.size, allowed)
        } else {
            getString(R.string.cle_credit)
        }
    }

    override fun errorText(error: Throwable): String =
        context?.let { error.friendly(it) } ?: error.message.orEmpty()

    override fun bindRow(view: View, item: CleReservation, position: Int) {
        view.findViewById<TextView>(R.id.cle_reservation_title).text = listOf(
            item.serviceType,
            item.teacher,
        ).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { item.id }

        val where = listOf(item.date, item.time, item.room)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        val whereView = view.findViewById<TextView>(R.id.cle_reservation_where)
        whereView.text = where
        whereView.visibility = if (where.isBlank()) View.GONE else View.VISIBLE

        val topic = view.findViewById<TextView>(R.id.cle_reservation_status)
        val status = listOf(item.status, item.topic).filter { it.isNotBlank() }.joinToString(" · ")
        topic.text = status
        topic.visibility = if (status.isBlank()) View.GONE else View.VISIBLE
    }

    /**
     * Runs the E-Hall page's JavaScript so the session becomes usable, then loads
     * the list. The WebView is 1×1 and invisible — it is plumbing, not UI.
     */
    private fun startBootstrap(view: View) {
        val web = WebView(requireContext())
        bootstrap = web
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.visibility = View.INVISIBLE
        (view as? ViewGroup)?.addView(web, ViewGroup.LayoutParams(1, 1))
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(loaded: WebView, url: String) {
                // The adapter has now run: its cookies are what the JSON wants.
                WebCookies.capture(EHALL_HOST)
                App.cle.markBootstrapped()
                load(force = true)
            }
        }
        viewLifecycleOwner.runIo(
            block = { App.cle.ensureSession() },
            onOk = {
                if (!isAdded) return@runIo
                WebCookies.install(web, CleApi.APP_INDEX) { web.loadUrl(CleApi.APP_INDEX) }
            },
            onErr = { error ->
                if (!isAdded) return@runIo
                note?.text = error.friendly(requireContext())
                showEmpty(true, error.friendly(requireContext()))
            },
        )
    }

    override fun onDestroyView() {
        bootstrap?.let { web ->
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        bootstrap = null
        note = null
        super.onDestroyView()
    }

    private companion object {
        const val EHALL_HOST = "ehall.sustech.edu.cn"
    }
}
