package com.pocketforge.mobile.runtime

import com.pocketforge.mobile.localmodel.engine.LocalModelEngine
import com.pocketforge.mobile.localmodel.engine.LocalModelGateway
import com.pocketforge.mobile.model.AiMode
import com.pocketforge.mobile.model.ChangeItem
import com.pocketforge.mobile.model.ChatMessage
import com.pocketforge.mobile.model.ProjectKind
import com.pocketforge.mobile.model.ProviderKind
import com.pocketforge.mobile.model.ProviderProfile
import com.pocketforge.mobile.model.RuntimeEvent
import com.pocketforge.mobile.model.ToolRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Common AgentOrchestrator that routes agent execution according to the selected [AiMode].
 *
 * Claude Code remains the default and primary autonomous coding agent with full PRoot sandbox
 * and tool-use capabilities. Local Model runs local GGUF models on-device using the same tool
 * and sandbox infrastructure via LocalModelEngine and LocalModelGateway.
 */
class AgentOrchestrator(
    val claudeBridge: RuntimeBridge,
    initialMode: AiMode = AiMode.DEFAULT,
    val localEngine: LocalModelEngine? = null,
    val context: android.content.Context? = null,
) : RuntimeBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _currentMode = MutableStateFlow(initialMode)
    val currentMode: StateFlow<AiMode> = _currentMode.asStateFlow()

    private val fallbackEvents = MutableSharedFlow<RuntimeEvent>(replay = 1, extraBufferCapacity = 64)
    private var activeLocalGateway: LocalModelGateway? = null
    private var activeLocalSessionId: String? = null

    override val events: Flow<RuntimeEvent> = merge(
        claudeBridge.events,
        fallbackEvents.asSharedFlow(),
    )

    fun setMode(mode: AiMode) {
        _currentMode.value = mode
    }

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String {
        val routingDecision = AutoAiRouter.evaluate(
            prompt = prompt,
            context = context,
            localEngine = localEngine,
            provider = provider,
            userSelectedMode = _currentMode.value,
        )

        return when (routingDecision.targetMode) {
            AiMode.AUTO, AiMode.CLAUDE_CODE -> {
                claudeBridge.startSession(
                    projectId = projectId,
                    projectSlug = projectSlug,
                    projectKind = projectKind,
                    prompt = prompt,
                    conversationHistory = conversationHistory,
                    provider = provider,
                )
            }
            AiMode.LOCAL_MODEL -> {
                val engine = localEngine
                if (engine == null || !engine.isLoaded.value || engine.activeModel.value == null) {
                    val sessionId = java.util.UUID.randomUUID().toString()
                    val message = "No local GGUF model is loaded. Please import and load a GGUF model in Settings -> AI mode -> Local Model."
                    fallbackEvents.emit(
                        RuntimeEvent.SessionFailed(
                            sessionId = sessionId,
                            reason = message,
                        )
                    )
                    return sessionId
                }

                // Start local loopback gateway connected to the local GGUF engine
                val gateway = LocalModelGateway(engine).start()
                activeLocalGateway?.close()
                activeLocalGateway = gateway

                val localProfile = ProviderProfile(
                    kind = ProviderKind.CUSTOM,
                    baseUrl = gateway.url,
                    model = engine.activeModel.value?.name ?: "local-gguf",
                    hasSecret = true,
                )

                try {
                    claudeBridge.startSession(
                        projectId = projectId,
                        projectSlug = projectSlug,
                        projectKind = projectKind,
                        prompt = prompt,
                        conversationHistory = conversationHistory,
                        provider = localProfile,
                    )
                } catch (e: Exception) {
                    // Fallback to direct token streaming if Claude Code PRoot isn't initialized
                    val sessionId = java.util.UUID.randomUUID().toString()
                    activeLocalSessionId = sessionId
                    scope.launch {
                        runDirectLocalSession(sessionId, prompt, engine)
                    }
                    sessionId
                }
            }
            AiMode.WEB_CHAT -> {
                val sessionId = java.util.UUID.randomUUID().toString()
                scope.launch {
                    fallbackEvents.emit(RuntimeEvent.SessionStarted(sessionId))
                    val message = "Web Chat Companion is ready. You can open Claude, ChatGPT, DeepSeek, or Gemini web chats with manual login, copy the prepared prompt, and paste the response back into PocketForge."
                    fallbackEvents.emit(RuntimeEvent.AssistantDelta(sessionId, message))
                    fallbackEvents.emit(RuntimeEvent.SessionCompleted(sessionId))
                }
                sessionId
            }
            AiMode.API -> {
                if (provider.hasSecret) {
                    claudeBridge.startSession(
                        projectId = projectId,
                        projectSlug = projectSlug,
                        projectKind = projectKind,
                        prompt = prompt,
                        conversationHistory = conversationHistory,
                        provider = provider,
                    )
                } else {
                    val sessionId = java.util.UUID.randomUUID().toString()
                    val message = "Direct API key for ${provider.kind.title} is not configured. Please add your API key in Settings -> Model provider."
                    fallbackEvents.emit(
                        RuntimeEvent.SessionFailed(
                            sessionId = sessionId,
                            reason = message,
                        )
                    )
                    sessionId
                }
            }
        }
    }

    private suspend fun runDirectLocalSession(
        sessionId: String,
        prompt: String,
        engine: LocalModelEngine,
    ) {
        fallbackEvents.emit(RuntimeEvent.SessionStarted(sessionId))
        try {
            engine.generateTokens(prompt).collect { token ->
                fallbackEvents.emit(RuntimeEvent.AssistantDelta(sessionId, token))
            }
            fallbackEvents.emit(RuntimeEvent.SessionCompleted(sessionId))
        } catch (e: Exception) {
            fallbackEvents.emit(RuntimeEvent.SessionFailed(sessionId, e.message ?: "Local model generation failed"))
        } finally {
            if (activeLocalSessionId == sessionId) {
                activeLocalSessionId = null
            }
        }
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean, rememberForSession: Boolean) {
        when (_currentMode.value) {
            AiMode.CLAUDE_CODE, AiMode.LOCAL_MODEL -> claudeBridge.respondToApproval(request, approved, rememberForSession)
            else -> Unit
        }
    }

    override fun setWorkspaceTrust(projectId: String, trusted: Boolean) {
        claudeBridge.setWorkspaceTrust(projectId, trusted)
    }

    override fun isWorkspaceTrusted(projectId: String): Boolean {
        return claudeBridge.isWorkspaceTrusted(projectId)
    }

    override suspend fun stopSession(sessionId: String) {
        when (_currentMode.value) {
            AiMode.CLAUDE_CODE -> claudeBridge.stopSession(sessionId)
            AiMode.LOCAL_MODEL -> {
                claudeBridge.stopSession(sessionId)
                localEngine?.cancelGeneration()
                activeLocalGateway?.close()
                activeLocalGateway = null
            }
            else -> Unit
        }
    }

    override suspend fun stopActiveSession() {
        when (_currentMode.value) {
            AiMode.CLAUDE_CODE -> claudeBridge.stopActiveSession()
            AiMode.LOCAL_MODEL -> {
                claudeBridge.stopActiveSession()
                localEngine?.cancelGeneration()
                activeLocalGateway?.close()
                activeLocalGateway = null
            }
            else -> Unit
        }
    }

    override suspend fun undoLastChanges(projectId: String): Boolean {
        return when (_currentMode.value) {
            AiMode.CLAUDE_CODE, AiMode.LOCAL_MODEL -> claudeBridge.undoLastChanges(projectId)
            else -> false
        }
    }

    override suspend fun acceptLastChanges(projectId: String) {
        when (_currentMode.value) {
            AiMode.CLAUDE_CODE, AiMode.LOCAL_MODEL -> claudeBridge.acceptLastChanges(projectId)
            else -> Unit
        }
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> {
        return when (_currentMode.value) {
            AiMode.CLAUDE_CODE, AiMode.LOCAL_MODEL -> claudeBridge.loadPendingChanges(projectId)
            else -> emptyList()
        }
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean {
        return when (_currentMode.value) {
            AiMode.CLAUDE_CODE, AiMode.LOCAL_MODEL -> claudeBridge.undoFileChange(projectId, path)
            else -> false
        }
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean {
        return when (_currentMode.value) {
            AiMode.CLAUDE_CODE, AiMode.LOCAL_MODEL -> claudeBridge.acceptFileChange(projectId, path)
            else -> false
        }
    }

    override fun configureProjectRoot(projectId: String, rootPath: String) {
        claudeBridge.configureProjectRoot(projectId, rootPath)
    }

    override fun resetProjectSession(projectId: String) {
        claudeBridge.resetProjectSession(projectId)
    }
}
