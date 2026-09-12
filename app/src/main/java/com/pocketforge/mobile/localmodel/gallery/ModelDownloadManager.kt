package com.pocketforge.mobile.localmodel.gallery

import android.content.Context
import com.pocketforge.mobile.localmodel.LocalModelMetadata
import com.pocketforge.mobile.localmodel.gguf.GgufParser
import com.pocketforge.mobile.localmodel.storage.LocalModelStore
import com.pocketforge.mobile.localmodel.util.Sha256Checksum
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ModelDownloadManager(
    private val context: Context,
    private val store: LocalModelStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val pauseFlags = ConcurrentHashMap<String, Boolean>()

    fun getDownloadState(modelId: String): DownloadState {
        return _downloadStates.value[modelId] ?: DownloadState.Idle
    }

    fun isDownloading(modelId: String): Boolean {
        val state = getDownloadState(modelId)
        return state is DownloadState.Downloading && !state.isPaused
    }

    /**
     * Initiates or resumes downloading of a model.
     */
    fun startDownload(item: GalleryModelItem) {
        if (isDownloading(item.id)) return

        val job = scope.launch {
            downloadModelInternal(item)
        }
        activeJobs[item.id] = job
    }

    /**
     * Pauses the download while preserving the partially downloaded file for later resumption.
     */
    fun pauseDownload(modelId: String) {
        pauseFlags[modelId] = true
        activeJobs[modelId]?.cancel()
        activeJobs.remove(modelId)

        val currentState = _downloadStates.value[modelId]
        if (currentState is DownloadState.Downloading) {
            updateState(modelId, currentState.copy(isPaused = true, speedBytesPerSec = 0L))
        }
    }

    /**
     * Cancels the download and removes any partial temporary file.
     */
    fun cancelDownload(modelId: String, fileName: String) {
        pauseFlags.remove(modelId)
        activeJobs[modelId]?.cancel()
        activeJobs.remove(modelId)

        val tempFile = File(modelsDir, "$fileName.download")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        updateState(modelId, DownloadState.Idle)
    }

    private suspend fun downloadModelInternal(item: GalleryModelItem) = withContext(Dispatchers.IO) {
        val modelId = item.id
        val fileName = item.fileName
        val tempFile = File(modelsDir, "$fileName.download")
        val finalFile = File(modelsDir, fileName)

        pauseFlags[modelId] = false

        var existingBytes = if (tempFile.exists()) tempFile.length() else 0L

        updateState(
            modelId,
            DownloadState.Downloading(
                bytesDownloaded = existingBytes,
                totalBytes = item.sizeBytes,
                speedBytesPerSec = 0L,
                isPaused = false,
            )
        )

        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            var targetUrl = item.downloadUrl
            var redirectCount = 0
            val maxRedirects = 6

            var responseCode: Int
            while (true) {
                val url = URL(targetUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 25_000
                    readTimeout = 40_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "PocketForge-GGUF-Engine/1.0 (Android; ARM64)")
                    setRequestProperty("Accept-Encoding", "identity")
                    if (existingBytes > 0L) {
                        setRequestProperty("Range", "bytes=$existingBytes-")
                    }
                }

                connection.connect()
                responseCode = connection.responseCode

                // Follow 301, 302, 303, 307, 308 redirects (common with Hugging Face LFS / Cloudflare CDN)
                if (responseCode in 300..399) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location != null && redirectCount < maxRedirects) {
                        redirectCount++
                        targetUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                            location
                        } else {
                            URL(URL(targetUrl), location).toString()
                        }
                        continue
                    } else {
                        throw IllegalStateException("Too many redirects or missing redirect location from Hugging Face")
                    }
                }
                break
            }

            val isPartialContent = (responseCode == HttpURLConnection.HTTP_PARTIAL)
            val isSuccess = (responseCode == HttpURLConnection.HTTP_OK) || isPartialContent

            if (!isSuccess) {
                if (responseCode == 416) {
                    // Range Not Satisfiable - partial download may already be complete or file changed on server
                    if (tempFile.length() >= item.sizeBytes && item.sizeBytes > 0) {
                        existingBytes = tempFile.length()
                    } else {
                        tempFile.delete()
                        existingBytes = 0L
                    }
                } else {
                    throw IllegalStateException("Server returned HTTP $responseCode: ${connection?.responseMessage}")
                }
            }

            val contentLength = connection?.contentLengthLong ?: -1L
            val totalBytes = when {
                isPartialContent && contentLength > 0 -> existingBytes + contentLength
                isSuccess && contentLength > 0 -> contentLength
                else -> item.sizeBytes
            }

            outputStream = FileOutputStream(tempFile, isPartialContent && existingBytes > 0L)
            inputStream = connection?.inputStream ?: throw IllegalStateException("Failed to open download stream")

            val buffer = ByteArray(64 * 1024)
            var bytesRead = 0
            var totalReadSinceStart = existingBytes
            var lastSpeedCheckTime = System.currentTimeMillis()
            var bytesSinceLastSpeedCheck = 0L
            var currentSpeed = 0L

            while (isActive) {
                val read = inputStream.read(buffer)
                if (read == -1) break
                bytesRead = read
                if (pauseFlags[modelId] == true) {
                    break
                }

                outputStream.write(buffer, 0, bytesRead)
                totalReadSinceStart += bytesRead
                bytesSinceLastSpeedCheck += bytesRead

                val now = System.currentTimeMillis()
                val elapsed = now - lastSpeedCheckTime
                if (elapsed >= 500) {
                    currentSpeed = ((bytesSinceLastSpeedCheck * 1000L) / elapsed.coerceAtLeast(1L))
                    bytesSinceLastSpeedCheck = 0L
                    lastSpeedCheckTime = now

                    updateState(
                        modelId,
                        DownloadState.Downloading(
                            bytesDownloaded = totalReadSinceStart,
                            totalBytes = totalBytes,
                            speedBytesPerSec = currentSpeed,
                            isPaused = false,
                        )
                    )
                }
            }

            outputStream.flush()

            if (pauseFlags[modelId] == true) {
                updateState(
                    modelId,
                    DownloadState.Downloading(
                        bytesDownloaded = totalReadSinceStart,
                        totalBytes = totalBytes,
                        speedBytesPerSec = 0L,
                        isPaused = true,
                    )
                )
                return@withContext
            }

            // === CRITICAL SAFETY & VERIFICATION PHASE ===
            // 1. Verify GGUF Magic Bytes and structure
            updateState(modelId, DownloadState.Verifying(0.1f, "Validating GGUF header & magic bytes..."))

            if (!GgufParser.isGgufFile(tempFile)) {
                tempFile.delete()
                updateState(modelId, DownloadState.Failed("Security Check Failed: Downloaded file is not a valid GGUF model binary."))
                return@withContext
            }

            // 2. Parse GGUF Metadata
            updateState(modelId, DownloadState.Verifying(0.35f, "Parsing GGUF metadata..."))
            val parsed = runCatching { GgufParser.parse(tempFile, loadTokens = false) }.getOrElse { e ->
                tempFile.delete()
                updateState(modelId, DownloadState.Failed("Corrupted GGUF file: ${e.message}"))
                return@withContext
            }

            // 3. Compute Streaming SHA-256 Checksum
            updateState(modelId, DownloadState.Verifying(0.60f, "Verifying SHA-256 checksum..."))
            val calculatedSha256 = Sha256Checksum.calculate(tempFile) { _, _, progress ->
                val fraction = 0.60f + (0.35f * progress)
                updateState(modelId, DownloadState.Verifying(fraction, "Computing SHA-256: ${(progress * 100).toInt()}%"))
            }

            // If an expected SHA-256 was configured, enforce match
            if (item.expectedSha256 != null && !calculatedSha256.equals(item.expectedSha256, ignoreCase = true)) {
                tempFile.delete()
                updateState(
                    modelId,
                    DownloadState.Failed("Integrity Mismatch: Expected SHA-256 does not match downloaded binary.")
                )
                return@withContext
            }

            // 4. Atomically promote to final model file
            if (finalFile.exists()) {
                finalFile.delete()
            }
            val renamed = tempFile.renameTo(finalFile)
            if (!renamed) {
                // Fallback copy if rename fails across file systems
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            // 5. Register in LocalModelStore WITHOUT silently activating!
            // CRITICAL: User must explicitly choose to load or activate the model.
            val metadata = parsed.metadata.copy(
                id = UUID.randomUUID().toString(),
                name = item.name,
                fileName = item.fileName,
                filePath = finalFile.absolutePath,
                fileSizeBytes = finalFile.length(),
                sha256 = calculatedSha256,
                isLoaded = false,
            )
            store.registerModel(metadata)

            updateState(modelId, DownloadState.Completed(calculatedSha256))

        } catch (e: CancellationException) {
            if (pauseFlags[modelId] == true) {
                // Already marked paused
            } else {
                updateState(modelId, DownloadState.Idle)
            }
        } catch (e: Exception) {
            updateState(modelId, DownloadState.Failed("Download failed: ${e.localizedMessage ?: e.message}"))
        } finally {
            runCatching { inputStream?.close() }
            runCatching { outputStream?.close() }
            runCatching { connection?.disconnect() }
            activeJobs.remove(modelId)
        }
    }

    private fun updateState(modelId: String, state: DownloadState) {
        val current = _downloadStates.value.toMutableMap()
        current[modelId] = state
        _downloadStates.value = current
    }

    companion object {
        @Volatile
        private var instance: ModelDownloadManager? = null

        fun getInstance(context: Context, store: LocalModelStore): ModelDownloadManager {
            return instance ?: synchronized(this) {
                instance ?: ModelDownloadManager(context.applicationContext, store).also { instance = it }
            }
        }
    }
}
