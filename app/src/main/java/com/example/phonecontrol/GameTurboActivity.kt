package com.example.phonecontrol

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial

class GameTurboActivity : AppCompatActivity() {

    private lateinit var layoutGameList: LinearLayout
    private val pickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val pkg = result.data?.getStringExtra("package_name")
            if (pkg != null) {
                addGameToList(pkg)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_turbo)

        findViewById<MaterialToolbar>(R.id.toolbarGameTurbo).setNavigationOnClickListener { finish() }
        layoutGameList = findViewById(R.id.layoutGameList)

        findViewById<Button>(R.id.btnAddGame).setOnClickListener {
            pickerLauncher.launch(Intent(this, AppInspectorActivity::class.java))
        }

        setupToggles()
        refreshGameList()
    }

    private fun setupToggles() {
        val prefs = getSharedPreferences("game_turbo_prefs", MODE_PRIVATE)
        val swPerf = findViewById<SwitchMaterial>(R.id.switchAutoPerf)
        val swPing = findViewById<SwitchMaterial>(R.id.switchAutoPing)
        val swThermal = findViewById<SwitchMaterial>(R.id.switchAutoThermal)

        swPerf.isChecked = prefs.getBoolean("auto_perf_enabled", true)
        swPing.isChecked = prefs.getBoolean("auto_ping_enabled", true)
        swThermal.isChecked = prefs.getBoolean("auto_thermal_enabled", false)

        swPerf.setOnCheckedChangeListener { _, isC -> prefs.edit().putBoolean("auto_perf_enabled", isC).apply() }
        swPing.setOnCheckedChangeListener { _, isC -> prefs.edit().putBoolean("auto_ping_enabled", isC).apply() }
        swThermal.setOnCheckedChangeListener { _, isC -> prefs.edit().putBoolean("auto_thermal_enabled", isC).apply() }
    }

    private fun refreshGameList() {
        layoutGameList.removeAllViews()
        val prefs = getSharedPreferences("game_turbo_prefs", MODE_PRIVATE)
        val games = prefs.getStringSet("game_packages", emptySet()) ?: emptySet()

        if (games.isEmpty()) {
            val tv = TextView(this).apply {
                text = "No games added yet. Click + to add."
                setTextColor(android.graphics.Color.GRAY)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 50, 0, 0)
            }
            layoutGameList.addView(tv)
            return
        }

        for (pkg in games) {
            val view = layoutInflater.inflate(R.layout.item_app_picker, layoutGameList, false)
            val tvName = view.findViewById<TextView>(R.id.tvAppName)
            val tvPkg = view.findViewById<TextView>(R.id.tvPackageName)
            val ivIcon = view.findViewById<ImageView>(R.id.ivAppIcon)
            val btnRemove = view.findViewById<CheckBox>(R.id.cbSelect)
            btnRemove.visibility = View.VISIBLE
            btnRemove.isChecked = true

            try {
                val appInfo = packageManager.getApplicationInfo(pkg, 0)
                tvName.text = packageManager.getApplicationLabel(appInfo)
                ivIcon.setImageDrawable(packageManager.getApplicationIcon(appInfo))
            } catch (e: Exception) {
                tvName.text = "Unknown Game"
            }
            tvPkg.text = pkg
            view.findViewById<TextView>(R.id.tvAppStatus).visibility = View.GONE

            btnRemove.setOnClickListener {
                removeGameFromList(pkg)
            }

            layoutGameList.addView(view)
        }
    }

    private fun addGameToList(pkg: String) {
        val prefs = getSharedPreferences("game_turbo_prefs", MODE_PRIVATE)
        val current = prefs.getStringSet("game_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.add(pkg)) {
            prefs.edit().putStringSet("game_packages", current).apply()
            refreshGameList()
            Toast.makeText(this, "Game added to library", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeGameFromList(pkg: String) {
        val prefs = getSharedPreferences("game_turbo_prefs", MODE_PRIVATE)
        val current = prefs.getStringSet("game_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.remove(pkg)) {
            prefs.edit().putStringSet("game_packages", current).apply()
            refreshGameList()
        }
    }
}
