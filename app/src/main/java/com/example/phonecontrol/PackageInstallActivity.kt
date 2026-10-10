package com.example.phonecontrol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.content.res.ColorStateList
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlin.concurrent.thread

/**
 * Universal System Default Package Installer Activity.
 * Catches VIEW & INSTALL_PACKAGE intents from any source (WhatsApp, Chrome, Telegram, File Managers).
 * Floats seamlessly as a transparent bottom sheet over the caller app.
 */
class PackageInstallActivity : AppCompatActivity() {

    private var currentUri: Uri? = null
    private var currentFileName: String = "package.apk"
    private var currentAppName: String = "App"
    private var installSheetDialog: BottomSheetDialog? = null
    private var currentInspection: PackageInstallerManager.ApkInspection? = null
    @Volatile private var pendingConflictResult: PackageInstallerManager.InstallResult? = null

    @Volatile private var isInstalling = false
    @Volatile private var isBackgroundInstall = false

    companion object {
        private const val CHANNEL_ID_INSTALLER = "package_installer_channel"
        private const val NOTIFICATION_ID_PROGRESS = 2001
        private const val NOTIFICATION_ID_COMPLETE = 2002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent.action
        val isConflictReentry = intent.getBooleanExtra("extra_from_conflict", false)
        val isSupportedAction = action == Intent.ACTION_VIEW || 
                               action == Intent.ACTION_SEND || 
                               action == "android.intent.action.INSTALL_PACKAGE" ||
                               action == Intent.ACTION_INSTALL_PACKAGE
        if (!isConflictReentry && !isSupportedAction) {
            finish()
            return
        }

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val action = intent.action
        val isConflictReentry = intent.getBooleanExtra("extra_from_conflict", false)
        val isSupportedAction = action == Intent.ACTION_VIEW || 
                               action == Intent.ACTION_SEND || 
                               action == "android.intent.action.INSTALL_PACKAGE" ||
                               action == Intent.ACTION_INSTALL_PACKAGE
        if (!isConflictReentry && !isSupportedAction) {
            finish()
            return
        }

        if (isConflictReentry) {
            val res = pendingConflictResult
            val insp = currentInspection
            val uri = currentUri
            if (res != null && insp != null && uri != null) {
                showConflictForceDialog(uri, currentFileName, insp, res, null)
                return
            }
        }
        val uri = intent.data
            ?: @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            ?: intent.clipData?.getItemAt(0)?.uri ?: return
        currentUri = uri
        currentFileName = resolveFileName(uri)
        try {
            installSheetDialog?.dismiss()
        } catch (e: Exception) {}
        showInitialInspectionSheet(uri, currentFileName)
    }

    private fun resolveFileName(uri: Uri): String {
        var name: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {}

        if (name.isNullOrBlank() || !name.contains(".")) {
            val path = uri.path
            if (!path.isNullOrBlank()) {
                val candidate = path.substringAfterLast("/")
                if (candidate.contains(".")) {
                    name = candidate
                }
            }
        }

        if (name.isNullOrBlank() || !name.contains(".")) {
            val lastSegment = uri.lastPathSegment
            if (!lastSegment.isNullOrBlank() && lastSegment.contains(".")) {
                name = lastSegment.substringAfterLast("/")
            }
        }

        // Infer extension from MIME type if filename lacks an extension
        if (name.isNullOrBlank() || !name.contains(".")) {
            val mime = try { contentResolver.getType(uri) } catch (e: Exception) { null }
            val base = if (!name.isNullOrBlank()) name else "package"
            name = when {
                mime == "application/vnd.android.package-archive" -> "$base.apk"
                mime == "application/xapk-package-archive" || mime?.contains("xapk", ignoreCase = true) == true -> "$base.xapk"
                mime?.contains("apks", ignoreCase = true) == true -> "$base.apks"
                mime?.contains("apkm", ignoreCase = true) == true || mime == "application/vnd.android.package-bundle" -> "$base.apkm"
                mime == "application/zip" || mime == "application/x-zip-compressed" -> "$base.xapk"
                else -> "$base.apk"
            }
        }

        return name ?: "package.apk"
    }

    private fun showInitialInspectionSheet(uri: Uri, fileName: String) {
        val dialogView = layoutInflater.inflate(R.layout.layout_dialog_install_sheet, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)
        dialog.setOnDismissListener {
            if (isInstalling) {
                if (!isBackgroundInstall) {
                    moveToBackground(currentAppName)
                }
            } else if (!isFinishing) {
                finish()
            }
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
            val lower = fileName.lowercase()
            val inspection = PackageInstallerManager.inspectApk(this, uri, fileName)
            runOnUiThread {
                if (!isFinishing && !isDestroyed && dialog.isShowing) {
                    if (inspection != null) {
                        bindInspectionData(dialogView, inspection, uri, fileName)
                    } else {
                        val isSingleApk = lower.endsWith(".apk")
                        if (isSingleApk) {
                            tvInstallType.text = "⚡ Package detected (Direct Root Install ready)"
                            tvInstallType.setTextColor(Color.parseColor("#00E5FF"))
                            btnInstall.visibility = View.VISIBLE
                            btnInstall.isEnabled = true
                            btnInstall.text = "⚡ Direct Root Install"
                            btnInstall.setOnClickListener {
                                btnInstall.isEnabled = false
                                btnInstall.text = "⚡ Installing with Root..."
                                currentAppName = fileName.substringBeforeLast(".")
                                val fallback = PackageInstallerManager.createFallbackInspection(fileName)
                                executeInstallation(dialogView, uri, fileName, fallback, forceReinstall = false)
                            }
                        } else {
                            tvInstallType.text = "⚠️ No installable Android app or bundle found inside this archive."
                            tvInstallType.setTextColor(Color.parseColor("#FF5252"))
                            btnInstall.visibility = View.GONE
                            btnCancel.text = "Close"
                            Toast.makeText(this@PackageInstallActivity, "Not a valid APK or App Bundle", Toast.LENGTH_LONG).show()
                        }
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
        val cgSplits = dialogView.findViewById<ChipGroup>(R.id.cgInspectSplits)
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
        currentAppName = inspection.appName.ifBlank { fileName.substringBeforeLast(".") }
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

        // Splits / Sub-Packages Chip Selector
        val selectedSplits = mutableSetOf<String>()
        selectedSplits.addAll(inspection.splitNames)

        if (inspection.splitNames.isNotEmpty()) {
            layoutSplits.visibility = View.VISIBLE
            tvSplits.text = "${inspection.splitNames.size} module splits detected (Tap chips to include/exclude):"
            cgSplits?.removeAllViews()
            for (split in inspection.splitNames) {
                val chip = Chip(this).apply {
                    text = split
                    isCheckable = true
                    isChecked = true
                    chipStrokeWidth = 2f
                    setChipStrokeColor(ColorStateList.valueOf(Color.parseColor("#00E5FF")))
                    setTextColor(Color.WHITE)
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                            selectedSplits.add(split)
                            setChipStrokeColor(ColorStateList.valueOf(Color.parseColor("#00E5FF")))
                        } else {
                            selectedSplits.remove(split)
                            setChipStrokeColor(ColorStateList.valueOf(Color.parseColor("#555555")))
                        }
                    }
                }
                cgSplits?.addView(chip)
            }
        } else {
            layoutSplits.visibility = View.GONE
        }

        // Dual Version Comparison
        tvIncomingVer.text = "v${inspection.incomingVersionName} (Build ${inspection.incomingVersionCode})"
        if (inspection.isInstalled) {
            tvInstalledVer.text = "v${inspection.installedVersionName ?: "?"} (Build ${inspection.installedVersionCode ?: 0})"
            if (inspection.isSignatureMismatch) {
                tvInstallType.text = "⚠️ Signature Conflict (Mod/Re-signed APK) - Overwrite Required"
                tvInstallType.setTextColor(Color.parseColor("#FF9800"))
            } else if (inspection.isDowngrade) {
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
            if (inspection.isGhostPackage) {
                tvInstallType.text = "👻 Ghost Package in System Cache (Auto Clean & Install)"
                tvInstallType.setTextColor(Color.parseColor("#FFD54F"))
            } else {
                tvInstallType.text = "✨ Fresh App Installation"
                tvInstallType.setTextColor(Color.parseColor("#00E676"))
            }
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
        btnInstall.text = if (inspection.isGhostPackage) {
            "👻 Clean & Install with Root"
        } else if (inspection.isSignatureMismatch) {
            "⚡ Resolve Conflict & Install"
        } else {
            "⚡ Install with Root"
        }
        btnInstall.setOnClickListener {
            if (inspection.isGhostPackage) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("👻 Residual Ghost Package Detected")
                    .setMessage("Residual system cache records for '${inspection.packageName}' were detected from a previous uninstall. To proceed, leftovers must be uninstalled and cleared across all users.\n\nDo you want to proceed with cleanup and installation?")
                    .setPositiveButton("Proceed & Install") { _, _ ->
                        btnInstall.isEnabled = false
                        btnInstall.text = "⚡ Installing with Root..."
                        executeInstallation(dialogView, uri, fileName, inspection, forceReinstall = true, autoBackup = true, selectedSplits = selectedSplits)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else if (inspection.isSignatureMismatch) {
                val conflictRes = PackageInstallerManager.InstallResult(
                    success = false,
                    message = "Signature conflict detected between installed version and incoming APK",
                    failureTitle = "Signature Mismatch Conflict",
                    failureExplanation = "The incoming APK has a different signing certificate from the version currently installed on your phone (common with Modded, Patched, or different release-key APKs).\n\nAndroid blocks updates across differing signatures. To proceed safely, Phone Control will back up your app data, cleanly remove the older build, and install this build with root.",
                    conflictPackage = inspection.packageName,
                    rawOutput = "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Signatures do not match",
                    isSignatureConflict = true
                )
                showConflictForceDialog(uri, fileName, inspection, conflictRes, installSheetDialog)
            } else {
                btnInstall.isEnabled = false
                btnInstall.text = "⚡ Installing with Root..."
                executeInstallation(dialogView, uri, fileName, inspection, forceReinstall = false, autoBackup = true, selectedSplits = selectedSplits)
            }
        }
    }

    private fun executeInstallation(
        dialogView: View,
        uri: Uri,
        fileName: String,
        inspection: PackageInstallerManager.ApkInspection,
        forceReinstall: Boolean,
        autoBackup: Boolean = true,
        selectedSplits: Set<String>? = null
    ) {
        val tvInstallType = dialogView.findViewById<TextView>(R.id.tvInspectInstallType)
        val btnInstall = dialogView.findViewById<Button>(R.id.btnInspectInstall)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnInspectCancel)
        val layoutProgress = dialogView.findViewById<View>(R.id.layoutInstallProgress)
        val pbProgress = dialogView.findViewById<ProgressBar>(R.id.pbInstallProgress)
        val tvProgressText = dialogView.findViewById<TextView>(R.id.tvInstallProgressText)
        val tvProgressPercent = dialogView.findViewById<TextView>(R.id.tvInstallProgressPercent)
        val btnRunInBackground = dialogView.findViewById<View>(R.id.btnRunInBackground)

        currentInspection = inspection
        layoutProgress?.visibility = View.VISIBLE
        btnInstall.isEnabled = false
        btnCancel.isEnabled = false

        isInstalling = true
        isBackgroundInstall = false

        btnRunInBackground?.visibility = View.VISIBLE
        btnRunInBackground?.setOnClickListener {
            btnRunInBackground.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            moveToBackground(currentAppName)
        }

        val statusMsg = if (forceReinstall) {
            if (autoBackup) "⚡ Auto-backing up previous app data & force reinstalling..." else "⚡ Clean force reinstalling..."
        } else "⚡ Executing root install..."
        tvInstallType.text = statusMsg
        tvInstallType.setTextColor(Color.parseColor("#FFD54F"))

        thread {
            val result = PackageInstallerManager.installPackage(
                context = this,
                uri = uri,
                fileName = fileName,
                forceReinstall = forceReinstall,
                autoBackup = autoBackup,
                selectedSplits = selectedSplits
            ) { progressText, progressPercent ->
                runOnUiThread {
                    pbProgress?.progress = progressPercent
                    tvProgressPercent?.text = "$progressPercent%"
                    tvProgressText?.text = progressText
                    tvInstallType.text = progressText
                    if (isBackgroundInstall) {
                        updateProgressNotification(currentAppName, progressText, progressPercent)
                    }
                }
            }

            runOnUiThread {
                isInstalling = false
                val currentDialog = installSheetDialog

                if (!result.success && result.isBackupFailed) {
                    androidx.appcompat.app.AlertDialog.Builder(this@PackageInstallActivity)
                        .setTitle("⚠️ " + (result.failureTitle ?: "Data Backup Failed"))
                        .setMessage("${result.failureExplanation}\n\nDo you want to continue with a clean install (all existing app data will be lost), or cancel?")
                        .setPositiveButton("Continue Without Backup") { _, _ ->
                            executeInstallation(dialogView, uri, fileName, inspection, forceReinstall = true, autoBackup = false, selectedSplits = selectedSplits)
                        }
                        .setNegativeButton("Cancel") { _, _ ->
                            btnInstall.isEnabled = true
                            btnInstall.text = if (inspection.isGhostPackage) "👻 Clean & Install with Root" else "⚡ Install with Root"
                            btnCancel.isEnabled = true
                            layoutProgress?.visibility = View.GONE
                            tvInstallType.text = "Installation halted to protect app data."
                        }
                        .setCancelable(false)
                        .show()
                    return@runOnUiThread
                }

                if (isBackgroundInstall) {
                    showCompletionNotification(
                        appName = currentAppName,
                        packageName = result.installedPackage ?: inspection.packageName,
                        success = result.success,
                        failureTitle = result.failureTitle
                    )
                    if (result.success) {
                        Toast.makeText(this@PackageInstallActivity, "✅ $currentAppName installed successfully!", Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        Toast.makeText(this@PackageInstallActivity, "⚠️ $currentAppName install conflict: ${result.failureTitle}", Toast.LENGTH_LONG).show()
                        isBackgroundInstall = false
                        pendingConflictResult = result
                        bringInstallerToFront()
                        showConflictForceDialog(uri, fileName, inspection, result, null)
                    }
                } else {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    if (result.success) {
                        showPostInstallDialog(result.installedPackage ?: inspection.packageName, result.backupPath, fileName, currentDialog)
                    } else {
                        showConflictForceDialog(uri, fileName, inspection, result, currentDialog)
                    }
                }
            }
        }
    }

    private fun showConflictForceDialog(
        uri: Uri,
        fileName: String,
        inspection: PackageInstallerManager.ApkInspection,
        result: PackageInstallerManager.InstallResult,
        existingDialog: BottomSheetDialog? = null
    ) {
        if (isFinishing || isDestroyed) return

        val dialogView = layoutInflater.inflate(R.layout.layout_dialog_conflict_force, null)
        val dialog = existingDialog ?: BottomSheetDialog(this)
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)
        dialog.setOnDismissListener {
            if (isInstalling) {
                if (!isBackgroundInstall) {
                    moveToBackground(currentAppName)
                }
            } else if (!isFinishing) {
                finish()
            }
        }
        installSheetDialog = dialog

        dialogView.post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    dialogView.performHapticFeedback(HapticFeedbackConstants.REJECT)
                } else {
                    dialogView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
            } catch (_: Exception) {}
        }

        val tvHeaderTitle = dialogView.findViewById<TextView>(R.id.tvConflictHeaderTitle)
        val tvReasonTitle = dialogView.findViewById<TextView>(R.id.tvConflictReasonTitle)
        val tvReasonExplanation = dialogView.findViewById<TextView>(R.id.tvConflictReasonExplanation)
        val tvAppInfo = dialogView.findViewById<TextView>(R.id.tvConflictAppInfo)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnConflictCancel)
        val btnProceed = dialogView.findViewById<Button>(R.id.btnConflictProceed)

        val layoutBackupToggle = dialogView.findViewById<View>(R.id.layoutConflictBackupToggle)
        val cbAutoBackup = dialogView.findViewById<CheckBox>(R.id.cbConflictAutoBackup)
        val tvBackupTitle = dialogView.findViewById<TextView>(R.id.tvConflictBackupTitle)
        val tvBackupDesc = dialogView.findViewById<TextView>(R.id.tvConflictBackupDesc)

        val isDupPerm = result.failureTitle?.contains("Duplicate Permission", ignoreCase = true) == true ||
                        result.rawOutput.contains("INSTALL_FAILED_DUPLICATE_PERMISSION", ignoreCase = true)
        val isProviderConflict = result.failureTitle?.contains("Provider Conflict", ignoreCase = true) == true ||
                        result.rawOutput.contains("INSTALL_FAILED_CONFLICTING_PROVIDER", ignoreCase = true)
        val isSigConflict = result.failureTitle?.contains("Signature", ignoreCase = true) == true ||
                        result.rawOutput.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) ||
                        result.rawOutput.contains("signatures do not match", ignoreCase = true) ||
                        result.rawOutput.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE", ignoreCase = true)

        fun updateBackupUi(checked: Boolean) {
            cbAutoBackup?.isChecked = checked
            if (checked) {
                layoutBackupToggle?.setBackgroundColor(Color.parseColor("#162E20"))
                tvBackupTitle?.text = "🛡️ Auto-backup app data before overwrite"
                tvBackupTitle?.setTextColor(Color.parseColor("#00E676"))
                tvBackupDesc?.text = "Archives accounts, databases & settings to restore in 1-click. Uncheck for a fresh clean install."
                tvBackupDesc?.setTextColor(Color.parseColor("#C8E6C9"))
                btnProceed.text = if (isDupPerm || isProviderConflict) "⚡ Replace Conflict & Install (Safe)" else "⚡ Force Install (Safe)"
            } else {
                layoutBackupToggle?.setBackgroundColor(Color.parseColor("#1E1E24"))
                tvBackupTitle?.text = "⚠️ No Backup (Fresh Clean Install)"
                tvBackupTitle?.setTextColor(Color.parseColor("#FF9800"))
                tvBackupDesc?.text = if (isDupPerm || isProviderConflict) "Removes the conflicting package and installs this new build cleanly." else "Previous app data will be deleted. The new build will start in completely fresh default state."
                tvBackupDesc?.setTextColor(Color.parseColor("#FFE0B2"))
                btnProceed.text = if (isDupPerm || isProviderConflict) "⚡ Clean Reinstall & Replace Conflict" else "⚡ Force Install (Clean)"
            }
        }

        updateBackupUi(true)

        layoutBackupToggle?.setOnClickListener {
            val newChecked = !(cbAutoBackup?.isChecked ?: true)
            layoutBackupToggle.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            updateBackupUi(newChecked)
        }

        cbAutoBackup?.setOnCheckedChangeListener { _, isChecked ->
            updateBackupUi(isChecked)
        }

        tvHeaderTitle.text = result.failureTitle ?: "Installation Stoppage Detected"
        tvReasonTitle.text = result.failureTitle ?: "Conflict Occurred"
        tvReasonExplanation.text = if ((isDupPerm || isProviderConflict) && !result.conflictPackage.isNullOrBlank()) {
            "Another app installed on your phone ('${result.conflictPackage}') conflicts with this APK. Android blocks co-existence unless the conflicting app is replaced.\n\nTapping below will automatically uninstall the conflicting build and cleanly install this APK."
        } else if (isSigConflict) {
            "The APK has a different signing certificate or is a modified/re-signed build compared to the app already on your phone. Android requires replacing the previous installation to proceed.\n\nTapping below will back up existing data, purge the old conflicting build across all users, and install this build cleanly."
        } else {
            result.failureExplanation ?: result.message
        }

        val oldVer = inspection.installedVersionName?.let { "v$it (Build ${inspection.installedVersionCode ?: 0})" } ?: "None"
        val newVer = "v${inspection.incomingVersionName} (Build ${inspection.incomingVersionCode})"
        tvAppInfo.text = "Installed: $oldVer  ➔  Incoming: $newVer"

        btnCancel.setOnClickListener {
            btnCancel.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            dialog.dismiss()
            finish()
        }

        btnProceed.setOnClickListener {
            btnProceed.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val shouldBackup = cbAutoBackup?.isChecked ?: true
            btnCancel.isEnabled = false
            btnProceed.isEnabled = false
            btnProceed.text = if (isDupPerm) {
                if (shouldBackup) "⚡ Resolving Conflict & Installing..." else "⚡ Clean Replacing Conflict..."
            } else {
                if (shouldBackup) "⚡ Backing up & Force Installing..." else "⚡ Clean Force Installing..."
            }

            isInstalling = true
            isBackgroundInstall = false

            thread {
                val forceResult = PackageInstallerManager.installPackage(
                    this,
                    uri,
                    fileName,
                    forceReinstall = true,
                    autoBackup = shouldBackup
                ) { progressText, progressPercent ->
                    runOnUiThread {
                        btnProceed.text = progressText
                        if (isBackgroundInstall) {
                            updateProgressNotification(currentAppName, progressText, progressPercent)
                        }
                    }
                }

                runOnUiThread {
                    isInstalling = false
                    if (isBackgroundInstall) {
                        showCompletionNotification(
                            appName = currentAppName,
                            packageName = forceResult.installedPackage ?: inspection.packageName,
                            success = forceResult.success,
                            failureTitle = forceResult.failureTitle
                        )
                        if (forceResult.success) {
                            Toast.makeText(this@PackageInstallActivity, "✅ $currentAppName force-installed successfully!", Toast.LENGTH_LONG).show()
                            finish()
                        } else {
                            Toast.makeText(this@PackageInstallActivity, "❌ $currentAppName force install failed: ${forceResult.failureTitle}", Toast.LENGTH_LONG).show()
                            isBackgroundInstall = false
                            pendingConflictResult = forceResult
                            bringInstallerToFront()
                            showConflictForceDialog(uri, fileName, inspection, forceResult, null)
                        }
                    } else {
                        if (isFinishing || isDestroyed) return@runOnUiThread

                        if (!forceResult.success && forceResult.isBackupFailed) {
                            androidx.appcompat.app.AlertDialog.Builder(this@PackageInstallActivity)
                                .setTitle("⚠️ " + (forceResult.failureTitle ?: "Data Backup Failed"))
                                .setMessage("${forceResult.failureExplanation}\n\nDo you want to continue with a clean install (all existing app data will be deleted), or cancel?")
                                .setPositiveButton("Continue Without Backup") { _, _ ->
                                    cbAutoBackup?.isChecked = false
                                    btnProceed.performClick()
                                }
                                .setNegativeButton("Cancel") { _, _ ->
                                    btnCancel.isEnabled = true
                                    btnProceed.isEnabled = true
                                    btnProceed.text = "⚡ Force Install (Clean)"
                                }
                                .setCancelable(false)
                                .show()
                            return@runOnUiThread
                        }

                        if (forceResult.success) {
                            showPostInstallDialog(forceResult.installedPackage ?: inspection.packageName, forceResult.backupPath, fileName, dialog)
                        } else {
                            showConflictForceDialog(uri, fileName, inspection, forceResult, dialog)
                        }
                    }
                }
            }
        }

        if (!dialog.isShowing) {
            dialog.show()
        }
    }

    private fun showPostInstallDialog(
        packageName: String?,
        backupPath: String?,
        fileName: String,
        existingDialog: BottomSheetDialog? = null
    ) {
        val dialogView = layoutInflater.inflate(R.layout.layout_dialog_post_install, null)
        val dialog = existingDialog ?: BottomSheetDialog(this)
        dialog.setContentView(dialogView)
        dialog.setCancelable(true)
        dialog.setOnDismissListener {
            if (!isFinishing) finish()
        }
        installSheetDialog = dialog

        dialogView.post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    dialogView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                } else {
                    dialogView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            } catch (_: Exception) {}
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
            dialog.setOnDismissListener(null)
            dialog.dismiss()
            if (pkg.isNotBlank()) {
                FreezerManager.unfreezeApp(pkg)
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
                if (launchIntent != null) {
                    try {
                        startActivity(launchIntent)
                    } catch (e: Exception) {
                        launchAppSafely(pkg)
                    }
                } else {
                    launchAppSafely(pkg)
                }
            }
            finish()
        }

        btnFreeze.setOnClickListener {
            dialog.setOnDismissListener(null)
            dialog.dismiss()
            if (pkg.isNotBlank()) {
                FreezerManager.addAppToFreezer(this, pkg)
                Toast.makeText(this, "❄️ $pkg added to Freezer and hibernated!", Toast.LENGTH_SHORT).show()
            }
            finish()
        }

        btnAppInfo.setOnClickListener {
            dialog.setOnDismissListener(null)
            dialog.dismiss()
            if (pkg.isNotBlank()) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$pkg")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
            finish()
        }

        btnDone.setOnClickListener {
            dialog.setOnDismissListener(null)
            dialog.dismiss()
            finish()
        }

        dialog.show()
    }

    private fun launchAppSafely(pkg: String) {
        try {
            val amResult = ShellUtils.runAsRoot("cmd package resolve-activity --brief $pkg", 5000)
            val activityLine = amResult.output.lines().find { it.contains("/") && !it.contains("priority=") }?.trim()
            if (!activityLine.isNullOrBlank()) {
                ShellUtils.runAsRoot("am start --user 0 -n $activityLine")
            } else {
                ShellUtils.runAsRoot("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER --user 0 $pkg")
            }
        } catch (_: Exception) {}
    }

    private fun moveToBackground(appName: String) {
        if (!isInstalling || isBackgroundInstall) return
        isBackgroundInstall = true
        ensureNotificationChannel()
        Toast.makeText(this, "📥 Installing $appName in background...", Toast.LENGTH_SHORT).show()
        updateProgressNotification(appName, "⚡ Installing with Root...", 0)
        try {
            installSheetDialog?.dismiss()
        } catch (_: Exception) {}
        moveTaskToBack(true)
    }

    private fun bringInstallerToFront() {
        try {
            val reopenIntent = Intent(this, PackageInstallActivity::class.java).apply {
                data = currentUri
                putExtra("extra_from_conflict", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(reopenIntent)
        } catch (_: Exception) {}
        // Guaranteed root launch into foreground over any active app
        ShellUtils.fastCmd("am start -n com.example.phonecontrol/.PackageInstallActivity --activity-reorder-to-front")
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_INSTALLER,
                "Package Installer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress and completion status for background app installations"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun updateProgressNotification(appName: String, progressText: String, progressPercent: Int) {
        try {
            ensureNotificationChannel()
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val builder = NotificationCompat.Builder(this, CHANNEL_ID_INSTALLER)
                .setSmallIcon(R.drawable.ic_sub_installer)
                .setContentTitle("Installing $appName")
                .setContentText(progressText)
                .setProgress(100, progressPercent, progressPercent <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
            nm.notify(NOTIFICATION_ID_PROGRESS, builder.build())
        } catch (_: Exception) {}
    }

    private fun showCompletionNotification(
        appName: String,
        packageName: String,
        success: Boolean,
        failureTitle: String? = null
    ) {
        try {
            ensureNotificationChannel()
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.cancel(NOTIFICATION_ID_PROGRESS)

            if (success) {
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                val pendingIntent = if (launchIntent != null) {
                    PendingIntent.getActivity(
                        this,
                        0,
                        launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                } else null

                val builder = NotificationCompat.Builder(this, CHANNEL_ID_INSTALLER)
                    .setSmallIcon(R.drawable.ic_sub_installer)
                    .setContentTitle("✅ $appName Installed")
                    .setContentText("Tap to launch application")
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)

                if (pendingIntent != null) {
                    builder.setContentIntent(pendingIntent)
                    builder.addAction(android.R.drawable.ic_menu_send, "Open", pendingIntent)
                }
                nm.notify(NOTIFICATION_ID_COMPLETE, builder.build())
            } else {
                val reopenIntent = Intent(this, PackageInstallActivity::class.java).apply {
                    data = currentUri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    reopenIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val builder = NotificationCompat.Builder(this, CHANNEL_ID_INSTALLER)
                    .setSmallIcon(R.drawable.ic_sub_installer)
                    .setContentTitle("⚠️ $appName Install Conflict")
                    .setContentText(failureTitle ?: "Tap to resolve and force install")
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)

                nm.notify(NOTIFICATION_ID_COMPLETE, builder.build())
            }
        } catch (_: Exception) {}
    }
}
