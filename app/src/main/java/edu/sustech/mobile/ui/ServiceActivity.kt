package edu.sustech.mobile.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.service.ServiceModule
import edu.sustech.mobile.service.Services

/**
 * Hosts one service module, full screen.
 *
 * Services are reached from the catalog rather than the bottom bar; this
 * activity is the boundary between the generic shell and a module's own
 * navigation (the print tabs, the TIS tabs, …).
 *
 * The service id also travels as an intent extra so the UI harness (and adb)
 * can deep-link straight into a module:
 *
 *     adb shell am start -n edu.sustech.mobile/.ui.ServiceActivity --es service tis
 */
class ServiceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.init(applicationContext)
        setContentView(R.layout.activity_service)

        val module = Services.byId(intent.getStringExtra(EXTRA_SERVICE))
        if (module == null || module.root == null) {
            finish()
            return
        }

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.title = getString(module.title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
            } else {
                finish()
            }
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.service_container, module.root.invoke())
                .commit()
        }
    }

    companion object {
        const val EXTRA_SERVICE = "service"

        fun intent(context: Context, module: ServiceModule): Intent =
            Intent(context, ServiceActivity::class.java).putExtra(EXTRA_SERVICE, module.id)
    }
}
