package com.pocketforge.mobile.localmodel.gallery

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.ui.graphics.vector.ImageVector

enum class GalleryCategory(
    val label: String,
    val description: String,
    val icon: ImageVector,
) {
    ALL("All", "Browse all Hugging Face GGUF models", Icons.Default.AllInclusive),
    CODING("Coding", "Optimized for code generation, syntax, refactoring, and debugging", Icons.Default.Code),
    REASONING("Reasoning", "Chain-of-thought, math, and deep logical problem solving", Icons.Default.Psychology),
    FAST("Fast", "Ultra-lightweight models for instant latency and low memory", Icons.Default.Bolt),
    GENERAL("General", "Well-rounded assistants for multi-turn chat and everyday tasks", Icons.Default.Chat),
    DOWNLOADED("Downloaded", "Models saved locally on your device", Icons.Default.DownloadDone),
}

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
        val isPaused: Boolean = false,
    ) : DownloadState() {
        val fraction: Float
            get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
        val formattedSpeed: String
            get() {
                val mb = speedBytesPerSec / (1024.0 * 1024.0)
                val kb = speedBytesPerSec / 1024.0
                return when {
                    mb >= 1.0 -> String.format("%.1f MB/s", mb)
                    kb >= 1.0 -> String.format("%.0f KB/s", kb)
                    else -> "$speedBytesPerSec B/s"
                }
            }
        val formattedProgress: String
            get() {
                val downloadedMb = bytesDownloaded / (1024.0 * 1024.0)
                val totalMb = totalBytes / (1024.0 * 1024.0)
                return when {
                    totalBytes > 0 -> String.format("%.1f MB / %.1f MB", downloadedMb, totalMb)
                    else -> String.format("%.1f MB downloaded", downloadedMb)
                }
            }
    }
    data class Verifying(val progress: Float, val message: String) : DownloadState()
    data class Failed(val error: String) : DownloadState()
    data class Completed(val sha256: String) : DownloadState()
}

data class GalleryModelItem(
    val id: String,
    val name: String,
    val repoId: String,
    val fileName: String,
    val downloadUrl: String,
    val category: GalleryCategory,
    val sizeBytes: Long,
    val quantization: String,
    val contextLength: Int,
    val blockCount: Int,
    val architecture: String,
    val recommendedRamBytes: Long,
    val minimumRamBytes: Long,
    val description: String,
    val tags: List<String> = emptyList(),
    val expectedSha256: String? = null,
    val isLargeModel: Boolean = false,
) {
    val formattedSize: String
        get() {
            val gb = sizeBytes / (1024.0 * 1024.0 * 1024.0)
            val mb = sizeBytes / (1024.0 * 1024.0)
            return if (gb >= 1.0) String.format("%.2f GB", gb) else String.format("%.0f MB", mb)
        }

    val formattedRecommendedRam: String
        get() {
            val gb = recommendedRamBytes / (1024.0 * 1024.0 * 1024.0)
            return String.format("%.1f GB", gb)
        }

    val formattedMinimumRam: String
        get() {
            val gb = minimumRamBytes / (1024.0 * 1024.0 * 1024.0)
            return String.format("%.1f GB", gb)
        }
}
