package com.pocketforge.mobile.localmodel.util

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import java.io.File
import java.util.Locale

data class DeviceResourceReport(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val availableStorageBytes: Long,
    val estimatedRequiredRamBytes: Long,
    val isRamSufficient: Boolean,
    val isStorageSufficient: Boolean,
    val ramStatusMessage: String,
    val storageStatusMessage: String,
) {
    val formattedTotalRam: String
        get() = formatBytes(totalRamBytes)

    val formattedAvailableRam: String
        get() = formatBytes(availableRamBytes)

    val formattedAvailableStorage: String
        get() = formatBytes(availableStorageBytes)

    val formattedRequiredRam: String
        get() = formatBytes(estimatedRequiredRamBytes)

    companion object {
        fun formatBytes(bytes: Long): String {
            val mb = bytes / (1024.0 * 1024.0)
            val gb = mb / 1024.0
            return if (gb >= 1.0) String.format(Locale.US, "%.1f GB", gb) else String.format(Locale.US, "%.0f MB", mb)
        }
    }
}

object DeviceResourceChecker {

    fun checkResources(
        context: Context,
        modelFile: File? = null,
        estimatedRamBytes: Long = 1024L * 1024 * 1024,
    ): DeviceResourceReport {
        // 1. RAM Check
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)

        val totalRam = memoryInfo.totalMem
        val availRam = memoryInfo.availMem
        val isRamSufficient = availRam >= (estimatedRamBytes * 0.75).toLong()

        val ramMsg = if (isRamSufficient) {
            "RAM OK: ${DeviceResourceReport.formatBytes(availRam)} free of ${DeviceResourceReport.formatBytes(totalRam)}"
        } else {
            "Low RAM Warning: Model needs ~${DeviceResourceReport.formatBytes(estimatedRamBytes)}, but only ${DeviceResourceReport.formatBytes(availRam)} is free"
        }

        // 2. Storage Check
        val targetDir = modelFile?.parentFile ?: context.filesDir
        val statFs = runCatching { StatFs(targetDir.absolutePath) }.getOrNull()
        val availableStorage = statFs?.availableBytes ?: (1024L * 1024 * 1024)
        val minStorageRequired = 300L * 1024 * 1024 // 300 MB minimum free storage
        val isStorageSufficient = availableStorage >= minStorageRequired

        val storageMsg = if (isStorageSufficient) {
            "Storage OK: ${DeviceResourceReport.formatBytes(availableStorage)} free"
        } else {
            "Low Storage Warning: Only ${DeviceResourceReport.formatBytes(availableStorage)} free on device"
        }

        return DeviceResourceReport(
            totalRamBytes = totalRam,
            availableRamBytes = availRam,
            availableStorageBytes = availableStorage,
            estimatedRequiredRamBytes = estimatedRamBytes,
            isRamSufficient = isRamSufficient,
            isStorageSufficient = isStorageSufficient,
            ramStatusMessage = ramMsg,
            storageStatusMessage = storageMsg,
        )
    }
}
