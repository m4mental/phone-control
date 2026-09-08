package com.example.phonecontrol

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import kotlin.concurrent.thread

class AdbShellActivity : AppCompatActivity() {

    private lateinit var tvOutput: TextView
    private lateinit var etInput: EditText
    private lateinit var scrollOutput: NestedScrollView
    
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    private val appInspectorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val pkg = result.data?.getStringExtra("package_name")
            if (pkg != null) {
                insertTextAtCursor(" $pkg ")
            }
        }
    }

    private val packagePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            handlePackageSelection(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_adb_shell)

        tvOutput = findViewById(R.id.tvAdbOutput)
        etInput = findViewById(R.id.etAdbInput)
        scrollOutput = findViewById(R.id.scrollOutput)
        
        // Hide UI until authenticated
        findViewById<View>(android.R.id.content).visibility = View.GONE
        showSecurityCheck()

        findViewById<MaterialToolbar>(R.id.toolbarAdb).setNavigationOnClickListener { finish() }

        // Copy & Share
        findViewById<ImageButton>(R.id.btnCopyOutput).setOnClickListener { copyOutputToClipboard() }
        findViewById<ImageButton>(R.id.btnShareLog).setOnClickListener { shareOutputLog() }

        findViewById<View>(R.id.btnShowTips).setOnClickListener { showCommandTips() }
        findViewById<View>(R.id.btnAppList).setOnClickListener {
            appInspectorLauncher.launch(Intent(this, AppInspectorActivity::class.java))
        }

        findViewById<View>(R.id.btnForceInstallToolbar).setOnClickListener {
            launchPackagePicker()
        }

        findViewById<ImageButton>(R.id.btnProcessMonitor).setOnClickListener { runSystemSnapshot() }
        findViewById<Chip>(R.id.chipLiveStats).setOnClickListener { runSystemSnapshot() }
        findViewById<Chip>(R.id.chipForceInstallApp).setOnClickListener { launchPackagePicker() }

        findViewById<ImageButton>(R.id.btnSendAdb).setOnClickListener {
            handleCommandSubmission(etInput.text.toString())
        }

        findViewById<ImageButton>(R.id.btnHistory).setOnClickListener {
            showHistoryDialog()
        }

        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                handleCommandSubmission(etInput.text.toString())
                true
            } else false
        }
        
        loadHistory()
        setupHackerBar()
        setupQuickChips()

        // Initial prompt
        tvOutput.text = ""
        appendColoredText("root@phonecontrol:~# ", Color.parseColor("#00E676"))
    }

    private fun launchPackagePicker() {
        packagePickerLauncher.launch(arrayOf(
            "application/vnd.android.package-archive",
            "application/zip",
            "application/octet-stream",
            "*/*"
        ))
    }

    private fun handlePackageSelection(uri: Uri) {
        var fileName = "package.apk"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {}

        appendColoredText("\n🔍 Inspecting package bundle: $fileName...\n", Color.parseColor("#00E5FF"))
        scrollOutput.post { scrollOutput.fullScroll(NestedScrollView.FOCUS_DOWN) }

        thread {
            val inspection = PackageInstallerManager.inspectApk(this, uri, fileName)
            runOnUiThread {
                if (inspection != null) {
                    showPreInstallInspectionDialog(uri, fileName, inspection)
                } else {
                    handlePackageInstallation(uri, forceReinstall = false)
                }
            }
        }
    }

    private fun showPreInstallInspectionDialog(
        uri: Uri,
        fileName: String,
        inspection: PackageInstallerManager.ApkInspection
    ) {
        val dialogView = layoutInflater.inflate(R.layout.layout_dialog_install_sheet, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(dialogView)

        val ivIcon = dialogView.findViewById<ImageView>(R.id.ivInspectIcon)
        val tvAppName = dialogView.findViewById<TextView>(R.id.tvInspectAppName)
        val tvPkg = dialogView.findViewById<TextView>(R.id.tvInspectPkgName)
        val tvSharedUid = dialogView.findViewById<TextView>(R.id.tvInspectSharedUid)
        val layoutSplits = dialogView.findViewById<View>(R.id.layoutInspectSplits)
        val tvSplits = dialogView.findViewById<TextView>(R.id.tvInspectSplits)
        val tvInstallType = dialogView.findViewById<TextView>(R.id.tvInspectInstallType)
        val tvInstalledVer = dialogView.findViewById<TextView>(R.id.tvInspectInstalledVersion)
        val tvIncomingVer = dialogView.findViewById<TextView>(R.id.tvInspectIncomingVersion)
        val tvSize = dialogView.findViewById<TextView>(R.id.tvInspectSize)
        val tvTargetSdk = dialogView.findViewById<TextView>(R.id.tvInspectTargetSdk)
        val tvMinSdk = dialogView.findViewById<TextView>(R.id.tvInspectMinSdk)
        val tvMaxSdk = dialogView.findViewById<TextView>(R.id.tvInspectMaxSdk)
        val tvPackageType = dialogView.findViewById<TextView>(R.id.tvInspectPackageType)
        val tvTrackers = dialogView.findViewById<TextView>(R.id.tvInspectTrackers)
        val tvPermissions = dialogView.findViewById<TextView>(R.id.tvInspectPermissions)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnInspectCancel)
        val btnInstall = dialogView.findViewById<Button>(R.id.btnInspectInstall)

        tvAppName.text = inspection.appName
        tvPkg.text = inspection.packageName
        if (inspection.icon != null) {
            ivIcon.setImageDrawable(inspection.icon)
        }

        // Shared User ID
        if (!inspection.sharedUserId.isNullOrBlank()) {
            tvSharedUid.visibility = View.VISIBLE
            tvSharedUid.text = "Shared UID: ${inspection.sharedUserId}"
        } else {
            tvSharedUid.visibility = View.GONE
        }

        // Splits / Sub-Packages
        if (inspection.splitNames.isNotEmpty()) {
            layoutSplits.visibility = View.VISIBLE
            tvSplits.text = inspection.splitNames.joinToString(", ")
        } else {
            layoutSplits.visibility = View.GONE
        }

        // Dual Version Comparison
        tvIncomingVer.text = "v${inspection.incomingVersionName} (Build ${inspection.incomingVersionCode})"
        if (inspection.isInstalled) {
            tvInstalledVer.text = "v${inspection.installedVersionName ?: "?"} (Build ${inspection.installedVersionCode ?: 0})"
            if (inspection.isDowngrade) {
                tvInstallType.text = "🔴 Version Downgrade (Older than installed build)"
                tvInstallType.setTextColor(Color.parseColor("#FF5252"))
            } else if (inspection.isSameVersion) {
                tvInstallType.text = "🔵 Re-install / Same Version Already Installed"
                tvInstallType.setTextColor(Color.parseColor("#00B0FF"))
            } else {
                tvInstallType.text = "🟢 App Upgrade: v${inspection.installedVersionName} ➔ v${inspection.incomingVersionName}"
                tvInstallType.setTextColor(Color.parseColor("#00E676"))
            }
        } else {
            tvInstalledVer.text = "Not Installed"
            tvInstallType.text = "✨ Fresh App Installation"
            tvInstallType.setTextColor(Color.parseColor("#00E676"))
        }

        tvSize.text = inspection.fileSizeFormatted
        tvPackageType?.text = inspection.packageTypeLabel
        tvTargetSdk.text = inspection.targetSdkLabel
        tvMinSdk.text = inspection.minSdkLabel
        tvMaxSdk?.text = inspection.maxSdkLabel
        if (inspection.maxSdk == null) {
            tvMaxSdk?.setTextColor(Color.parseColor("#00E676"))
        } else {
            tvMaxSdk?.setTextColor(Color.parseColor("#FFD54F"))
        }

        if (inspection.trackers.isEmpty()) {
            tvTrackers.text = "✅ Clean: No known ad/tracking SDKs detected."
            tvTrackers.setTextColor(Color.parseColor("#00E676"))
        } else {
            tvTrackers.text = "⚠️ Detected SDKs (${inspection.trackers.size}):\n• " + inspection.trackers.joinToString("\n• ")
            tvTrackers.setTextColor(Color.parseColor("#FFAB00"))
        }

        if (inspection.sensitivePermissions.isEmpty()) {
            tvPermissions.text = "✅ No critical sensitive permissions requested."
            tvPermissions.setTextColor(Color.parseColor("#00E676"))
        } else {
            tvPermissions.text = inspection.sensitivePermissions.joinToString(", ")
            tvPermissions.setTextColor(Color.parseColor("#E0E0E0"))
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
            appendColoredText("🚫 Installation cancelled by user: ${inspection.appName}\n", Color.parseColor("#888888"))
            appendColoredText("root@phonecontrol:~# ", Color.parseColor("#00E676"))
            scrollOutput.post { scrollOutput.fullScroll(NestedScrollView.FOCUS_DOWN) }
        }

        btnInstall.setOnClickListener {
            dialog.dismiss()
            handlePackageInstallation(uri, forceReinstall = false, inspection = inspection)
        }

        dialog.show()
    }

    private fun handlePackageInstallation(uri: Uri, forceReinstall: Boolean = false, autoBackup: Boolean = true, inspection: PackageInstallerManager.ApkInspection? = null) {
        var fileName = "package.apk"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {}

        val actionTag = if (forceReinstall) "FORCE RE-INSTALL (CLEAN OVERWRITE)" else "FORCE PACKAGE INSTALLER"
        appendColoredText("\n📦 [$actionTag] Starting: $fileName\n", Color.parseColor("#00E5FF"))
        Toast.makeText(this, "Installing $fileName...", Toast.LENGTH_SHORT).show()

        thread {
            val result = PackageInstallerManager.installPackage(this, uri, fileName, forceReinstall, autoBackup) { progressText ->
                runOnUiThread {
                    appendColoredText("➔ $progressText\n", Color.parseColor("#FFD700"))
                    scrollOutput.post { scrollOutput.fullScroll(NestedScrollView.FOCUS_DOWN) }
                }
            }

            runOnUiThread {
                if (result.success) {
                    appendColoredText("🎉 ${result.message}\n", Color.parseColor("#00E676"))
                    if (result.rawOutput.isNotBlank()) {
                        appendColoredText(result.rawOutput + "\n", Color.LTGRAY)
                    }
                    Toast.makeText(this, "Success: $fileName installed!", Toast.LENGTH_LONG).show()
                    showPostInstallDialog(result.installedPackage, result.backupPath, fileName)
                } else {
                    appendColoredText("❌ ${result.message}\n", Color.parseColor("#FF5252"))
                    if (result.rawOutput.isNotBlank()) {
                        appendColoredText(result.rawOutput + "\n", Color.LTGRAY)
                    }
                    Toast.makeText(this, "Install Failed: ${result.failureTitle ?: result.message}", Toast.LENGTH_LONG).show()

                    showConflictForceDialog(uri, fileName, inspection, result)
                }

                appendColoredText("root@phonecontrol:~# ", Color.parseColor("#00E676"))
                scrollOutput.post { scrollOutput.fullScroll(NestedScrollView.FOCUS_DOWN) }
            }
        }
    }

    private fun showConflictForceDialog(
        uri: Uri,
        fileName: String,
        inspection: PackageInstallerManager.ApkInspection?,
        result: PackageInstallerManager.InstallResult
    ) {
        val dialogView = layoutInflater.inflate(R.layout.layout_dialog_conflict_force, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(dialogView)

        val tvHeaderTitle = dialogView.findViewById<TextView>(R.id.tvConflictHeaderTitle)
        val tvReasonTitle = dialogView.findViewById<TextView>(R.id.tvConflictReasonTitle)
        val tvReasonExplanation = dialogView.findViewById<TextView>(R.id.tvConflictReasonExplanation)
        val tvAppInfo = dialogView.findViewById<TextView>(R.id.tvConflictAppInfo)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnConflictCancel)
        val btnProceed = dialogView.findViewById<Button>(R.id.btnConflictProceed)

        tvHeaderTitle.text = result.failureTitle ?: "Installation Stoppage Detected"
        tvReasonTitle.text = result.failureTitle ?: "Conflict Occurred"
        tvReasonExplanation.text = result.failureExplanation ?: result.message

        if (inspection != null) {
            val oldVer = inspection.installedVersionName?.let { "v$it (Build ${inspection.installedVersionCode ?: 0})" } ?: "None"
            val newVer = "v${inspection.incomingVersionName} (Build ${inspection.incomingVersionCode})"
            tvAppInfo.text = "Installed: $oldVer  ➔  Incoming: $newVer"
        } else {
            tvAppInfo.text = "Target: $fileName"
        }

        val layoutBackupToggle = dialogView.findViewById<View>(R.id.layoutConflictBackupToggle)
        val cbAutoBackup = dialogView.findViewById<CheckBox>(R.id.cbConflictAutoBackup)
        val tvBackupTitle = dialogView.findViewById<TextView>(R.id.tvConflictBackupTitle)
        val tvBackupDesc = dialogView.findViewById<TextView>(R.id.tvConflictBackupDesc)

        fun updateBackupUi(checked: Boolean) {
            cbAutoBackup?.isChecked = checked
            if (checked) {
                layoutBackupToggle?.setBackgroundColor(Color.parseColor("#162E20"))
                tvBackupTitle?.text = "🛡️ Auto-backup app data before overwrite"
                tvBackupTitle?.setTextColor(Color.parseColor("#00E676"))
                tvBackupDesc?.text = "Archives accounts, databases & settings to restore in 1-click. Uncheck for a fresh clean install."
                tvBackupDesc?.setTextColor(Color.parseColor("#C8E6C9"))
                btnProceed.text = "⚡ Force Install (Safe)"
            } else {
                layoutBackupToggle?.setBackgroundColor(Color.parseColor("#1E1E24"))
                tvBackupTitle?.text = "⚠️ No Backup (Fresh Clean Install)"
                tvBackupTitle?.setTextColor(Color.parseColor("#FF9800"))
                tvBackupDesc?.text = "Previous app data will be deleted. The new build will start in completely fresh default state."
                tvBackupDesc?.setTextColor(Color.parseColor("#FFE0B2"))
                btnProceed.text = "⚡ Force Install (Clean)"
            }
        }

        updateBackupUi(true)

        layoutBackupToggle?.setOnClickListener {
            val newChecked = !(cbAutoBackup?.isChecked ?: true)
            updateBackupUi(newChecked)
        }

        cbAutoBackup?.setOnCheckedChangeListener { _, isChecked ->
            updateBackupUi(isChecked)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
            appendColoredText("\n🚫 Force installation cancelled by user. Existing app preserved.\n", Color.parseColor("#FF5252"))
        }

        btnProceed.setOnClickListener {
            val shouldBackup = cbAutoBackup?.isChecked ?: true
            dialog.dismiss()
            val msg = if (shouldBackup) "safe force installation with auto-backup" else "clean force installation without backup"
            appendColoredText("\n⚡ User confirmed $msg...\n", Color.parseColor("#FFAB00"))
            handlePackageInstallation(uri, forceReinstall = true, autoBackup = shouldBackup, inspection = inspection)
        }

        dialog.show()
    }

    private fun showPostInstallDialog(
        packageName: String?,
        backupPath: String?,
        fileName: String
    ) {
        val dialogView = layoutInflater.inflate(R.layout.layout_dialog_post_install, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(dialogView)

        val ivIcon = dialogView.findViewById<ImageView>(R.id.ivPostIcon)
        val tvAppName = dialogView.findViewById<TextView>(R.id.tvPostAppName)
        val tvPkg = dialogView.findViewById<TextView>(R.id.tvPostPkgName)
        val tvVersion = dialogView.findViewById<TextView>(R.id.tvPostVersion)
        val layoutBackup = dialogView.findViewById<View>(R.id.layoutPostBackupNotice)
        val tvBackupNotice = dialogView.findViewById<TextView>(R.id.tvPostBackupNotice)
        val btnRestore = dialogView.findViewById<Button>(R.id.btnPostRestoreData)
        val btnOpen = dialogView.findViewById<Button>(R.id.btnPostOpen)
        val btnFreeze = dialogView.findViewById<Button>(R.id.btnPostFreeze)
        val btnAppInfo = dialogView.findViewById<Button>(R.id.btnPostAppInfo)
        val btnDone = dialogView.findViewById<Button>(R.id.btnPostDone)

        val pkg = packageName ?: ""
        tvPkg.text = if (pkg.isNotBlank()) pkg else fileName

        if (pkg.isNotBlank()) {
            try {
                val appInfo = packageManager.getApplicationInfo(pkg, 0)
                val label = packageManager.getApplicationLabel(appInfo).toString()
                val icon = packageManager.getApplicationIcon(appInfo)
                val pkgInfo = packageManager.getPackageInfo(pkg, 0)
                tvAppName.text = label
                ivIcon.setImageDrawable(icon)
                tvVersion.text = "v${pkgInfo.versionName ?: "1.0"}"
            } catch (e: Exception) {
                tvAppName.text = fileName.substringBeforeLast(".")
                tvVersion.text = "Installed"
            }
        } else {
            tvAppName.text = fileName.substringBeforeLast(".")
            tvVersion.text = "Installed"
        }

        if (!backupPath.isNullOrBlank()) {
            layoutBackup.visibility = View.VISIBLE
            tvBackupNotice.text = "App data was safeguarded to:\n$backupPath\nTap below to restore your accounts, databases, and preferences in 1-click."
            btnRestore.visibility = View.VISIBLE
            btnRestore.setOnClickListener {
                btnRestore.isEnabled = false
                btnRestore.text = "Restoring data..."
                Toast.makeText(this, "Restoring data for $pkg...", Toast.LENGTH_SHORT).show()
                thread {
                    val restored = PackageInstallerManager.restoreAppData(this, pkg, backupPath)
                    runOnUiThread {
                        if (restored) {
                            Toast.makeText(this, "✅ App data restored successfully!", Toast.LENGTH_LONG).show()
                            btnRestore.text = "✅ Data Restored"
                            btnRestore.setBackgroundColor(Color.parseColor("#388E3C"))
                            layoutBackup.visibility = View.GONE
                        } else {
                            Toast.makeText(this, "❌ Data restoration failed", Toast.LENGTH_LONG).show()
                            btnRestore.isEnabled = true
                            btnRestore.text = "🔄 Retry Data Restore"
                        }
                    }
                }
            }
        } else {
            layoutBackup.visibility = View.GONE
            btnRestore.visibility = View.GONE
        }

        btnOpen.setOnClickListener {
            dialog.dismiss()
            if (pkg.isNotBlank()) {
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                } else {
                    FreezerManager.unfreezeApp(pkg)
                    Toast.makeText(this, "Launching $pkg...", Toast.LENGTH_SHORT).show()
                    ShellUtils.runAsRoot("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
                }
            }
        }

        btnFreeze.setOnClickListener {
            dialog.dismiss()
            if (pkg.isNotBlank()) {
                FreezerManager.addAppToFreezer(this, pkg)
                Toast.makeText(this, "❄️ $pkg added to Freezer and hibernated!", Toast.LENGTH_SHORT).show()
            }
        }

        btnAppInfo.setOnClickListener {
            dialog.dismiss()
            if (pkg.isNotBlank()) {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$pkg")
                }
                startActivity(intent)
            }
        }

        btnDone.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }



    private fun setupHackerBar() {
        findViewById<Button>(R.id.btnKeyHistoryUp).setOnClickListener {
            if (commandHistory.isNotEmpty()) {
                if (historyIndex < commandHistory.size - 1) {
                    historyIndex++
                    etInput.setText(commandHistory[historyIndex])
                    etInput.setSelection(etInput.text.length)
                }
            }
        }

        findViewById<Button>(R.id.btnKeyHistoryDown).setOnClickListener {
            if (commandHistory.isNotEmpty()) {
                if (historyIndex > 0) {
                    historyIndex--
                    etInput.setText(commandHistory[historyIndex])
                    etInput.setSelection(etInput.text.length)
                } else if (historyIndex == 0) {
                    historyIndex = -1
                    etInput.setText("")
                }
            }
        }

        findViewById<Button>(R.id.btnKeyPipe).setOnClickListener { insertTextAtCursor(" | ") }
        findViewById<Button>(R.id.btnKeySlash).setOnClickListener { insertTextAtCursor("/") }
        findViewById<Button>(R.id.btnKeyGrep).setOnClickListener { insertTextAtCursor("grep ") }
        findViewById<Button>(R.id.btnKeyPs).setOnClickListener { insertTextAtCursor("ps -A ") }
        findViewById<Button>(R.id.btnKeyDumpsys).setOnClickListener { insertTextAtCursor("dumpsys ") }
        findViewById<Button>(R.id.btnKeyLogcat).setOnClickListener { insertTextAtCursor("logcat -d ") }
        findViewById<Button>(R.id.btnKeyClear).setOnClickListener {
            tvOutput.text = ""
            appendColoredText("root@phonecontrol:~# ", Color.parseColor("#00E676"))
        }
    }

    private fun setupQuickChips() {
        findViewById<Chip>(R.id.chipGetProp).setOnClickListener {
            executeCommand("getprop ro.product.model; getprop ro.build.version.release; getprop ro.board.platform")
        }
        findViewById<Chip>(R.id.chipLs).setOnClickListener {
            executeCommand("ls -l /data/data | head -n 20")
        }
        findViewById<Chip>(R.id.chipUptime).setOnClickListener {
            executeCommand("uptime; cat /proc/loadavg")
        }
        findViewById<Chip>(R.id.chipEnableApp).setOnClickListener {
            showEnableAppDialog()
        }
        findViewById<Chip>(R.id.chipDisableApp).setOnClickListener {
            showDisableAppDialog()
        }
        findViewById<Chip>(R.id.chipDeleteApp).setOnClickListener {
            showDeleteAppDialog()
        }
        findViewById<Chip>(R.id.chipUfs).setOnClickListener {
            executeCommand("df -h /data /system /vendor /metadata")
        }
        findViewById<Chip>(R.id.chipNet).setOnClickListener {
            executeCommand("ip route; ping -c 3 8.8.8.8")
        }
    }

    private fun insertTextAtCursor(text: String) {
        val start = etInput.selectionStart.coerceAtLeast(0)
        val end = etInput.selectionEnd.coerceAtLeast(0)
        etInput.text.replace(Math.min(start, end), Math.max(start, end), text, 0, text.length)
    }

    private fun handleCommandSubmission(cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return

        if (trimmed.equals("clear", ignoreCase = true)) {
            tvOutput.text = ""
            appendColoredText("root@phonecontrol:~# ", Color.parseColor("#00E676"))
            etInput.setText("")
            return
        }

        executeCommand(trimmed)
    }

    private fun runSystemSnapshot() {
        appendColoredText("\n⚡ Fetching Live Real-Time System Snapshot...\n", Color.parseColor("#00E5FF"))
        thread {
            val cpuFreq = ShellUtils.runAsRoot("cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq").output
            val memInfo = ShellUtils.runAsRoot("free -m").output
            val topApps = ShellUtils.runAsRoot("top -b -n 1 -m 6").output

            runOnUiThread {
                appendColoredText("----------------------------------------\n", Color.DKGRAY)
                appendColoredText("📊 CPU CORE FREQUENCIES (kHz):\n", Color.parseColor("#00E5FF"))
                val lines = cpuFreq.split("\n").filter { it.isNotBlank() }
                for ((i, f) in lines.withIndex()) {
                    val mhz = (f.trim().toLongOrNull() ?: 0) / 1000
                    appendColoredText("  Core #$i: ${mhz} MHz\n", Color.GREEN)
                }

                appendColoredText("\n🧠 Memory State:\n$memInfo\n", Color.LTGRAY)
                appendColoredText("\n🔥 Live Real-Time Top Processes:\n$topApps\n", Color.parseColor("#FFD700"))
                appendColoredText("----------------------------------------\n", Color.DKGRAY)
                appendColoredText("root@phonecontrol:~# ", Color.parseColor("#00E676"))

                scrollOutput.post { scrollOutput.fullScroll(NestedScrollView.FOCUS_DOWN) }
            }
        }
    }

    private fun showEnableAppDialog() {
        val et = EditText(this).apply { hint = "com.package.name" }
        AlertDialog.Builder(this)
            .setTitle("Force Enable App")
            .setMessage("Enter the package name of the app to enable/unhide.")
            .setView(et)
            .setPositiveButton("ENABLE") { _, _ ->
                val pkg = et.text.toString().trim()
                if (pkg.isNotEmpty()) {
                    executeCommand("pm default-state --user 0 $pkg; pm unhide $pkg; pm enable $pkg; pm unsuspend $pkg")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDisableAppDialog() {
        val et = EditText(this).apply { hint = "com.package.name" }
        AlertDialog.Builder(this)
            .setTitle("Force Disable/Freeze App")
            .setMessage("Enter package name to completely freeze and hide.")
            .setView(et)
            .setPositiveButton("DISABLE") { _, _ ->
                val pkg = et.text.toString().trim()
                if (pkg.isNotEmpty()) {
                    executeCommand("pm disable-user --user 0 $pkg; pm hide $pkg")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteAppDialog() {
        val et = EditText(this).apply { hint = "com.package.name" }
        AlertDialog.Builder(this)
            .setTitle("Force Delete (Uninstall)")
            .setMessage("WARNING: This will uninstall the app for the current user!")
            .setView(et)
            .setPositiveButton("DELETE") { _, _ ->
                val pkg = et.text.toString().trim()
                if (pkg.isNotEmpty()) {
                    executeCommand("pm uninstall -k --user 0 $pkg")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCommandTips() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_adb_tips, findViewById(android.R.id.content), false)
        val container = view.findViewById<LinearLayout>(R.id.layoutTipsContainer)

        val tips = listOf(
            "dumpsys cpuinfo | head -n 10" to "Power Monitor: Top apps by CPU/Battery usage.",
            "pm enable <pkg>" to "Enables a disabled/frozen app.",
            "pm disable-user --user 0 <pkg>" to "Completely freezes/disables an app.",
            "pm hide <pkg>" to "Hides app from launcher (remains installed).",
            "pm unhide <pkg>" to "Makes a hidden app visible again.",
            "am force-stop <pkg>" to "Kills all app processes and services.",
            "pm clear <pkg>" to "Resets all app data (Login, settings, etc).",
            "pm list packages -3" to "Lists all installed user applications.",
            "dumpsys window | grep mCurrentFocus" to "Shows current foreground activity.",
            "wm density <dpi>" to "Changes screen DPI (e.g. 400).",
            "wm size <width>x<height>" to "Changes screen resolution.",
            "df -h" to "Shows storage space usage for all partitions.",
            "logcat -d" to "Displays latest system log buffer.",
            "reboot recovery" to "Restarts the phone into Recovery mode."
        )

        for ((cmd, desc) in tips) {
            val tv = TextView(this).apply {
                text = "⚡ $cmd\n$desc"
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(20, 20, 20, 20)
                setBackgroundColor(Color.parseColor("#1C1C20"))
                setOnClickListener {
                    insertTextAtCursor(cmd)
                    dialog.dismiss()
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
            container.addView(tv, params)
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun showSecurityCheck() {
        val prefs = getSharedPreferences("app_security", MODE_PRIVATE)
        val isBioEnabled = prefs.getBoolean("biometric_terminal", false)

        if (!isBioEnabled) {
            findViewById<View>(android.R.id.content).visibility = View.VISIBLE
            return
        }

        val biometricManager = BiometricManager.from(this)
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS) {
            val executor = ContextCompat.getMainExecutor(this)
            val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    findViewById<View>(android.R.id.content).visibility = View.VISIBLE
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(this@AdbShellActivity, "Terminal Locked: $errString", Toast.LENGTH_SHORT).show()
                    finish()
                }
            })

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Root Terminal Security")
                .setSubtitle("Authenticate fingerprint to access superuser shell")
                .setNegativeButtonText("Cancel")
                .build()

            prompt.authenticate(promptInfo)
        } else {
            findViewById<View>(android.R.id.content).visibility = View.VISIBLE
        }
    }

    private fun copyOutputToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Terminal Log", tvOutput.text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Terminal output copied!", Toast.LENGTH_SHORT).show()
    }

    private fun shareOutputLog() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "PhoneControl Terminal Output")
            putExtra(Intent.EXTRA_TEXT, tvOutput.text.toString())
        }
        startActivity(Intent.createChooser(shareIntent, "Share Terminal Log via"))
    }

    private fun loadHistory() {
        val prefs = getSharedPreferences("adb_history", MODE_PRIVATE)
        val historySet = prefs.getStringSet("history", emptySet())
        if (historySet != null) {
            commandHistory.addAll(historySet)
        }
    }

    private fun saveToHistory(cmd: String) {
        commandHistory.remove(cmd)
        commandHistory.add(0, cmd)
        if (commandHistory.size > 50) commandHistory.removeAt(commandHistory.size - 1)
        val prefs = getSharedPreferences("adb_history", MODE_PRIVATE)
        prefs.edit().putStringSet("history", commandHistory.toSet()).apply()
        historyIndex = -1
    }

    private fun showHistoryDialog() {
        if (commandHistory.isEmpty()) {
            Toast.makeText(this, "No history available", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Command History (${commandHistory.size})")
            .setItems(commandHistory.toTypedArray()) { _, which ->
                etInput.setText(commandHistory[which])
                etInput.setSelection(etInput.text.length)
                etInput.requestFocus()
            }
            .setNeutralButton("Clear All") { _, _ ->
                commandHistory.clear()
                getSharedPreferences("adb_history", MODE_PRIVATE).edit().clear().apply()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun executeCommand(cmd: String) {
        if (cmd.isBlank()) return
        
        var commandToRun = cmd.trim()
        if (commandToRun.startsWith("adb shell ")) {
            commandToRun = commandToRun.substring("adb shell ".length)
        } else if (commandToRun.startsWith("adb ")) {
            commandToRun = commandToRun.substring("adb ".length)
        }

        saveToHistory(cmd)
        etInput.setText("")
        appendColoredText("\nroot@phonecontrol:~# $commandToRun\n", Color.parseColor("#00E676"))
        
        thread {
            val result = ShellUtils.runAsRoot(commandToRun)
            runOnUiThread {
                if (result.output.isNotBlank()) {
                    appendColoredText(result.output + "\n", Color.LTGRAY)
                } else {
                    if (result.exitCode == 0) {
                        appendColoredText("[✓ Command executed with exit code 0]\n", Color.parseColor("#81C784"))
                    }
                }

                if (result.exitCode != 0 && result.exitCode != -1) {
                    appendColoredText("[✗ Error: Exit code ${result.exitCode}]\n", Color.parseColor("#FF5252"))
                }
                
                appendColoredText("root@phonecontrol:~# ", Color.parseColor("#00E676"))
                
                scrollOutput.post {
                    scrollOutput.fullScroll(NestedScrollView.FOCUS_DOWN)
                }
            }
        }
    }

    private fun appendColoredText(text: String, color: Int) {
        val builder = SpannableStringBuilder(text)
        builder.setSpan(ForegroundColorSpan(color), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvOutput.append(builder)
    }
}
