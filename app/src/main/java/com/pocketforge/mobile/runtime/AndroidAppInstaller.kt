package com.pocketforge.mobile.runtime

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import java.io.File

/** Installs a locally-built APK through Android's package manager, without ADB. */
object AndroidAppInstaller {

    const val ACTION_INSTALL_RESULT = "com.pocketforge.mobile.action.APK_INSTALL_RESULT"
    const val EXTRA_APK_PATH = "com.pocketforge.mobile.extra.APK_PATH"

    fun install(context: Context, apk: File) {
        require(apk.isFile && apk.extension.equals("apk", ignoreCase = true) && apk.length() > 0L) {
            "A valid APK was not produced: ${apk.name}"
        }

        // On MIUI/HyperOS or older Android, direct PackageInstaller sessions often get blocked by vendor security frameworks.
        // Also fallback to Intent if device manufacturer enforces custom package installers.
        if (isMiuiDevice()) {
            installViaSystemIntent(context, apk)
            return
        }

        try {
            installViaPackageInstaller(context, apk)
        } catch (error: Throwable) {
            // If PackageInstaller session creation fails, fallback to system PackageInstaller Activity Intent
            installViaSystemIntent(context, apk)
        }
    }

    /**
     * Standard Android PackageInstaller session flow.
     */
    fun installViaPackageInstaller(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setSize(apk.length())
                setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                setOriginatingUid(Process.myUid())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
                }
            }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(apk.name, 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callback = Intent(context, AndroidAppInstallReceiver::class.java)
                    .setAction(ACTION_INSTALL_RESULT)
                    .setPackage(context.packageName)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                    .putExtra(EXTRA_APK_PATH, apk.absolutePath)
                val mutabilityFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
                val pending = PendingIntent.getBroadcast(
                    context, sessionId, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag,
                )
                session.commit(pending.intentSender)
            }
        } catch (error: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    /**
     * Installs the APK via the system PackageInstaller Intent (ACTION_VIEW / ACTION_INSTALL_PACKAGE) with FileProvider URI.
     * This opens the system standard installation dialog on all OEM roms (MIUI, Samsung, Vivo, Oppo, OnePlus, Pixel, etc.).
     */
    fun installViaSystemIntent(context: Context, apk: File) {
        // Ensure apk file is copied to an accessible cache directory if needed
        val fileToShare = if (apk.canRead()) {
            apk
        } else {
            val cacheCopy = File(context.cacheDir, "install_${apk.name}")
            apk.copyTo(cacheCopy, overwrite = true)
            cacheCopy
        }

        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", fileToShare)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Check if there is an activity to handle it, or fallback to ACTION_INSTALL_PACKAGE
        val resolve = context.packageManager.queryIntentActivities(intent, 0)
        if (resolve.isNotEmpty()) {
            context.startActivity(intent)
        } else {
            @Suppress("DEPRECATION")
            val fallbackIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        }
    }

    private fun isMiuiDevice(): Boolean = Build.MANUFACTURER.lowercase() in
        setOf("xiaomi", "redmi", "poco")
}
