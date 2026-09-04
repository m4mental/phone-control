package com.example.phonecontrol

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.concurrent.thread

class StandbyGuardActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_standby_guard)

        findViewById<MaterialToolbar>(R.id.toolbarStandbyGuard).setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val swStandby = findViewById<SwitchMaterial>(R.id.switchStandbyGuard)

        val isStandbyActive = prefs.getBoolean("standby_guard_active", false)
        swStandby.isChecked = isStandbyActive
        swStandby.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("standby_guard_active", isChecked).apply()
            Toast.makeText(this, if (isChecked) "Standby Bucket Guard Enabled" else "Standby Guard Disabled", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.cardWhitelistStandby).setOnClickListener {
            startActivity(Intent(this, DozeWhitelistActivity::class.java))
        }

        // Apply Restricted Buckets to Background Apps immediately
        findViewById<Button>(R.id.btnApplyRestrictedNow).setOnClickListener {
            thread {
                val result = ShellUtils.runAsRoot("pm list packages -3 | cut -d ':' -f2")
                val packages = result.output.split("\n").filter { it.isNotBlank() }
                val allSafeApps = MultitaskingManager.getUserWhitelist(this) + MultitaskingManager.protectedApps

                var restrictedCount = 0
                for (pkg in packages) {
                    if (!allSafeApps.contains(pkg)) {
                        ShellUtils.fastCmd("am set-standby-bucket $pkg restricted 2>/dev/null")
                        restrictedCount++
                    } else {
                        ShellUtils.fastCmd("am set-standby-bucket $pkg active 2>/dev/null")
                        MultitaskingManager.grantFullExemption(pkg)
                    }
                }
                runOnUiThread {
                    Toast.makeText(this, "$restrictedCount background apps restricted, whitelisted apps kept Active!", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Reset All Buckets to Active
        findViewById<Button>(R.id.btnResetBucketsActive).setOnClickListener {
            thread {
                ShellUtils.runAsRoot("for pkg in \$(pm list packages -3 | cut -d ':' -f2); do am set-standby-bucket \$pkg active 2>/dev/null; done")
                runOnUiThread {
                    Toast.makeText(this, "All apps restored to ACTIVE bucket", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
