package com.pocketforge.mobile.localmodel.storage

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import com.pocketforge.mobile.localmodel.LocalModelMetadata
import com.pocketforge.mobile.localmodel.gguf.GgufParser
import com.pocketforge.mobile.localmodel.util.Sha256Checksum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class LocalModelStore(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("pocket_local_models", Context.MODE_PRIVATE)
    private val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    private val _models = MutableStateFlow<List<LocalModelMetadata>>(emptyList())
    val models: StateFlow<List<LocalModelMetadata>> = _models.asStateFlow()

    private val _activeModelId = MutableStateFlow<String?>(null)
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        val raw = prefs.getString(KEY_MODELS_LIST, null)
        val list = mutableListOf<LocalModelMetadata>()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val model = LocalModelMetadata.fromJson(obj)
                    // Check if file still exists on disk
                    if (File(model.filePath).isFile) {
                        list.add(model)
                    }
                }
            }
        }
        val savedActiveId = prefs.getString(KEY_ACTIVE_MODEL_ID, null)
        _models.value = list
        _activeModelId.value = if (list.any { it.id == savedActiveId }) savedActiveId else list.firstOrNull()?.id
    }

    private fun saveToDisk(list: List<LocalModelMetadata>, activeId: String?) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs.edit()
            .putString(KEY_MODELS_LIST, array.toString())
            .putString(KEY_ACTIVE_MODEL_ID, activeId)
            .apply()
        _models.value = list
        _activeModelId.value = activeId
    }

    suspend fun importModelFromUri(
        uri: Uri,
        onProgress: (fraction: Float, status: String) -> Unit,
    ): Result<LocalModelMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val contentResolver = context.contentResolver
            val displayName = resolveFileName(uri) ?: "imported_model_${System.currentTimeMillis()}.gguf"
            val totalBytes = resolveFileSize(uri)

            onProgress(0.05f, "Preparing import for $displayName...")

            val targetFile = File(modelsDir, "${UUID.randomUUID()}_$displayName")
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    var bytesWritten = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        bytesWritten += read
                        if (totalBytes > 0) {
                            val fraction = 0.05f + (0.75f * (bytesWritten.toFloat() / totalBytes.toFloat()))
                            val writtenMb = bytesWritten / (1024 * 1024)
                            val totalMb = totalBytes / (1024 * 1024)
                            onProgress(fraction.coerceIn(0.05f, 0.80f), "Importing model: $writtenMb MB / $totalMb MB")
                        }
                    }
                }
            } ?: throw IllegalStateException("Cannot read selected file URI")

            onProgress(0.82f, "Verifying GGUF header...")

            if (!GgufParser.isGgufFile(targetFile)) {
                targetFile.delete()
                throw IllegalArgumentException("The selected file is not a valid GGUF model file.")
            }

            onProgress(0.90f, "Parsing model metadata...")
            val parsed = GgufParser.parse(targetFile, loadTokens = false)
            val sha256 = Sha256Checksum.calculate(targetFile)

            val modelMetadata = parsed.metadata.copy(
                id = UUID.randomUUID().toString(),
                name = displayName.removeSuffix(".gguf"),
                fileName = displayName,
                filePath = targetFile.absolutePath,
                fileSizeBytes = targetFile.length(),
                sha256 = sha256,
                isLoaded = false,
            )

            val currentList = _models.value.toMutableList()
            currentList.add(modelMetadata)
            // Safety: Do NOT silently activate imported models. Keep existing activeId.
            saveToDisk(currentList, activeId = _activeModelId.value)

            onProgress(1.0f, "Model imported successfully!")
            modelMetadata
        }
    }

    fun registerModel(metadata: LocalModelMetadata) {
        val currentList = _models.value.filter { it.id != metadata.id }.toMutableList()
        currentList.add(metadata)
        // Safety: Do NOT silently activate downloaded models. Keep existing activeId.
        saveToDisk(currentList, activeId = _activeModelId.value)
    }

    fun setActiveModel(id: String?) {
        val currentList = _models.value
        val validId = if (currentList.any { it.id == id }) id else null
        saveToDisk(currentList, validId)
    }

    fun deleteModel(id: String): Boolean {
        val currentList = _models.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1) {
            val model = currentList.removeAt(index)
            File(model.filePath).delete()
            val newActiveId = if (_activeModelId.value == id) currentList.firstOrNull()?.id else _activeModelId.value
            saveToDisk(currentList, newActiveId)
            return true
        }
        return false
    }

    suspend fun verifyModelSha256(id: String, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val model = _models.value.firstOrNull { it.id == id } ?: return@withContext false
        val file = File(model.filePath)
        if (!file.isFile) return@withContext false
        Sha256Checksum.verify(file, model.sha256, onProgress)
    }

    fun getActiveModel(): LocalModelMetadata? {
        val id = _activeModelId.value ?: return null
        return _models.value.firstOrNull { it.id == id }
    }

    private fun resolveFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        }
        return uri.lastPathSegment
    }

    private fun resolveFileSize(uri: Uri): Long {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0) return cursor.getLong(index)
                }
            }
        }
        return -1L
    }

    companion object {
        private const val KEY_MODELS_LIST = "models_list_json"
        private const val KEY_ACTIVE_MODEL_ID = "active_model_id"
    }
}
