package edu.sustech.mobile.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.Credentials
import edu.sustech.mobile.core.WebCookies
import edu.sustech.mobile.sso.CasLogin
import edu.sustech.mobile.sso.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Official service pages share the app's CAS session inside this WebView. */
class ServicePortalActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.init(applicationContext)
        setContentView(R.layout.activity_service_portal_web)

        val url = intent.getStringExtra(EXTRA_URL)?.takeIf(::isOfficialUrl)
        if (url == null) {
            finish()
            return
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.portal_web_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val actionUrl = intent.getStringExtra(EXTRA_ACTION_URL)?.takeIf(::isOfficialUrl)
        val actionTitle = intent.getIntExtra(EXTRA_ACTION_TITLE, 0)
        if (actionUrl != null && actionTitle != 0) {
            toolbar.menu.add(Menu.NONE, ACTION_PROJECT_OVERVIEW, Menu.NONE, getString(actionTitle))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            toolbar.setOnMenuItemClickListener { item ->
                if (item.itemId == ACTION_PROJECT_OVERVIEW) {
                    web.loadUrl(actionUrl)
                    true
                } else {
                    false
                }
            }
        }

        web = findViewById(R.id.portal_web)
        progress = findViewById(R.id.portal_web_progress)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                routeUrl(request.url)

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                routeUrl(Uri.parse(url))
        }

        lifecycleScope.launch {
            if (Credentials.configured && Uri.parse(url).host == EXCHANGE_HOST) {
                // This platform has a service-specific ticket. TIS can still
                // work after this service session expires, so renew it directly.
                withContext(Dispatchers.IO) { runCatching { ensurePortalSession(EXCHANGE_SERVICE) } }
            } else if (Credentials.configured && isLanguageHelpUrl(url)) {
                // Open the tutoring booking app directly and establish its own
                // CAS session before handing the shared cookies to the WebView.
                withContext(Dispatchers.IO) { runCatching { ensurePortalSession(LANGUAGE_HELP_SERVICE) } }
            } else if (Credentials.configured && isCampusCardUrl(url)) {
                // The electronic card face uses the same campus-card CAS
                // session as the native balance and QR-code API.
                withContext(Dispatchers.IO) {
                    if (!Session.ensureCard()) Session.reloginCard()
                }
            } else if (Credentials.configured && isIcBookingUrl(url)) {
                // The IC booking app has its own authcenter handshake. Without it the
                // page renders its own "error page!" — measured 2026-10-06: every route
                // does that with no ic-cookie, whatever the URL, and `requiresCas` below
                // would otherwise give it the *courses* session instead.
                withContext(Dispatchers.IO) { runCatching { App.rooms.ensureSession() } }
            } else if (requiresCas(url) && Credentials.configured) {
                withContext(Dispatchers.IO) { Session.ensureCourses() }
            }
            WebCookies.install(web, url) { if (!isFinishing) web.loadUrl(url) }
        }
    }

    private fun routeUrl(uri: Uri): Boolean {
        val scheme = uri.scheme.orEmpty()
        if (scheme == "about") return false
        if (scheme == "https" && isOfficialHost(uri.host.orEmpty())) return false
        if (scheme !in setOf("http", "https", "mailto", "tel")) return true
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            App.toast(R.string.portal_no_handler)
        }
        return true
    }

    private fun requiresCas(url: String): Boolean = Uri.parse(url).host.orEmpty() in setOf(
        "ehall.sustech.edu.cn",
        "booking.lib.sustech.edu.cn",
        "ws.sustech.edu.cn",
    )

    /** Probe a read-only service page, then refresh that service's CAS ticket. */
    private fun ensurePortalSession(serviceUrl: String) {
        val request = okhttp3.Request.Builder()
            .url(serviceUrl)
            .get()
            .header("User-Agent", CasLogin.UA)
            .build()
        fun needsLogin(response: okhttp3.Response): Boolean =
            response.code == 401 || response.code == 403 ||
                (response.isRedirect && response.header("Location").orEmpty().let { location ->
                    location.contains(CAS_HOST) || location.contains(AUTH_ADAPTER)
                })

        val expired = App.http.newCall(request).execute().use(::needsLogin)
        if (expired) {
            CasLogin.login(serviceUrl, Credentials.sid, Credentials.password, xhr = false)
            val authenticated = App.http.newCall(request).execute().use { response ->
                response.isSuccessful && !needsLogin(response)
            }
            if (!authenticated) throw edu.sustech.mobile.core.ApiException("Service sign-in did not establish a session")
        }
    }

    private fun isLanguageHelpUrl(url: String): Boolean =
        Uri.parse(url).path.orEmpty().contains("/dxggyw/sys/yyzxyy/")

    private fun isCampusCardUrl(url: String): Boolean =
        Uri.parse(url).host == "campuscard.sustech.edu.cn"
                && Uri.parse(url).path.orEmpty().startsWith("/epay/")

    private fun isIcBookingUrl(url: String): Boolean =
        Uri.parse(url).host == "booking.lib.sustech.edu.cn"

    private fun isOfficialUrl(url: String): Boolean =
        Uri.parse(url).scheme == "https" && isOfficialHost(Uri.parse(url).host.orEmpty())

    private fun isOfficialHost(host: String): Boolean =
        host == "sustech.edu.cn" || host.endsWith(".sustech.edu.cn")

    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "portal_url"
        private const val EXTRA_ACTION_URL = "portal_action_url"
        private const val EXTRA_ACTION_TITLE = "portal_action_title"
        private const val ACTION_PROJECT_OVERVIEW = 7301
        private const val CAS_HOST = "cas.sustech.edu.cn"
        private const val AUTH_ADAPTER = "/amp-auth-adapter/login"
        private const val EXCHANGE_HOST = "ws.sustech.edu.cn"
        private const val EXCHANGE_SERVICE = "https://ws.sustech.edu.cn/SUSTechHome.aspx"
        private const val LANGUAGE_HELP_SERVICE =
            "https://ehall.sustech.edu.cn/dxggyw/sys/yyzxyy/*default/index.do"

        fun intent(
            context: android.content.Context,
            url: String,
            actionUrl: String? = null,
            actionTitle: Int? = null,
        ) = Intent(context, ServicePortalActivity::class.java)
            .putExtra(EXTRA_URL, url)
            .apply {
                if (actionUrl != null && actionTitle != null) {
                    putExtra(EXTRA_ACTION_URL, actionUrl)
                    putExtra(EXTRA_ACTION_TITLE, actionTitle)
                }
            }
    }
}
