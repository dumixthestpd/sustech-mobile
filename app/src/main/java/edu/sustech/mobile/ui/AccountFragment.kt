package edu.sustech.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.AppConfig
import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.Credentials
import edu.sustech.mobile.core.Hosts
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.sso.Session
import edu.sustech.mobile.pms.PmsApi
import okhttp3.Request

/**
 * Account tab: the stored school account, per-service session state, language,
 * and the server override used when testing against a local mock.
 *
 * Sessions are per host, so "signed in" is answered per service — and each of
 * them re-authenticates on its own from the stored account.
 */
class AccountFragment : Fragment(R.layout.fragment_account), Refreshable {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Dev-only: the server override exists for testing against a local
        // mock. Hidden entirely unless such an override is in effect, so the
        // shipping UI never shows a print-server address.
        view.findViewById<View>(R.id.card_server).visibility =
            if (AppConfig.hasServerOverride) View.VISIBLE else View.GONE

        val server = view.findViewById<TextInputEditText>(R.id.input_server)
        server.setText(AppConfig.baseUrl)
        view.findViewById<TextView>(R.id.account_server).text = AppConfig.baseUrl
        view.findViewById<MaterialButton>(R.id.btn_server_save).setOnClickListener {
            AppConfig.baseUrl = server.text?.toString().orEmpty()
            server.setText(AppConfig.baseUrl)
            view.findViewById<TextView>(R.id.account_server).text = AppConfig.baseUrl
            Toast.makeText(requireContext(), R.string.server_saved, Toast.LENGTH_SHORT).show()
            load()
        }

        view.findViewById<MaterialButton>(R.id.btn_forget).setOnClickListener {
            Credentials.clear()
            App.cookies.clear()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            Toast.makeText(requireContext(), R.string.account_forgotten, Toast.LENGTH_SHORT).show()
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            requireActivity().finish()
        }

        val language = view.findViewById<TextView>(R.id.account_language)
        language.setText(languageNames()[selectedLanguageIndex()])
        view.findViewById<MaterialButton>(R.id.btn_language).setOnClickListener {
            val names = languageNames()
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.language_title)
                .setSingleChoiceItems(names, selectedLanguageIndex()) { dialog, index ->
                    dialog.dismiss()
                    if (index != selectedLanguageIndex()) {
                        val tag = arrayOf("", "en", "zh-CN", "ru")[index]
                        AppCompatDelegate.setApplicationLocales(
                            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                            else LocaleListCompat.forLanguageTags(tag),
                        )
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        load()
    }

    private fun languageNames(): Array<String> = arrayOf(
        getString(R.string.language_system),
        getString(R.string.language_english),
        getString(R.string.language_chinese),
        getString(R.string.language_russian),
    )

    private fun selectedLanguageIndex(): Int = when (
        AppCompatDelegate.getApplicationLocales().get(0)?.language
    ) {
        "en" -> 1
        "zh" -> 2
        "ru" -> 3
        else -> 0
    }

    override fun refresh() = load()

    private fun load() {
        val account = view?.findViewById<TextView>(R.id.account_identity)
        val accountState = view?.findViewById<TextView>(R.id.account_identity_state)
        val printSession = view?.findViewById<TextView>(R.id.account_print_session)
        val tisSession = view?.findViewById<TextView>(R.id.account_tis_session)
        val network = view?.findViewById<TextView>(R.id.account_network)

        account?.text = if (Credentials.configured) Credentials.sid else getString(R.string.account_unknown)
        accountState?.setText(
            if (Credentials.configured) R.string.account_creds_saved else R.string.account_creds_missing,
        )

        runIo(
            block = { App.api.check() },
            onOk = { printSession?.setText(R.string.account_session_valid) },
            onErr = { error ->
                if (error is ApiException && error.httpStatus == 405) {
                    printSession?.setText(R.string.account_print_unavailable)
                } else {
                    printSession?.text = error.friendly(requireContext())
                }
            },
        )
        runIo(
            block = { Session.ensureCourses() },
            onOk = { semester ->
                tisSession?.text = if (semester == null) {
                    getString(R.string.account_session_invalid)
                } else {
                    getString(
                        R.string.tis_semester,
                        semester.labelEn.ifEmpty { semester.label },
                        getString(R.string.account_session_valid),
                    )
                }
            },
            onErr = { tisSession?.setText(R.string.account_session_invalid) },
        )
        runIo(
            block = { networkClass() },
            onOk = { network?.text = it },
            onErr = { network?.setText(R.string.network_none) },
        )
    }

    /**
     * The host root can reply with HTTP 405 even when the printing API is
     * blocked. Only a valid response from the public PMS endpoint proves that
     * printing is usable. Any other PMS reply still proves the phone is online.
     */
    private fun networkClass(): String {
        return when (App.api.reachability()) {
            PmsApi.Reachability.AVAILABLE -> getString(R.string.network_campus)
            PmsApi.Reachability.REPLIED -> getString(R.string.network_off_campus)
            PmsApi.Reachability.UNREACHABLE -> {
                if (ping("https://" + Hosts.CAS + "/cas/login") != null)
                    getString(R.string.network_off_campus)
                else getString(R.string.network_none)
            }
        }
    }

    /** HTTP status of a GET to [url], or null when nothing answered at all. */
    private fun ping(url: String): Int? = try {
        App.http.newCall(
            Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", edu.sustech.mobile.sso.CasLogin.UA)
                .build(),
        ).execute().use { it.code }
    } catch (e: Exception) {
        // No connection at all is the answer; redirects do not throw because
        // API calls never follow them — the 30x code is the answer.
        null
    }
}
