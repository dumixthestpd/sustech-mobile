package edu.sustech.mobile.core

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import edu.sustech.mobile.R
import edu.sustech.mobile.bb.BbApi
import edu.sustech.mobile.ecard.EcardApi
import edu.sustech.mobile.library.LibraryApi
import edu.sustech.mobile.nces.NcesApi
import edu.sustech.mobile.pms.PmsApi
import edu.sustech.mobile.tis.TisApi
import edu.sustech.mobile.transit.BusApi
import edu.sustech.mobile.weather.WeatherClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Process-wide singletons: one HTTP client, one cookie jar, one API per
 * service.
 *
 * The cookie jar is the whole session story — it is host-scoped, so a PMS
 * session and a TIS session coexist and survive an app restart, exactly as
 * separate browser tabs would.
 */
object App {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        // The UI has one light palette; force it so a device in dark mode cannot
        // hand the views dark tints on top of white surfaces.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        AppConfig.init(appContext)
        Credentials.init(appContext)
    }

    val cookies: CookieStore by lazy { CookieStore(appContext) }

    /** 校园卡（一卡通）API */
    val ecard: EcardApi by lazy { EcardApi(http) }

    /**
     * Client for API calls. Redirects are **not** followed: a 302 to CAS means
     * "sign in again", and a 302 that points at an `http://` address must never
     * be chased (the platform blocks cleartext anyway, which used to surface as a
     * confusing "CLEARTEXT" failure on an otherwise healthy https server).
     */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookies)
            .followRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** Client for the sign-in handshake, whose ticket exchange *is* a redirect chain. */
    val httpFollow: OkHttpClient by lazy { http.newBuilder().followRedirects(true).build() }

    /**
     * Alias kept for the CAS step that needs the raw `Location` header. Same as
     * [http] now that API calls stopped following redirects.
     */
    val httpNoRedirect: OkHttpClient get() = http

    val api: PmsApi by lazy { PmsApi(http) { AppConfig.baseUrl } }

    val tis: TisApi by lazy { TisApi(http) }

    val bb: BbApi by lazy { BbApi(http) }

    val bus: BusApi by lazy {
        BusApi(http) { appContext.resources.configuration.locales[0]?.language.orEmpty() }
    }

    val weather: WeatherClient by lazy { WeatherClient(http) }

    val library: LibraryApi by lazy { LibraryApi(http) }

    /** My-library loans (borrowed books, due dates, renew). */
    val loans: edu.sustech.mobile.library.LoansApi by lazy {
        edu.sustech.mobile.library.LoansApi(http)
    }

    /** Public NCES course and review reads; no sign-in required. */
    val nces: NcesApi by lazy { NcesApi(http) }

    /** 外事信息系统 exchange programmes, read natively with the stored account. */
    val ws: edu.sustech.mobile.ws.WsApi by lazy { edu.sustech.mobile.ws.WsApi(http) }

    /** E-Hall venue booking, read natively behind the ticket → token handshake. */
    val booking: edu.sustech.mobile.booking.BookingApi by lazy {
        edu.sustech.mobile.booking.BookingApi(http)
    }

    /** E-Hall language tutoring; needs the WebView bootstrap before its reads answer. */
    val cle: edu.sustech.mobile.cle.CleApi by lazy { edu.sustech.mobile.cle.CleApi() }

    /** Application context, for callers that need assets or resources. */
    val context: android.content.Context get() = appContext

    /** Bare hostname of the configured print server. */
    fun host(): String = Hosts.host(AppConfig.baseUrl)

    fun toast(message: String) {
        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
    }

    fun toast(resId: Int) {
        Toast.makeText(appContext, resId, Toast.LENGTH_SHORT).show()
    }
}
