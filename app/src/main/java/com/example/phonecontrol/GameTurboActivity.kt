package com.example.phonecontrol

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class GameTurboActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_turbo)

        findViewById<MaterialToolbar>(R.id.toolbarGameTurbo).setNavigationOnClickListener { finish() }

        // Sub-feature Card 1: Game Turbo Suite & Tuner
        findViewById<View>(R.id.cardGameTurboSuite).setOnClickListener {
            startActivity(Intent(this, GameTurboSuiteActivity::class.java))
        }

        // Sub-feature Card 2: Per-App Custom Profiles
        findViewById<View>(R.id.cardPerApp).setOnClickListener {
            startActivity(Intent(this, PerAppActivity::class.java))
        }

        updateVisibility()
    }

    override fun onResume() {
        super.onResume()
        updateVisibility()
    }

    private fun updateVisibility() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        findViewById<View>(R.id.cardGameTurboSuite).visibility =
            if (prefs.getBoolean("game_turbo_enabled", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardPerApp).visibility =
            if (prefs.getBoolean("per_app_enabled", true)) View.VISIBLE else View.GONE
    }
}
