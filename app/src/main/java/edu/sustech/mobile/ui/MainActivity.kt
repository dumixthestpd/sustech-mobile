package edu.sustech.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import android.webkit.CookieManager
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.Credentials

/**
 * The shell: three fixed destinations (Today, Services, Account).
 *
 * Individual services are NOT tabs — a new service would push the bottom bar
 * past its limit and reshuffle everything. They open in
 * [ServiceActivity] instead, so the shell never changes when the catalog
 * grows.
 */
class MainActivity : AppCompatActivity() {

    private val tabs = LinkedHashMap<Int, Fragment>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.init(applicationContext)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        findViewById<BottomNavigationView>(R.id.bottom_nav).setOnItemSelectedListener { item ->
            show(fragmentFor(item.itemId))
            true
        }
        if (savedInstanceState == null) {
            show(fragmentFor(R.id.nav_today))
            when (HomePreferences.read(this).landing) {
                "bb" -> startActivity(ServiceActivity.intent(this, edu.sustech.mobile.service.Services.blackboard).putExtra("bb_deadlines", true))
                "bus" -> startActivity(ServiceActivity.intent(this, edu.sustech.mobile.service.Services.transit))
            }
        }
    }

    private fun fragmentFor(id: Int): Fragment = tabs.getOrPut(id) {
        when (id) {
            R.id.nav_services -> ServicesFragment()
            R.id.nav_account -> AccountFragment()
            else -> TodayFragment()
        }
    }

    private fun show(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .commit()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        for (i in 0 until menu.size()) menu.getItem(i).icon?.setTint(getColor(R.color.brand))
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> {
            (supportFragmentManager.findFragmentById(R.id.container) as? Refreshable)?.refresh()
            true
        }
        R.id.action_forget -> {
            // Forget the account AND every session: with credentials stored,
            // clearing cookies alone would just sign back in on the next launch.
            Credentials.clear()
            App.cookies.clear()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            Toast.makeText(this, R.string.account_forgotten, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
