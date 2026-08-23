package com.example.phonecontrol

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class VaultActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vault)

        findViewById<MaterialToolbar>(R.id.toolbarVault).setNavigationOnClickListener { finish() }

        findViewById<View>(R.id.cardGoToBackup).setOnClickListener {
            startActivity(Intent(this, AppBackupListActivity::class.java))
        }

        findViewById<View>(R.id.cardGoToRestore).setOnClickListener {
            startActivity(Intent(this, AppRestoreListActivity::class.java))
        }
        
        // Ensure storage is ready on hub entry
        BackupManager.ensureStorageStructure()
    }
}
