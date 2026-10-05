package edu.sustech.mobile.ui.cle

import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Language-centre tutoring (CLE): what this account has booked, and how much of
 * the per-semester allowance is left.
 *
 * 🔴 This is the one E-Hall app whose data endpoints refuse a CAS ticket alone
 * (403), and whose session does not survive being copied into this app's HTTP
 * client — the same query replayed from the jar is refused even with the page's
 * cookies in it. So the queries run **inside the bootstrapped page** over a
 * JavaScript bridge, which is what the reference implementation does by driving
 * a browser.
 *
 * The WebView is 1×1 and invisible: it is a session, not a screen. Nothing is
 * booked from here — a reservation posts the model's entire control set and
 * consumes one of three per-semester slots, so it stays on the official page.
 */
class CleFragment : ListFragment<CleReservation>(R.layout.fragment_cle) {

    private var bootstrap: WebView? = null
    private var note: TextView? = null
    private var allowed = 0

    /** One query at a time: answers come back through a single bridge. */
    private val queries = Mutex()

    /** The request waiting for an answer, and its tag. */
    @Volatile
    private var pending: CompletableDeferred<String>? = null

    @Volatile
    private var pendingId = 0

    /** The page announces itself more than once (it re-routes); bootstrap once. */
    @Volatile
    private var settled = false

    private val bridge = object {
        @JavascriptInterface
        fun onResult(id: String, payload: String) {
            val waiting = pending ?: return
            // A late answer from an earlier request must not satisfy this one.
            if (id != pendingId.toString()) return
            if (waiting.isActive) waiting.complete(payload)
        }
    }

    override fun rowLayout() = R.layout.item_cle_reservation

    override fun cachePrefix() = "cle.mine."

    override fun onReady(view: View) {
        note = view.findViewById(R.id.cle_note)
        note?.text = getString(R.string.cle_bootstrapping)
        startBootstrap(view)
    }

    override suspend fun fetch(): List<CleReservation> {
        // The screen's first load runs before the WebView has made the session
        // usable; the bootstrap calls load(force = true) when it is done.
        if (!App.cle.bootstrapped) return emptyList()
        val reservations = App.cle.reservations(query(App.cle.modelPath(CleApi.MODEL_RESERVATION)))
        allowed = App.cle.allowedPerSemester(
            query(App.cle.modelPath(CleApi.MODEL_CONFIG, "SFZZSY" to "1")),
        )
        return reservations
    }

    override fun emptyText(): String = if (App.cle.bootstrapped) {
        getString(R.string.cle_no_reservations)
    } else {
        getString(R.string.cle_bootstrapping)
    }

    override fun onLoaded(rows: List<CleReservation>) {
        // Loading before the bootstrap is a no-op, not a result — don't overwrite
        // the "preparing the session" line with a summary of nothing.
        if (!App.cle.bootstrapped) return
        note?.text = if (allowed > 0) {
            getString(R.string.cle_quota, rows.size, allowed)
        } else {
            getString(R.string.cle_credit)
        }
    }

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

        val statusView = view.findViewById<TextView>(R.id.cle_reservation_status)
        val status = listOf(item.status, item.topic).filter { it.isNotBlank() }.joinToString(" · ")
        statusView.text = status
        statusView.visibility = if (status.isBlank()) View.GONE else View.VISIBLE
    }

    /** Asks the page for a path and waits for that request's own answer. */
    private suspend fun query(path: String): String = queries.withLock {
        val web = bootstrap ?: throw IllegalStateException("cle: no bootstrap WebView")
        val id = ++pendingId
        val waiting = CompletableDeferred<String>()
        pending = waiting
        val script = CleApi.JS_FETCH
            .replace("__PATH__", path)
            .replace("__ID__", id.toString())
        withContext(Dispatchers.Main) { web.evaluateJavascript(script, null) }
        return withTimeout(30_000) { waiting.await() }
    }

    /**
     * Loads the app index once so the EMAP adapter runs, then loads the list. The
     * page keeps the session; nothing is exported from it.
     */
    private fun startBootstrap(view: View) {
        val web = WebView(requireContext())
        bootstrap = web
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.visibility = View.INVISIBLE
        web.addJavascriptInterface(bridge, CleApi.BRIDGE)
        (view as? ViewGroup)?.addView(web, ViewGroup.LayoutParams(1, 1))
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(loaded: WebView, url: String) {
                if (settled) return
                // The adapter's own XHRs run just after the page finishes; let
                // them land before asking for data.
                loaded.postDelayed({
                    if (settled || !isAdded) return@postDelayed
                    settled = true
                    App.cle.markBootstrapped()
                    load(force = true)
                }, BOOTSTRAP_SETTLE_MS)
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
            web.removeJavascriptInterface(CleApi.BRIDGE)
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        bootstrap = null
        note = null
        super.onDestroyView()
    }

    private companion object {
        /** Time for the E-Hall page's own XHRs to establish its session. */
        const val BOOTSTRAP_SETTLE_MS = 2500L
    }
}
