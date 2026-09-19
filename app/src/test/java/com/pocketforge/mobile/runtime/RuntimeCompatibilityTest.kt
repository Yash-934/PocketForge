package com.pocketforge.mobile.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCompatibilityTest {

    @Test
    fun supportsArm64WhenKernelAarch64AndAbiMatches() {
        val abis = arrayOf("arm64-v8a", "armeabi-v7a", "armeabi")
        val arch = "aarch64"
        assertTrue(RuntimeCompatibility.supportsArm64Runtime(abis, arch))
    }

    @Test
    fun supportsArm64WhenKernelArm64AndAbiMatches() {
        val abis = arrayOf("arm64-v8a")
        val arch = "arm64"
        assertTrue(RuntimeCompatibility.supportsArm64Runtime(abis, arch))
    }

    @Test
    fun rejectsX86_64OnlyDevice() {
        val abis = arrayOf("x86_64", "x86")
        val arch = "x86_64"
        assertFalse(RuntimeCompatibility.supportsArm64Runtime(abis, arch))
    }

    @Test
    fun rejectsArm64AbiWithX86Kernel() {
        val abis = arrayOf("arm64-v8a", "x86_64")
        val arch = "x86_64"
        assertFalse(RuntimeCompatibility.supportsArm64Runtime(abis, arch))
    }

    @Test
    fun rejectsEmptyAbis() {
        val abis = emptyArray<String>()
        val arch = "aarch64"
        assertFalse(RuntimeCompatibility.supportsArm64Runtime(abis, arch))
    }

    @Test
    fun rejectsNullInputs() {
        assertFalse(RuntimeCompatibility.supportsArm64Runtime(null, "aarch64"))
        assertFalse(RuntimeCompatibility.supportsArm64Runtime(arrayOf("arm64-v8a"), null))
        assertFalse(RuntimeCompatibility.supportsArm64Runtime(null, null))
    }
}
