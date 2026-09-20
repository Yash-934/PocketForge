package com.pocketforge.mobile.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

class AndroidAppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidAppInstaller.ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val apkPath = intent.getStringExtra(AndroidAppInstaller.EXTRA_APK_PATH)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val userAction = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            userAction?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            userAction?.let(context::startActivity)
            return
        }

        if (status != PackageInstaller.STATUS_SUCCESS) {
            val rawMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Installation failed"
            val readableMessage = when (status) {
                PackageInstaller.STATUS_FAILURE_BLOCKED ->
                    "Installation blocked. Allow 'Install unknown apps' in Android Settings for PocketForge, or disable Play Protect app blocking."
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                    "Incompatible APK architecture, minSdk, or missing device features ($rawMessage)."
                PackageInstaller.STATUS_FAILURE_CONFLICT ->
                    "Conflict with an existing installed version of this app with a different signature. Uninstall the previous version first."
                PackageInstaller.STATUS_FAILURE_STORAGE ->
                    "Insufficient storage space to install this app."
                PackageInstaller.STATUS_FAILURE_INVALID ->
                    "Corrupted or invalid APK ($rawMessage)."
                else -> {
                    if (rawMessage.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE", ignoreCase = true)) {
                        "Insufficient storage space to install app."
                    } else if (rawMessage.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) ||
                        rawMessage.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE", ignoreCase = true)) {
                        "Existing version conflicts with new APK signature. Uninstall the older version from your phone first."
                    } else if (rawMessage.contains("INSTALL_FAILED_OLDER_SDK", ignoreCase = true) ||
                        rawMessage.contains("INSTALL_FAILED_CPU_ABI_INCOMPATIBLE", ignoreCase = true)) {
                        "APK incompatible with device OS version or CPU architecture ($rawMessage)."
                    } else {
                        rawMessage
                    }
                }
            }

            // If PackageInstaller failed and we have the APK path, fall back to standard system View Intent
            if (!apkPath.isNullOrBlank()) {
                val apkFile = java.io.File(apkPath)
                if (apkFile.isFile && apkFile.length() > 0L) {
                    runCatching {
                        AndroidAppInstaller.installViaSystemIntent(context, apkFile)
                        return
                    }
                }
            }

            Toast.makeText(context, readableMessage, Toast.LENGTH_LONG).show()
            return
        }

        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                context.packageManager.getLaunchIntentSenderForPackage(packageName).sendIntent(
                    context, 0, null, null, null,
                )
            }.onFailure {
                Toast.makeText(context, "Installed $packageName. Open it from your launcher.", Toast.LENGTH_LONG).show()
            }
            return
        }
        context.packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        }
    }
}
