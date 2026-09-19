package com.pocketforge.mobile.runtime

import android.os.Build

object RuntimeCompatibility {
    fun isDeviceSupported(): Boolean {
        return supportsArm64Runtime(
            supportedAbis = Build.SUPPORTED_ABIS ?: emptyArray(),
            osArchitecture = System.getProperty("os.arch"),
        )
    }

    fun supportsArm64Runtime(
        supportedAbis: Array<String>?,
        osArchitecture: String?,
    ): Boolean {
        if (supportedAbis == null || osArchitecture == null) return false
        val kernelIsArm64 =
            osArchitecture.equals("aarch64", ignoreCase = true) ||
            osArchitecture.equals("arm64", ignoreCase = true)

        return kernelIsArm64 &&
            supportedAbis.any {
                it.equals("arm64-v8a", ignoreCase = true)
            }
    }
}
