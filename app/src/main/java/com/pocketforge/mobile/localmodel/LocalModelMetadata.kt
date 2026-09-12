package com.pocketforge.mobile.localmodel

import org.json.JSONObject
import java.util.Locale

data class LocalModelMetadata(
    val id: String,
    val name: String,
    val fileName: String,
    val filePath: String,
    val fileSizeBytes: Long,
    val sha256: String,
    val architecture: String = "llama",
    val quantization: String = "Unknown",
    val contextLength: Int = 2048,
    val embeddingLength: Int = 2048,
    val blockCount: Int = 16,
    val headCount: Int = 16,
    val vocabSize: Int = 32000,
    val tensorCount: Long = 0L,
    val importedAtMillis: Long = System.currentTimeMillis(),
    val isLoaded: Boolean = false,
) {
    val formattedSize: String
        get() {
            val kb = fileSizeBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
                mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
                else -> String.format(Locale.US, "%.0f KB", kb)
            }
        }

    val formattedShortSha: String
        get() = if (sha256.length > 16) "${sha256.take(8)}...${sha256.takeLast(8)}" else sha256

    val estimatedRamBytes: Long
        get() {
            // Weights memory + KV cache buffer (2 * layers * embd * ctx * 2 bytes for fp16) + 128MB working buffer
            val kvCache = 2L * blockCount.coerceAtLeast(1) * embeddingLength.coerceAtLeast(512) * contextLength.coerceAtLeast(1024) * 2L
            val overhead = 128L * 1024 * 1024
            return fileSizeBytes + kvCache + overhead
        }

    val formattedEstimatedRam: String
        get() {
            val mb = estimatedRamBytes / (1024.0 * 1024.0)
            val gb = mb / 1024.0
            return if (gb >= 1.0) String.format(Locale.US, "%.1f GB", gb) else String.format(Locale.US, "%.0f MB", mb)
        }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("fileName", fileName)
            put("filePath", filePath)
            put("fileSizeBytes", fileSizeBytes)
            put("sha256", sha256)
            put("architecture", architecture)
            put("quantization", quantization)
            put("contextLength", contextLength)
            put("embeddingLength", embeddingLength)
            put("blockCount", blockCount)
            put("headCount", headCount)
            put("vocabSize", vocabSize)
            put("tensorCount", tensorCount)
            put("importedAtMillis", importedAtMillis)
            put("isLoaded", isLoaded)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): LocalModelMetadata {
            return LocalModelMetadata(
                id = json.optString("id"),
                name = json.optString("name"),
                fileName = json.optString("fileName"),
                filePath = json.optString("filePath"),
                fileSizeBytes = json.optLong("fileSizeBytes"),
                sha256 = json.optString("sha256"),
                architecture = json.optString("architecture", "llama"),
                quantization = json.optString("quantization", "Unknown"),
                contextLength = json.optInt("contextLength", 2048),
                embeddingLength = json.optInt("embeddingLength", 2048),
                blockCount = json.optInt("blockCount", 16),
                headCount = json.optInt("headCount", 16),
                vocabSize = json.optInt("vocabSize", 32000),
                tensorCount = json.optLong("tensorCount", 0L),
                importedAtMillis = json.optLong("importedAtMillis", System.currentTimeMillis()),
                isLoaded = json.optBoolean("isLoaded", false),
            )
        }
    }
}
