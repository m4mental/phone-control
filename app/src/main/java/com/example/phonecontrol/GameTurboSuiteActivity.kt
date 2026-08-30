package com.example.phonecontrol

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.concurrent.thread

class GameTurboSuiteActivity : AppCompatActivity() {

    private lateinit var layoutGameAppsList: LinearLayout
    private lateinit var pm: PackageManager

    data class GameDisplayItem(
        val pkg: String,
        val name: String,
        val icon: Drawable?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_turbo_suite)

        findViewById<MaterialToolbar>(R.id.toolbarGameSuite).setNavigationOnClickListener { finish() }

        pm = packageManager
        layoutGameAppsList = findViewById(R.id.layoutGameAppsList)

        val prefs = getSharedPreferences("game_turbo_prefs", MODE_PRIVATE)
        val switchMaster = findViewById<SwitchMaterial>(R.id.switchGameTurboMaster)
        val switchTouch = findViewById<SwitchMaterial>(R.id.switchTouchSampling)
        val btnAddGame = findViewById<MaterialButton>(R.id.btnAddGame)

        switchMaster.isChecked = prefs.getBoolean("game_turbo_enabled", false)
        switchMaster.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("game_turbo_enabled", isChecked).apply()
            Toast.makeText(this, if (isChecked) "Auto Game Turbo Active" else "Game Turbo Disabled", Toast.LENGTH_SHORT).show()
        }

        switchTouch.isChecked = prefs.getBoolean("game_turbo_touch_sampling", false)
        switchTouch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("game_turbo_touch_sampling", isChecked).apply()
            thread { GameTurboManager.applyTouchSampling(this, isChecked) }
            Toast.makeText(this, if (isChecked) "High Touch Polling Enabled" else "Standard Touch Polling", Toast.LENGTH_SHORT).show()
        }

        btnAddGame.setOnClickListener {
            showGamePicker()
        }

        refreshGameList()
    }

    private fun refreshGameList() {
        val games = GameTurboManager.getTurboGames(this)
        if (games.isEmpty()) {
            layoutGameAppsList.removeAllViews()
            val tv = TextView(this).apply {
                text = "No games added yet.\nTap '+ Add Game or App' above to select your games."
                setTextColor(android.graphics.Color.GRAY)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 40, 0, 0)
                textSize = 12f
            }
            layoutGameAppsList.addView(tv)
            return
        }

        thread {
            val displayItems = games.map { pkg ->
                var name = pkg
                var icon: Drawable? = null
                try {
                    val info = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                    name = pm.getApplicationLabel(info).toString()
                    icon = pm.getApplicationIcon(info)
                } catch (ignored: Exception) {}
                GameDisplayItem(pkg, name, icon)
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                layoutGameAppsList.removeAllViews()
                for (item in displayItems) {
                    val view = layoutInflater.inflate(R.layout.item_app_picker, layoutGameAppsList, false)
                    val ivIcon = view.findViewById<ImageView>(R.id.ivAppIcon)
                    val tvName = view.findViewById<TextView>(R.id.tvAppName)
                    val tvPkg = view.findViewById<TextView>(R.id.tvPackageName)
                    val tvStatus = view.findViewById<TextView>(R.id.tvAppStatus)
                    val cbSelect = view.findViewById<CheckBox>(R.id.cbSelect)

                    cbSelect.visibility = View.GONE
                    if (item.icon != null) ivIcon.setImageDrawable(item.icon) else ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                    tvName.text = item.name
                    tvPkg.text = item.pkg
                    tvStatus.text = "TURBO OPTIMIZED"
                    tvStatus.setTextColor(android.graphics.Color.parseColor("#FFD600"))

                    view.setOnClickListener {
                        AlertDialog.Builder(this)
                            .setTitle(item.name)
                            .setMessage("Remove from Game Turbo?")
                            .setPositiveButton("Remove") { _, _ ->
                                val current = GameTurboManager.getTurboGames(this).toMutableSet()
                                current.remove(item.pkg)
                                GameTurboManager.saveTurboGames(this, current)
                                refreshGameList()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }

                    layoutGameAppsList.addView(view)
                }
            }
        }
    }

    private fun showGamePicker() {
        val currentGames = GameTurboManager.getTurboGames(this)
        thread {
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.category == ApplicationInfo.CATEGORY_GAME }
                .sortedBy { pm.getApplicationLabel(it).toString() }

            val appNames = installed.map { pm.getApplicationLabel(it).toString() }.toTypedArray()
            val appPkgs = installed.map { it.packageName }.toTypedArray()
            val checked = BooleanArray(installed.size) { i -> currentGames.contains(appPkgs[i]) }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                AlertDialog.Builder(this)
                    .setTitle("Select Games")
                    .setMultiChoiceItems(appNames, checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton("Save") { _, _ ->
                        val selected = mutableSetOf<String>()
                        for (i in checked.indices) {
                            if (checked[i]) selected.add(appPkgs[i])
                        }
                        GameTurboManager.saveTurboGames(this, selected)
                        refreshGameList()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
}
