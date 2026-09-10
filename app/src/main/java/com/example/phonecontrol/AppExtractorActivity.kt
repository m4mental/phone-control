package com.example.phonecontrol

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlin.concurrent.thread

class AppExtractorActivity : AppCompatActivity() {

    private lateinit var rvApps: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var etSearch: EditText
    private lateinit var tvCount: TextView
    private lateinit var chipGroupFilter: ChipGroup
    private lateinit var btnInstallApk: MaterialButton
    private lateinit var btnToggleMultiSelect: MaterialButton

    private lateinit var layoutBatchBar: LinearLayout
    private lateinit var tvBatchSelectedCount: TextView
    private lateinit var btnBatchClose: ImageButton
    private lateinit var btnBatchSelectAll: MaterialButton
    private lateinit var btnBatchExtract: MaterialButton

    private lateinit var adapter: AppExtractorAdapter

    private var allApps: List<AppExtractorManager.AppItem> = emptyList()
    private var hasLoadedSystemApps = false

    companion object {
        private const val REQUEST_PICK_APK = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_extractor)

        findViewById<MaterialToolbar>(R.id.toolbarExtractor).setNavigationOnClickListener {
            handleBackOrFinish()
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackOrFinish()
            }
        })

        rvApps = findViewById(R.id.rvApps)
        progressBar = findViewById(R.id.progressBarLoading)
        etSearch = findViewById(R.id.etSearchApp)
        tvCount = findViewById(R.id.tvAppCount)
        chipGroupFilter = findViewById(R.id.chipGroupFilter)
        btnInstallApk = findViewById(R.id.btnInstallApk)
        btnToggleMultiSelect = findViewById(R.id.btnToggleMultiSelect)

        layoutBatchBar = findViewById(R.id.layoutBatchBar)
        tvBatchSelectedCount = findViewById(R.id.tvBatchSelectedCount)
        btnBatchClose = findViewById(R.id.btnBatchClose)
        btnBatchSelectAll = findViewById(R.id.btnBatchSelectAll)
        btnBatchExtract = findViewById(R.id.btnBatchExtract)

        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.setHasFixedSize(true)
        rvApps.setItemViewCacheSize(25)
        adapter = AppExtractorAdapter(
            apps = emptyList(),
            onExtractClicked = { app -> promptExtractApp(app) },
            onSelectionChanged = { count -> updateBatchBar(count) },
            onMultiSelectModeChanged = { isMultiSelect -> updateMultiSelectUi(isMultiSelect) }
        )
        rvApps.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        chipGroupFilter.setOnCheckedChangeListener { _, checkedId ->
            val needsSystem = checkedId == R.id.chipFilterAll || checkedId == R.id.chipFilterSystem
            if (needsSystem && !hasLoadedSystemApps) {
                loadApps(includeSystem = true)
            } else {
                filterList()
            }
        }

        btnInstallApk.setOnClickListener {
            pickApkToInstall()
        }

        btnToggleMultiSelect.setOnClickListener {
            val newMode = !adapter.isMultiSelectMode
            adapter.setMultiSelectMode(newMode)
            updateMultiSelectUi(newMode)
        }

        btnBatchClose.setOnClickListener {
            adapter.setMultiSelectMode(false)
            updateMultiSelectUi(false)
        }

        btnBatchSelectAll.setOnClickListener {
            val currentSelected = adapter.selectedPackages.size
            if (currentSelected > 0 && currentSelected >= adapter.itemCount) {
                adapter.deselectAll()
            } else {
                adapter.selectAll()
            }
        }

        btnBatchExtract.setOnClickListener {
            val selectedApps = allApps.filter { adapter.selectedPackages.contains(it.packageName) }
            if (selectedApps.isNotEmpty()) {
                promptBatchExtract(selectedApps)
            } else {
                AppToast.show(this, "No apps selected")
            }
        }

        loadApps(includeSystem = false)
    }

    private fun handleBackOrFinish() {
        if (adapter.isMultiSelectMode) {
            adapter.setMultiSelectMode(false)
            updateMultiSelectUi(false)
        } else {
            finish()
        }
    }



    private fun updateMultiSelectUi(isMultiSelect: Boolean) {
        layoutBatchBar.visibility = if (isMultiSelect) View.VISIBLE else View.GONE
        btnToggleMultiSelect.text = if (isMultiSelect) "CANCEL" else "SELECT"
        btnToggleMultiSelect.setTextColor(if (isMultiSelect) android.graphics.Color.parseColor("#FF5252") else android.graphics.Color.parseColor("#00FF66"))
        btnToggleMultiSelect.strokeColor = android.content.res.ColorStateList.valueOf(
            if (isMultiSelect) android.graphics.Color.parseColor("#FF5252") else android.graphics.Color.parseColor("#00FF66")
        )
    }

    private fun updateBatchBar(selectedCount: Int) {
        tvBatchSelectedCount.text = "$selectedCount selected"
        btnBatchExtract.text = "Extract ($selectedCount)"
        btnBatchExtract.isEnabled = selectedCount > 0
        btnBatchSelectAll.text = if (selectedCount > 0 && selectedCount == adapter.itemCount) "Deselect All" else "Select All"
    }

    private fun loadApps(includeSystem: Boolean) {
        val cached = if (includeSystem) AppCacheManager.cachedAllApps else AppCacheManager.cachedUserApps
        if (cached != null) {
            allApps = cached
            if (includeSystem) hasLoadedSystemApps = true
            progressBar.visibility = View.GONE
            filterList()
        } else {
            progressBar.visibility = View.VISIBLE
            tvCount.text = "Scanning packages..."
        }

        thread {
            val apps = if (includeSystem) {
                AppCacheManager.getOrLoadAllApps(this, forceRefresh = (cached == null))
            } else {
                AppCacheManager.getOrLoadUserApps(this, forceRefresh = (cached == null))
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allApps = apps
                if (includeSystem) hasLoadedSystemApps = true
                progressBar.visibility = View.GONE
                filterList()
            }
        }
    }

    private fun filterList() {
        val q = etSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        val checkedFilterId = chipGroupFilter.checkedChipId

        var filtered = allApps

        // Filter category by Chip
        when (checkedFilterId) {
            R.id.chipFilterUser -> {
                filtered = filtered.filter { !it.isSystemApp }
            }
            R.id.chipFilterSystem -> {
                filtered = filtered.filter { it.isSystemApp }
            }
            R.id.chipFilterSplit -> {
                filtered = filtered.filter { it.isSplit }
            }
            // chipFilterAll -> no restriction
        }

        // Filter by Search Query
        if (q.isNotBlank()) {
            filtered = filtered.filter {
                it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }

        adapter.updateList(filtered)
        tvCount.text = "${filtered.size} apps found"
    }

    private fun pickApkToInstall() {
        try {
            val picker = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "application/vnd.android.package-archive",
                        "application/octet-stream",
                        "application/zip"
                    )
                )
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(picker, "Select APK / APKS to Install"), REQUEST_PICK_APK)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open file picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_APK && resultCode == RESULT_OK && data?.data != null) {
            val installIntent = Intent(this, PackageInstallActivity::class.java).apply {
                this.data = data.data
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(installIntent)
        }
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

    private fun promptBatchExtract(apps: List<AppExtractorManager.AppItem>) {
        val totalBytes = apps.sumOf { it.totalSize }
        val sizeStr = AppExtractorManager.formatSize(totalBytes)

        MaterialAlertDialogBuilder(this)
            .setTitle("📦 Batch Extract ${apps.size} Apps?")
            .setMessage("Selected: ${apps.size} applications\nTotal size: ~$sizeStr\n\nDestination:\n${AppExtractorManager.EXTRACT_DIR}/")
            .setPositiveButton("⚡ START BATCH") { _, _ ->
                performBatchExtraction(apps)
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

    private fun performBatchExtraction(apps: List<AppExtractorManager.AppItem>) {
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Batch Extracting (${apps.size} apps)")
            .setMessage("Starting...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        thread {
            var successCount = 0
            var failCount = 0

            for ((index, app) in apps.withIndex()) {
                runOnUiThread {
                    progressDialog.setMessage("Extracting (${index + 1}/${apps.size}):\n${app.appName}")
                }
                val res = AppExtractorManager.extractApp(this, app) { /* per-file progress */ }
                if (res.success) {
                    successCount++
                } else {
                    failCount++
                }
            }

            runOnUiThread {
                progressDialog.dismiss()
                adapter.setMultiSelectMode(false)
                updateMultiSelectUi(false)

                MaterialAlertDialogBuilder(this)
                    .setTitle("🎉 Batch Extraction Finished")
                    .setMessage("Successfully extracted: $successCount\nFailed: $failCount\n\nSaved to: ${AppExtractorManager.EXTRACT_DIR}/")
                    .setPositiveButton("OPEN FOLDER") { _, _ ->
                        openFolder(File(AppExtractorManager.EXTRACT_DIR))
                    }
                    .setNegativeButton("DONE", null)
                    .show()
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
            AppToast.show(this, "Sharing error: ${e.message}")
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
            AppToast.show(this, "Folder: ${folder.absolutePath}")
        }
    }
}
