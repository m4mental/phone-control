package com.example.phonecontrol

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class FreezerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_freezer)

        findViewById<MaterialToolbar>(R.id.toolbarFreezer).setNavigationOnClickListener { finish() }

        // Card 1: App Freezer & Hibernation
        findViewById<View>(R.id.cardAppFreezer).setOnClickListener {
            startActivity(Intent(this, AppFreezerListActivity::class.java))
        }

        // Card 2: Carrier Bloatware Remover
        findViewById<View>(R.id.cardBloatware).setOnClickListener {
            startActivity(Intent(this, BloatwareActivity::class.java))
        }

        // Card 3: App & Data Vault [BETA]
        findViewById<View>(R.id.cardVault).setOnClickListener {
            startActivity(Intent(this, VaultActivity::class.java))
        }

        // Card 4: Root Shell Terminal
        findViewById<View>(R.id.cardTerminal).setOnClickListener {
            startActivity(Intent(this, AdbShellActivity::class.java))
        }

        updateVisibility()
    }

    override fun onResume() {
        super.onResume()
        updateVisibility()
    }

    private fun updateVisibility() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        findViewById<View>(R.id.cardAppFreezer).visibility =
            if (prefs.getBoolean("freezer_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardBloatware).visibility =
            if (prefs.getBoolean("bloatware_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardVault).visibility =
            if (prefs.getBoolean("vault_enabled", false)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardTerminal).visibility =
            if (prefs.getBoolean("adb_enabled", true)) View.VISIBLE else View.GONE
    }
}
