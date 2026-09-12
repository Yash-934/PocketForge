package com.pocketforge.mobile.localmodel.engine

import android.content.Context
import android.net.Uri
import com.pocketforge.mobile.localmodel.LocalModelMetadata
import com.pocketforge.mobile.localmodel.inference.GgufInferenceEngine
import com.pocketforge.mobile.localmodel.inference.LlamaBackend
import com.pocketforge.mobile.localmodel.storage.LocalModelStore
import com.pocketforge.mobile.localmodel.util.DeviceResourceChecker
import com.pocketforge.mobile.localmodel.util.DeviceResourceReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import java.io.File

class LocalModelEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = LocalModelStore(context)
    val downloadManager = com.pocketforge.mobile.localmodel.gallery.ModelDownloadManager.getInstance(context, store)
    val llamaBackend = LlamaBackend(context)
    val ggufEngine = GgufInferenceEngine()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _activeModel = MutableStateFlow<LocalModelMetadata?>(null)
    val activeModel: StateFlow<LocalModelMetadata?> = _activeModel.asStateFlow()

    private val _generationStatus = MutableStateFlow<String?>(null)
    val generationStatus: StateFlow<String?> = _generationStatus.asStateFlow()

    init {
        // Observe store changes and load active model only if explicitly selected and verified
        scope.launch {
            store.models.collect { models ->
                val activeId = store.activeModelId.value
                val model = models.firstOrNull { it.id == activeId }
                _activeModel.value = model
                if (model != null && !_isLoaded.value && activeId != null) {
                    val file = File(model.filePath)
                    if (file.isFile && com.pocketforge.mobile.localmodel.gguf.GgufParser.isGgufFile(file)) {
                        loadModel(model)
                    }
                }
            }
        }
    }

    suspend fun loadModel(metadata: LocalModelMetadata): Result<Unit> {
        val file = File(metadata.filePath)
        if (!file.isFile) {
            return Result.failure(IllegalStateException("Model file does not exist at ${file.absolutePath}"))
        }

        // Security check: Refuse to activate unverified, non-GGUF or arbitrary executables
        if (!com.pocketforge.mobile.localmodel.gguf.GgufParser.isGgufFile(file)) {
            return Result.failure(SecurityException("Security verification failed: File is not a valid GGUF model binary."))
        }

        // Check resources
        val report = DeviceResourceChecker.checkResources(context, file, metadata.estimatedRamBytes)
        if (!report.isStorageSufficient) {
            return Result.failure(IllegalStateException(report.storageStatusMessage))
        }

        val result = if (llamaBackend.isAvailable) {
            // Llama CLI backend available
            _isLoaded.value = true
            _activeModel.value = metadata
            store.setActiveModel(metadata.id)
            Result.success(Unit)
        } else {
            // Use embedded GGUF engine
            ggufEngine.load(file).onSuccess {
                _isLoaded.value = true
                _activeModel.value = metadata
                store.setActiveModel(metadata.id)
            }
        }

        return result
    }

    suspend fun unloadModel() {
        cancelGeneration()
        ggufEngine.unload()
        _isLoaded.value = false
        _activeModel.value = null
    }

    fun cancelGeneration() {
        llamaBackend.cancel()
        ggufEngine.cancel()
        _isGenerating.value = false
        _generationStatus.value = "Cancelled"
    }

    fun generateTokens(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
    ): Flow<String> = flow {
        val model = _activeModel.value ?: throw IllegalStateException("No local model loaded")
        val file = File(model.filePath)
        if (!file.isFile) throw IllegalStateException("Model file missing from storage")

        _isGenerating.value = true
        _generationStatus.value = "Generating..."

        val flow = if (llamaBackend.isAvailable) {
            llamaBackend.generate(file, prompt, maxTokens, temperature)
        } else {
            ggufEngine.generate(prompt, maxTokens, temperature)
        }

        flow.collect { token ->
            emit(token)
        }
    }.onCompletion {
        _isGenerating.value = false
        _generationStatus.value = null
    }

    fun checkResources(metadata: LocalModelMetadata? = null): DeviceResourceReport {
        val target = metadata ?: _activeModel.value
        val file = target?.let { File(it.filePath) }
        val estRam = target?.estimatedRamBytes ?: (1024L * 1024 * 1024)
        return DeviceResourceChecker.checkResources(context, file, estRam)
    }

    suspend fun importModel(
        uri: Uri,
        onProgress: (fraction: Float, status: String) -> Unit,
    ): Result<LocalModelMetadata> {
        // Import and register without silent activation
        return store.importModelFromUri(uri, onProgress)
    }

    fun deleteModel(id: String): Boolean {
        if (_activeModel.value?.id == id) {
            cancelGeneration()
            ggufEngine.unload()
            _isLoaded.value = false
            _activeModel.value = null
        }
        return store.deleteModel(id)
    }

    suspend fun verifyModelSha256(id: String, onProgress: (Float) -> Unit): Boolean {
        return store.verifyModelSha256(id, onProgress)
    }

    companion object {
        @Volatile
        private var instance: LocalModelEngine? = null

        fun getInstance(context: Context): LocalModelEngine {
            return instance ?: synchronized(this) {
                instance ?: LocalModelEngine(context.applicationContext).also { instance = it }
            }
        }
    }
}
