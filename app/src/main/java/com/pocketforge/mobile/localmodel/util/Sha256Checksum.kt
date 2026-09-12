package com.pocketforge.mobile.localmodel.util

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

object Sha256Checksum {

    fun calculate(
        file: File,
        onProgress: ((bytesRead: Long, totalBytes: Long, fraction: Float) -> Unit)? = null,
    ): String {
        require(file.isFile) { "File does not exist: ${file.absolutePath}" }
        val totalBytes = file.length()
        return FileInputStream(file).use { input ->
            calculateFromStream(input, totalBytes, onProgress)
        }
    }

    fun calculateFromStream(
        input: InputStream,
        totalBytes: Long = -1L,
        onProgress: ((bytesRead: Long, totalBytes: Long, fraction: Float) -> Unit)? = null,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var bytesRead = 0L

        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
            bytesRead += read
            if (totalBytes > 0 && onProgress != null) {
                val fraction = (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                onProgress(bytesRead, totalBytes, fraction)
            }
        }

        return bytesToHex(digest.digest())
    }

    fun verify(file: File, expectedHash: String, onProgress: ((Float) -> Unit)? = null): Boolean {
        if (expectedHash.isBlank()) return false
        val computed = calculate(file) { _, _, fraction -> onProgress?.invoke(fraction) }
        return computed.equals(expectedHash.trim(), ignoreCase = true)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        val hexArray = "0123456789abcdef".toCharArray()
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = hexArray[v ushr 4]
            hexChars[i * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }
}
