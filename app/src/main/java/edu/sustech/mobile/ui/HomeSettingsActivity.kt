package edu.sustech.mobile.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App

class HomeSettingsActivity : AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        App.init(applicationContext)
        setContentView(R.layout.activity_service)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setTitle(R.string.home_layout_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        if (state == null) supportFragmentManager.beginTransaction()
            .replace(R.id.service_container, HomeSettingsFragment()).commit()
    }
}
