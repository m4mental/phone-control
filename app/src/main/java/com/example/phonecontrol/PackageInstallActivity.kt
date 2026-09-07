package com.example.phonecontrol

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.concurrent.thread

/**
 * Universal System Default Package Installer Activity.
 * Catches VIEW & INSTALL_PACKAGE intents from any source (WhatsApp, Chrome, Telegram, File Managers).
 * Floats seamlessly as a transparent bottom sheet over the caller app.
 */
class PackageInstallActivity : AppCompatActivity() {

    private var currentUri: Uri? = null
    private var currentFileName: String = "package.apk"
    private var installSheetDialog: BottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder().build())
        } catch (e: Exception) {}

        val uri = intent.data
            ?: @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            ?: intent.clipData?.getItemAt(0)?.uri

        if (uri == null) {
            Toast.makeText(this, "No package URI received", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        currentUri = uri
        currentFileName = resolveFileName(uri)

        // Show pre-install bottom sheet
        showInitialInspectionSheet(uri, currentFileName)
    }

    private fun resolveFileName(uri: Uri): String {
        var name = "package.apk"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {}

        if (name == "package.apk" && uri.path != null) {
            val lastSegment = uri.lastPathSegment
            if (!lastSegment.isNullOrBlank()) {
                name = lastSegment.substringAfterLast("/")
            }
        }
        return name
    }

    private fun showInitialInspectionSheet(uri: Uri, fileName: String) {
        val dialogView = layoutInflater.inflate(R.layout.layout_dialog_install_sheet, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)
        dialog.setOnDismissListener {
            if (!isFinishing) finish()
        }
        installSheetDialog = dialog

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
        val tvTrackers = dialogView.findViewById<TextView>(R.id.tvInspectTrackers)
        val tvPermissions = dialogView.findViewById<TextView>(R.id.tvInspectPermissions)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnInspectCancel)
        val btnInstall = dialogView.findViewById<Button>(R.id.btnInspectInstall)

        tvAppName.text = fileName.substringBeforeLast(".")
        tvPkg.text = fileName
        tvInstallType.text = "🔍 Inspecting package & scanning trackers..."
        tvInstallType.setTextColor(Color.parseColor("#00E5FF"))
        btnInstall.isEnabled = false
        btnInstall.text = "Inspecting..."

        btnCancel.setOnClickListener {
            dialog.dismiss()
            finish()
        }

        dialog.show()

        thread {
            val inspection = PackageInstallerManager.inspectApk(this, uri, fileName)
            runOnUiThread {
                if (!isFinishing && dialog.isShowing) {
                    if (inspection != null) {
                        bindInspectionData(dialogView, inspection, uri, fileName)
                    } else {
                        tvInstallType.text = "❌ Failed to parse APK file. The package may be corrupted or encrypted."
                        tvInstallType.setTextColor(Color.parseColor("#FF5252"))
                        btnInstall.visibility = View.GONE
                        btnCancel.text = "Close"
                    }
                }
            }
        }
    }

    private fun bindInspectionData(
        dialogView: View,
        inspection: PackageInstallerManager.ApkInspection,
        uri: Uri,
        fileName: String
    ) {
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

        // Privacy & Trackers
        if (inspection.trackers.isEmpty()) {
            tvTrackers.text = "✅ Clean: No known ad/tracking SDKs detected."
            tvTrackers.setTextColor(Color.parseColor("#00E676"))
        } else {
            tvTrackers.text = "⚠️ Detected SDKs (${inspection.trackers.size}):\n• " + inspection.trackers.joinToString("\n• ")
            tvTrackers.setTextColor(Color.parseColor("#FFAB00"))
        }

        // Sensitive Permissions
        if (inspection.sensitivePermissions.isEmpty()) {
            tvPermissions.text = "✅ No critical sensitive permissions requested."
            tvPermissions.setTextColor(Color.parseColor("#00E676"))
        } else {
            tvPermissions.text = inspection.sensitivePermissions.joinToString(", ")
            tvPermissions.setTextColor(Color.parseColor("#E0E0E0"))
        }

        btnInstall.isEnabled = true
        btnInstall.text = "⚡ Install with Root"
        btnInstall.setOnClickListener {
            btnInstall.isEnabled = false
            btnInstall.text = "⚡ Installing with Root..."
            executeInstallation(dialogView, uri, fileName, inspection, forceReinstall = false)
        }
    }

    private fun executeInstallation(
        dialogView: View,
        uri: Uri,
        fileName: String,
        inspection: PackageInstallerManager.ApkInspection,
        forceReinstall: Boolean
    ) {
        val tvInstallType = dialogView.findViewById<TextView>(R.id.tvInspectInstallType)
        val btnInstall = dialogView.findViewById<Button>(R.id.btnInspectInstall)

        val statusMsg = if (forceReinstall) "⚡ Auto-backing up previous app data & force reinstalling..." else "⚡ Executing root install..."
        tvInstallType.text = statusMsg
        tvInstallType.setTextColor(Color.parseColor("#FFD54F"))

        thread {
            val result = PackageInstallerManager.installPackage(this, uri, fileName, forceReinstall) { progressText ->
                runOnUiThread {
                    tvInstallType.text = progressText
                }
            }

            runOnUiThread {
                if (result.success) {
                    installSheetDialog?.dismiss()
                    showPostInstallDialog(result.installedPackage ?: inspection.packageName, result.backupPath, fileName)
                } else {
                    installSheetDialog?.dismiss()
                    showConflictForceDialog(uri, fileName, inspection, result)
                }
            }
        }
    }

    private fun showConflictForceDialog(
        uri: Uri,
        fileName: String,
        inspection: PackageInstallerManager.ApkInspection,
        result: PackageInstallerManager.InstallResult
    ) {
        val dialogView = layoutInflater.inflate(R.layout.layout_dialog_conflict_force, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)
        dialog.setOnDismissListener {
            if (!isFinishing) finish()
        }

        val tvHeaderTitle = dialogView.findViewById<TextView>(R.id.tvConflictHeaderTitle)
        val tvReasonTitle = dialogView.findViewById<TextView>(R.id.tvConflictReasonTitle)
        val tvReasonExplanation = dialogView.findViewById<TextView>(R.id.tvConflictReasonExplanation)
        val tvAppInfo = dialogView.findViewById<TextView>(R.id.tvConflictAppInfo)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnConflictCancel)
        val btnProceed = dialogView.findViewById<Button>(R.id.btnConflictProceed)

        tvHeaderTitle.text = result.failureTitle ?: "Installation Stoppage Detected"
        tvReasonTitle.text = result.failureTitle ?: "Conflict Occurred"
        tvReasonExplanation.text = result.failureExplanation ?: result.message

        val oldVer = inspection.installedVersionName?.let { "v$it (Build ${inspection.installedVersionCode ?: 0})" } ?: "None"
        val newVer = "v${inspection.incomingVersionName} (Build ${inspection.incomingVersionCode})"
        tvAppInfo.text = "Installed: $oldVer  ➔  Incoming: $newVer"

        btnCancel.setOnClickListener {
            dialog.dismiss()
            finish()
        }

        btnProceed.setOnClickListener {
            dialog.dismiss()
            showInitialInspectionSheet(uri, fileName)
            // Immediately execute with forceReinstall
            installSheetDialog?.let { currentDialog ->
                val currentView = currentDialog.findViewById<View>(android.R.id.content) ?: dialogView
                executeInstallation(currentView, uri, fileName, inspection, forceReinstall = true)
            }
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
        dialog.setCancelable(true)
        dialog.setOnDismissListener {
            if (!isFinishing) finish()
        }

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
            finish()
        }

        btnFreeze.setOnClickListener {
            dialog.dismiss()
            if (pkg.isNotBlank()) {
                FreezerManager.addAppToFreezer(this, pkg)
                Toast.makeText(this, "❄️ $pkg added to Freezer and hibernated!", Toast.LENGTH_SHORT).show()
            }
            finish()
        }

        btnAppInfo.setOnClickListener {
            dialog.dismiss()
            if (pkg.isNotBlank()) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$pkg")
                }
                startActivity(intent)
            }
            finish()
        }

        btnDone.setOnClickListener {
            dialog.dismiss()
            finish()
        }

        dialog.show()
    }
}
