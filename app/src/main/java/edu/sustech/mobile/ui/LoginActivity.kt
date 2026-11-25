package edu.sustech.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.AppConfig
import edu.sustech.mobile.core.Credentials
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.sso.Session

/**
 * The app's only sign-in screen, and the only time credentials are ever typed.
 *
 * One school account is stored and reused for every service; sessions are
 * re-established silently from it afterwards, so this is a first-run stop, not
 * a gate.
 *
 * There is no per-service sign-in and no browser sign-in: TIS refuses mobile
 * browser sign-ins, and a native client has no reason to show someone else's
 * login page.
 */
class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.init(applicationContext)
        setContentView(R.layout.activity_login)

        val inputSid = findViewById<TextInputEditText>(R.id.input_username)
        val inputPassword = findViewById<TextInputEditText>(R.id.input_password)
        val progress = findViewById<ProgressBar>(R.id.login_progress)
        val button = findViewById<MaterialButton>(R.id.btn_login)
        val status = findViewById<TextView>(R.id.login_status)

        inputSid.setText(Credentials.sid.ifEmpty { AppConfig.lastUsername })

        button.setOnClickListener {
            val sid = inputSid.text?.toString()?.trim().orEmpty()
            val password = inputPassword.text?.toString().orEmpty()
            if (sid.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, R.string.login_missing_fields, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AppConfig.lastUsername = sid
            Credentials.save(sid, password)
            signIn(progress, button, status)
        }

        // No account needed to look around: the catalog, the campus weather and
        // the service screens (which say what they need) all work without one.
        findViewById<MaterialButton>(R.id.btn_skip).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        status.text = AppConfig.lastSignInNote
        if (Credentials.configured && !intent.getBooleanExtra(EXTRA_FORCE_LOGIN, false)) {
            // Nothing left to ask: the account is already saved, so open the
            // app and let the verification run alongside it. Waiting here is
            // what made "continue without credentials, then refresh" the fast
            // path — the verdict is the same a moment later, and the timeout
            // only ever bought a spinner.
            Session.verifyInBackground()
            openMain()
        }
    }

    private fun signIn(progress: ProgressBar, button: MaterialButton, status: TextView) {
        progress.visibility = View.VISIBLE
        button.isEnabled = false
        runIo(
            block = { Session.signIn() },
            onOk = { report ->
                progress.visibility = View.GONE
                button.isEnabled = true
                // Show (and remember) the reason, not just the verdict.
                status.text = report.detail
                AppConfig.lastSignInNote = report.detail
                if (report.detail.isNotEmpty()) Log.w(TAG, "sign-in: $report.detail")
                when (report.access) {
                    Session.Access.ACCEPTED -> openMain()

                    // Only an explicit refusal means the account is wrong.
                    Session.Access.REFUSED -> {
                        status.setText(R.string.login_rejected)
                        Toast.makeText(this, R.string.login_rejected, Toast.LENGTH_LONG).show()
                    }

                    // Printing is campus-only, so "nothing answered" is a
                    // network fact, not a credential fact: let the user in and
                    // say what happened.
                    Session.Access.UNREACHABLE -> {
                        Toast.makeText(this, R.string.login_unreachable, Toast.LENGTH_LONG).show()
                        openMain()
                    }
                }
            },
            onErr = { error ->
                progress.visibility = View.GONE
                button.isEnabled = true
                Toast.makeText(this, error.friendly(this), Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        private const val TAG = "SustechSignIn"
        const val EXTRA_FORCE_LOGIN = "force_login"
    }
}
