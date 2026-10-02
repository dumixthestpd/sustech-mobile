package edu.sustech.mobile.sso

import android.util.Log
import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.AppConfig
import edu.sustech.mobile.core.Cache
import edu.sustech.mobile.core.Credentials
import edu.sustech.mobile.core.Hosts
import edu.sustech.mobile.pms.PmsAuth
import edu.sustech.mobile.tis.Semester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Auto sign-in for every service, from the one stored school account.
 *
 * Nothing in the UI signs in per service: screens call `ensureX()`, which
 * reuses a live session or silently re-authenticates with the stored
 * credentials, so expiry is invisible — the behavior the Python client gets
 * from `Authorizer.ensure()`.
 *
 * [signIn] classifies the outcome instead of returning a bare boolean, because
 * one of the services is campus-only: printing answers 403 from anywhere else,
 * and that must never be presented as "your account is wrong".
 */
object Session {

    /** How a sign-in attempt ended. */
    enum class Access {
        /** At least one service accepted the account. */
        ACCEPTED,

        /** A service answered and rejected the account — the credentials are wrong. */
        REFUSED,

        /** Nothing answered: off campus, offline, or the service is down. */
        UNREACHABLE,
    }

    /** The verdict plus per-service reasons, so a failure is never a mystery. */
    data class SignInReport(val access: Access, val detail: String)

    /** One probe's outcome, with how long it held the user up. */
    private data class Probe(val access: Access, val reason: String, val millis: Long)

    /** TIS's CAS entry point, the same value the Python `TISAuth` uses. */
    private const val TIS_SERVICE = Hosts.TIS + "/cas"

    /** 校园卡门户的 Spring Security CAS 入口（实际跳转确认过）。 */
    private const val CARD_SERVICE =
        Hosts.CAMPUS_CARD + "/epay/j_spring_cas_security_check"

    /**
     * The print site itself. Visiting it through CAS is what the website does,
     * and the print back end links the CAS identity to the print account (it
     * creates one on first visit) — which is how the Python client's refresh
     * works too. The site's own RSA password login is kept as a fallback.
     */
    private const val PRINT_SERVICE = Hosts.PMS + "/client/new/cprintPc/"

    /**
     * Signs in and reports the strongest signal seen.
     *
     * The course probe goes first and normally settles the question on its own:
     * CAS answers from any network, so it is both the credential check and the
     * fast one. Printing is asked **only** when the course probe could not
     * answer at all — an unreachable CAS is the single case where a second
     * opinion changes the verdict, and printing is campus-only, so probing it
     * on every sign-in spent a CAS handshake plus an RSA password login on the
     * one service that can never answer off campus. `ensurePrint()` and the
     * per-screen re-login still run it lazily, where it is actually needed.
     *
     * One flat rule drives the whole classification: only an explicit refusal
     * counts as a bad account. Off-campus (printing), timeouts and 5xx replies
     * are unreachable, so a user off campus is never told their password is
     * wrong because printing could not be reached.
     */
    fun signIn(): SignInReport {
        requireCredentials()
        val startedAt = System.currentTimeMillis()
        val courses = probe("courses") { coursesAccess() }
        val print: Probe?
        val access: Access
        if (courses.access == Access.UNREACHABLE) {
            print = probe("printing") { printAccess() }
            access = when {
                print.access == Access.ACCEPTED -> Access.ACCEPTED
                print.access == Access.REFUSED -> Access.REFUSED
                else -> Access.UNREACHABLE
            }
        } else {
            // Nothing to ask printing: the course probe already returned a
            // verdict, and only a second *verdict* could change it.
            print = null
            access = courses.access
        }
        val detail = listOfNotNull(courses, print)
            .filter { it.reason.isNotEmpty() }
            .joinToString(" · ") { it.reason }
        val timing = "courses ${courses.millis}ms, printing " +
            (print?.let { "${it.millis}ms" } ?: "skipped")
        val elapsed = System.currentTimeMillis() - startedAt
        Log.i(
            TAG,
            "sign-in $access in ${elapsed}ms ($timing)${if (detail.isEmpty()) "" else " · $detail"}",
        )
        return SignInReport(access, detail)
    }

    /**
     * Verifies a saved account while the app is already open.
     *
     * With credentials stored there is nothing to ask and nothing worth gating
     * on: the verdict reads the same a second later, and holding the app shut
     * for it is what made "continue without credentials, then refresh" the
     * fast path. Runs on an app-level scope so the verdict still lands once the
     * sign-in screen is gone; a refusal ends up in the sign-in note the Account
     * tab shows.
     */
    fun verifyInBackground() {
        if (!Credentials.configured) return
        scope.launch {
            val report = runCatching { signIn() }.getOrNull() ?: return@launch
            AppConfig.lastSignInNote = report.detail
            if (report.detail.isNotEmpty()) Log.w(TAG, "sign-in: ${report.detail}")
        }
    }

    /** Outlives the sign-in screen — see [verifyInBackground]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Runs one probe, turning any failure into a reason string, and times it. */
    private fun probe(name: String, block: () -> Access): Probe {
        val startedAt = System.currentTimeMillis()
        val (access, reason) = try {
            when (val result = block()) {
                Access.ACCEPTED -> result to ""
                else -> result to "$name: ${lastReason(name) ?: "no answer"}"
            }
        } catch (e: ApiException) {
            Access.UNREACHABLE to "$name: ${e.message}"
        } catch (e: Exception) {
            Access.UNREACHABLE to "$name: ${e::class.java.simpleName}: ${e.message}"
        }
        return Probe(access, reason, System.currentTimeMillis() - startedAt)
    }

    /** Why the last probe for [name] failed, kept for the report. */
    private val reasons = HashMap<String, String>()

    private fun lastReason(name: String): String? = reasons[name]

    private fun note(name: String, reason: String) {
        reasons[name] = reason
    }

    /** Live print session, or a fresh one from the stored credentials. */
    fun ensurePrint(): Boolean = printAccess() == Access.ACCEPTED

    /** Live TIS session, or a fresh one from the stored credentials. */
    fun ensureCourses(): Semester? = runCatching { App.tis.currentSemester() }
        .getOrNull()
        ?: reloginCourses()

    /** True while a re-login is running, so the path cannot re-enter itself. */
    private var reloginInFlight = false

    fun reloginCourses(): Semester? {
        if (!Credentials.configured || reloginInFlight) return null
        reloginInFlight = true
        return try {
            // A new session can belong to a different account; nothing cached
            // from the old one may survive it.
            Cache.invalidate("tis.")
            CasLogin.login(TIS_SERVICE, Credentials.sid, Credentials.password, xhr = true)
            App.tis.currentSemester()
        } catch (e: ApiException) {
            null
        } finally {
            reloginInFlight = false
        }
    }

    fun reloginPrint(): Boolean {
        if (reloginInFlight) return false
        reloginInFlight = true
        return try {
            printAccess() == Access.ACCEPTED
        } finally {
            reloginInFlight = false
        }
    }

    /**
     * Blackboard's CAS entry point — the same service URL the Python
     * `BBAuth` uses. Cookies land scoped to `bb.sustech.edu.cn`.
     */
    private const val BB_SERVICE =
        Hosts.BLACKBOARD + "/webapps/bb-sso-BBLEARN/index.jsp"

    /** Live Blackboard session, or a fresh one from the stored credentials. */
    fun ensureBb(): Boolean = runCatching { App.bb.isSignedIn() }
        .getOrNull() == true
        ?: reloginBb()

    /** 校园卡是否已被 CAS 接受（Account 页的会话探针）。 */
    fun ensureCard(): Boolean = runCatching { App.ecard.isSignedIn() }.getOrDefault(false)

    /**
     * 校园卡会话失效后静默重登。
     *
     * 与 [reloginBb] 同一套契约：复用已存凭据走一次 CAS，成功即返回 true。
     * 节流位 [reloginInFlight] 是共享的，避免多个服务同时打 CAS 被限流。
     */
    fun reloginCard(): Boolean {
        if (!Credentials.configured || reloginInFlight) return false
        reloginInFlight = true
        return try {
            Cache.invalidate("ecard.")
            CasLogin.login(CARD_SERVICE, Credentials.sid, Credentials.password, xhr = false)
            App.ecard.isSignedIn()
        } catch (e: ApiException) {
            false
        } finally {
            reloginInFlight = false
        }
    }

    fun reloginBb(): Boolean {
        if (!Credentials.configured || reloginInFlight) return false
        reloginInFlight = true
        return try {
            Cache.invalidate("bb.")
            CasLogin.login(BB_SERVICE, Credentials.sid, Credentials.password,
                xhr = false, submitValue = "提交")
            App.bb.isSignedIn()
        } catch (e: ApiException) {
            false
        } finally {
            reloginInFlight = false
        }
    }

    // -- Per-service classification -------------------------------------------

    private fun printAccess(): Access {
        if (printSessionAlive()) return Access.ACCEPTED
        if (!Credentials.configured) return Access.REFUSED

        var refused = false
        // 1) CAS — the path the browser takes, and the one the real server
        //    accepts. It also works for accounts with no print password yet.
        //    Skipped for a local test server, which has no CAS in front of it.
        if (!AppConfig.isLocalHost(Hosts.host(AppConfig.baseUrl))) {
            try {
                CasLogin.login(PRINT_SERVICE, Credentials.sid, Credentials.password, xhr = false)
                Cache.invalidate("pms.")
                if (printSessionAlive()) return Access.ACCEPTED
                note("printing", "CAS sign-in did not produce a print session")
            } catch (e: ApiException) {
                if (e.refused) refused = true
                note("printing", e.message.orEmpty())
            }
        }
        // 2) The print system's own RSA password login.
        try {
            PmsAuth.login(Credentials.sid, Credentials.password)
            if (printSessionAlive()) return Access.ACCEPTED
            note("printing", "print login did not produce a session")
        } catch (e: ApiException) {
            if (e.refused) refused = true
            note("printing", e.message.orEmpty())
        }
        return if (refused) Access.REFUSED else Access.UNREACHABLE
    }

    /**
     * Session probe for the sign-in logic. Uses the un-wrapped check on purpose:
     * the wrapped one re-enters [reloginPrint] and would recurse.
     */
    private fun printSessionAlive(): Boolean = try {
        App.api.checkSession()
        true
    } catch (e: ApiException) {
        false
    }

    private fun coursesAccess(): Access {
        runCatching { App.tis.currentSemester() }.onSuccess { return Access.ACCEPTED }
        if (!Credentials.configured) return Access.REFUSED
        return try {
            CasLogin.login(TIS_SERVICE, Credentials.sid, Credentials.password, xhr = true)
            App.tis.currentSemester()
            Access.ACCEPTED
        } catch (e: ApiException) {
            note("courses", e.message.orEmpty())
            if (e.refused) Access.REFUSED else Access.UNREACHABLE
        }
    }

    private fun requireCredentials() {
        if (!Credentials.configured) {
            throw ApiException("No school account saved", signInRequired = true)
        }
    }

    private const val TAG = "SustechSignIn"
}
