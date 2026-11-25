package edu.sustech.mobile.core

import android.content.Context

/**
 * The one credential pair the app keeps: the school account.
 *
 * Entered once (there is no per-service sign-in any more) and reused for every
 * service afterwards — the same contract as `sustech_survival`'s
 * `credentials.txt`: one cred, and services re-authenticate themselves forever
 * after.
 *
 * Stored in the app's private SharedPreferences. `allowBackup=false` keeps it
 * out of cloud backups; nothing else can read it without root. It is never
 * logged, never returned in a UI string, and never leaves the device except as
 * a CAS/RSA login body to the university's own endpoints.
 */
object Credentials {

    private const val PREFS = "sustech_mobile_creds"
    private const val KEY_SID = "sid"
    private const val KEY_PASSWORD = "password"
    private const val KEY_VALIDATION = "validation"

    enum class Validation {
        UNVERIFIED,
        VERIFIED,
        REJECTED,
    }

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val prefs get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True once a student ID and password have been entered. */
    val configured: Boolean get() = sid.isNotEmpty() && password.isNotEmpty()

    val sid: String get() = prefs.getString(KEY_SID, "").orEmpty()

    /** Read-only here; the only writer is [save]. */
    val password: String get() = prefs.getString(KEY_PASSWORD, "").orEmpty()

    val validation: Validation
        get() = when (prefs.getString(KEY_VALIDATION, null)) {
            Validation.VERIFIED.name -> Validation.VERIFIED
            Validation.REJECTED.name -> Validation.REJECTED
            else -> Validation.UNVERIFIED
        }

    fun save(sid: String, password: String) {
        val cleanSid = sid.trim()
        if (cleanSid.isEmpty() || password.isEmpty()) return
        prefs.edit()
            .putString(KEY_SID, cleanSid)
            .putString(KEY_PASSWORD, password)
            .putString(KEY_VALIDATION, Validation.UNVERIFIED.name)
            .apply()
    }

    fun markVerified() {
        if (configured) prefs.edit().putString(KEY_VALIDATION, Validation.VERIFIED.name).apply()
    }

    fun markRejected() {
        val rejectedSid = sid
        prefs.edit()
            .remove(KEY_SID)
            .remove(KEY_PASSWORD)
            .putString(KEY_VALIDATION, Validation.REJECTED.name)
            .apply()
        if (rejectedSid.isNotEmpty()) AppConfig.lastUsername = rejectedSid
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
