package com.example.phonecontrol

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import kotlin.concurrent.thread

class AppExtractorActivity : AppCompatActivity() {

    private lateinit var rvApps: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var etSearch: EditText
    private lateinit var tvCount: TextView
    private lateinit var switchSystem: SwitchMaterial
    private lateinit var adapter: AppExtractorAdapter

    private var allApps: List<AppExtractorManager.AppItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_extractor)

        findViewById<MaterialToolbar>(R.id.toolbarExtractor).setNavigationOnClickListener { finish() }

        rvApps = findViewById(R.id.rvApps)
        progressBar = findViewById(R.id.progressBarLoading)
        etSearch = findViewById(R.id.etSearchApp)
        tvCount = findViewById(R.id.tvAppCount)
        switchSystem = findViewById(R.id.switchSystemApps)

        rvApps.layoutManager = LinearLayoutManager(this)
        adapter = AppExtractorAdapter(emptyList()) { app ->
            promptExtractApp(app)
        }
        rvApps.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s?.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        switchSystem.setOnCheckedChangeListener { _, isChecked ->
            loadApps(isChecked)
        }

        loadApps(false)
    }

    private fun loadApps(includeSystem: Boolean) {
        progressBar.visibility = View.VISIBLE
        tvCount.text = "Loading installed apps..."

        thread {
            val apps = AppExtractorManager.getInstalledApps(this, includeSystem)
            runOnUiThread {
                allApps = apps
                progressBar.visibility = View.GONE
                filterList(etSearch.text?.toString())
            }
        }
    }

    private fun filterList(query: String?) {
        val q = query?.trim()?.lowercase().orEmpty()
        val filtered = if (q.isBlank()) {
            allApps
        } else {
            allApps.filter {
                it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
        adapter.updateList(filtered)
        tvCount.text = "${filtered.size} apps found"
    }

    private fun promptExtractApp(app: AppExtractorManager.AppItem) {
        val format = if (app.isSplit) "Split Bundle (.apks)" else "Standalone (.apk)"
        val size = AppExtractorManager.formatSize(app.totalSize)

        MaterialAlertDialogBuilder(this)
            .setTitle("📦 Extract ${app.appName}?")
            .setMessage("Package: ${app.packageName}\nVersion: v${app.versionName} (${app.versionCode})\nFormat: $format\nTotal Size: $size\n\nDestination: ${AppExtractorManager.EXTRACT_DIR}/")
            .setPositiveButton("⚡ EXTRACT NOW") { _, _ ->
                performExtraction(app)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performExtraction(app: AppExtractorManager.AppItem) {
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Extracting ${app.appName}")
            .setMessage("Starting extraction...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        thread {
            val result = AppExtractorManager.extractApp(this, app) { progressMsg ->
                runOnUiThread {
                    progressDialog.setMessage(progressMsg)
                }
            }

            runOnUiThread {
                progressDialog.dismiss()
                if (result.success && result.outputPath != null) {
                    showExtractionSuccessDialog(app, result.outputPath)
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Extraction Failed")
                        .setMessage(result.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun showExtractionSuccessDialog(app: AppExtractorManager.AppItem, filePath: String) {
        val file = File(filePath)
        val sizeBytes = if (file.isDirectory) AppExtractorManager.getFolderSize(file) else file.length()
        val fileSize = AppExtractorManager.formatSize(sizeBytes)

        MaterialAlertDialogBuilder(this)
            .setTitle("🎉 Extraction Completed!")
            .setMessage("File saved to:\n$filePath\n\nSize: $fileSize")
            .setPositiveButton("SHARE") { _, _ ->
                shareFile(file)
            }
            .setNeutralButton("OPEN FOLDER") { _, _ ->
                openFolder(file.parentFile ?: File(AppExtractorManager.EXTRACT_DIR))
            }
            .setNegativeButton("DONE", null)
            .show()
    }

    private fun shareFile(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (file.name.endsWith(".apk")) "application/vnd.android.package-archive" else "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share ${file.name}"))
        } catch (e: Exception) {
            Toast.makeText(this, "Sharing error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openFolder(folder: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(folder.absolutePath), "resource/folder")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Folder: ${folder.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }
}
