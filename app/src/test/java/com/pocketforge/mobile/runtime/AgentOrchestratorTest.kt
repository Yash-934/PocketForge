package com.pocketforge.mobile.runtime

import com.pocketforge.mobile.model.AiMode
import com.pocketforge.mobile.model.ChangeItem
import com.pocketforge.mobile.model.ChatMessage
import com.pocketforge.mobile.model.ProjectKind
import com.pocketforge.mobile.model.ProviderKind
import com.pocketforge.mobile.model.ProviderProfile
import com.pocketforge.mobile.model.RuntimeEvent
import com.pocketforge.mobile.model.ToolRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOrchestratorTest {

    private class FakeRuntimeBridge : RuntimeBridge {
        val bridgeEvents = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 16)
        override val events: Flow<RuntimeEvent> = bridgeEvents

        var startSessionCalled = false
        var lastPrompt: String? = null
        var stopSessionCalled = false
        var stopActiveSessionCalled = false
        var undoLastChangesCalled = false
        var configuredRoot: Pair<String, String>? = null

        override suspend fun startSession(
            projectId: String,
            projectSlug: String,
            projectKind: ProjectKind,
            prompt: String,
            conversationHistory: List<ChatMessage>,
            provider: ProviderProfile,
        ): String {
            startSessionCalled = true
            lastPrompt = prompt
            return "fake-session-123"
        }

        override suspend fun respondToApproval(request: ToolRequest, approved: Boolean, rememberForSession: Boolean) {}

        override suspend fun stopSession(sessionId: String) {
            stopSessionCalled = true
        }

        override suspend fun stopActiveSession() {
            stopActiveSessionCalled = true
        }

        override suspend fun undoLastChanges(projectId: String): Boolean {
            undoLastChangesCalled = true
            return true
        }

        override suspend fun acceptLastChanges(projectId: String) {}

        override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = emptyList()

        override suspend fun undoFileChange(projectId: String, path: String): Boolean = true

        override suspend fun acceptFileChange(projectId: String, path: String): Boolean = true

        override fun configureProjectRoot(projectId: String, rootPath: String) {
            configuredRoot = projectId to rootPath
        }
    }

    @Test
    fun aiModeParsingAndDefaults() {
        assertEquals(AiMode.AUTO, AiMode.DEFAULT)
        assertEquals(AiMode.AUTO, AiMode.fromString(null))
        assertEquals(AiMode.AUTO, AiMode.fromString(""))
        assertEquals(AiMode.AUTO, AiMode.fromString("unknown_mode"))
        assertEquals(AiMode.AUTO, AiMode.fromString("AUTO"))
        assertEquals(AiMode.AUTO, AiMode.fromString("auto"))
        assertEquals(AiMode.CLAUDE_CODE, AiMode.fromString("CLAUDE_CODE"))
        assertEquals(AiMode.CLAUDE_CODE, AiMode.fromString("claude_code"))
        assertEquals(AiMode.LOCAL_MODEL, AiMode.fromString("LOCAL_MODEL"))
        assertEquals(AiMode.WEB_CHAT, AiMode.fromString("WEB_CHAT"))
        assertEquals(AiMode.API, AiMode.fromString("API"))
    }

    @Test
    fun aiModeAvailabilityAndBadges() {
        assertTrue(AiMode.AUTO.isAvailable)
        assertEquals("Auto", AiMode.AUTO.badgeText)

        assertTrue(AiMode.CLAUDE_CODE.isAvailable)
        assertEquals("Primary", AiMode.CLAUDE_CODE.badgeText)

        assertTrue(AiMode.LOCAL_MODEL.isAvailable)
        assertEquals("Offline", AiMode.LOCAL_MODEL.badgeText)

        assertTrue(AiMode.WEB_CHAT.isAvailable)
        assertEquals("No API Key", AiMode.WEB_CHAT.badgeText)

        assertTrue(AiMode.API.isAvailable)
        assertEquals("Cloud", AiMode.API.badgeText)
    }

    @Test
    fun autoAiRouterEvaluatesRulesCorrectly() {
        // Offline -> LOCAL
        val offlineDecision = AutoAiRouter.evaluate(
            prompt = "Refactor this entire database architecture",
            context = null,
            localEngine = null,
            provider = ProviderProfile(ProviderKind.ANTHROPIC),
            userSelectedMode = AiMode.AUTO,
            hasInternetOverride = false,
        )
        assertEquals(AiMode.LOCAL_MODEL, offlineDecision.targetMode)
        assertTrue(offlineDecision.modelLabel.contains("Local Model"))
        assertFalse(offlineDecision.isPaidApi)

        // Simple question / small edit with Claude config -> CLAUDE_CODE or LOCAL
        val simpleDecision = AutoAiRouter.evaluate(
            prompt = "What does this helper function do?",
            context = null,
            localEngine = null,
            provider = ProviderProfile(ProviderKind.ANTHROPIC),
            userSelectedMode = AiMode.AUTO,
            hasInternetOverride = true,
        )
        assertTrue(simpleDecision.targetMode == AiMode.LOCAL_MODEL || simpleDecision.targetMode == AiMode.CLAUDE_CODE)

        // Complex multi-step coding task -> CLAUDE_CODE
        val complexDecision = AutoAiRouter.evaluate(
            prompt = "Refactor the authentication architecture, run gradle build, and fix all unit tests",
            context = null,
            localEngine = null,
            provider = ProviderProfile(ProviderKind.ANTHROPIC),
            userSelectedMode = AiMode.AUTO,
            hasInternetOverride = true,
        )
        assertEquals(AiMode.CLAUDE_CODE, complexDecision.targetMode)
        assertTrue(complexDecision.modelLabel.contains("Claude Code"))
        assertFalse(complexDecision.isPaidApi)

        // Manual override -> Respected unconditionally
        val manualDecision = AutoAiRouter.evaluate(
            prompt = "Simple question",
            context = null,
            localEngine = null,
            provider = ProviderProfile(ProviderKind.ANTHROPIC),
            userSelectedMode = AiMode.CLAUDE_CODE,
            hasInternetOverride = true,
        )
        assertEquals(AiMode.CLAUDE_CODE, manualDecision.targetMode)
    }

    @Test
    fun orchestratorDelegatesToClaudeWhenInClaudeCodeMode() = runBlocking {
        val fakeBridge = FakeRuntimeBridge()
        val orchestrator = AgentOrchestrator(fakeBridge, initialMode = AiMode.CLAUDE_CODE)

        assertEquals(AiMode.CLAUDE_CODE, orchestrator.currentMode.value)

        val sessionId = orchestrator.startSession(
            projectId = "test-project",
            projectSlug = "test",
            projectKind = ProjectKind.PROJECT,
            prompt = "Build a counter",
            conversationHistory = emptyList(),
            provider = ProviderProfile(ProviderKind.ANTHROPIC),
        )

        assertEquals("fake-session-123", sessionId)
        assertTrue(fakeBridge.startSessionCalled)
        assertEquals("Build a counter", fakeBridge.lastPrompt)

        orchestrator.stopActiveSession()
        assertTrue(fakeBridge.stopActiveSessionCalled)

        orchestrator.configureProjectRoot("test-project", "src")
        assertEquals("test-project" to "src", fakeBridge.configuredRoot)
    }

    @Test
    fun orchestratorSafelyHandlesApiModeWithoutKey() = runBlocking {
        val fakeBridge = FakeRuntimeBridge()
        val orchestrator = AgentOrchestrator(fakeBridge, initialMode = AiMode.API)

        assertEquals(AiMode.API, orchestrator.currentMode.value)

        val deferredEvent = async { orchestrator.events.first() }

        val sessionId = orchestrator.startSession(
            projectId = "test-project",
            projectSlug = "test",
            projectKind = ProjectKind.PROJECT,
            prompt = "Build a counter",
            conversationHistory = emptyList(),
            provider = ProviderProfile(ProviderKind.ANTHROPIC, hasSecret = false),
        )

        val event = deferredEvent.await()
        assertFalse(fakeBridge.startSessionCalled)
        assertTrue(sessionId.isNotBlank())
        assertTrue(event is RuntimeEvent.SessionFailed)
        val failure = event as RuntimeEvent.SessionFailed
        assertEquals(sessionId, failure.sessionId)
        assertTrue(failure.reason.contains("not configured"))
    }

    @Test
    fun orchestratorEmitsGuidanceForWebChatCompanionMode() = runBlocking {
        val fakeBridge = FakeRuntimeBridge()
        val orchestrator = AgentOrchestrator(fakeBridge, initialMode = AiMode.WEB_CHAT)

        assertEquals(AiMode.WEB_CHAT, orchestrator.currentMode.value)

        val deferredEvent = async { orchestrator.events.first() }

        val sessionId = orchestrator.startSession(
            projectId = "test-project",
            projectSlug = "test",
            projectKind = ProjectKind.PROJECT,
            prompt = "Build a counter",
            conversationHistory = emptyList(),
            provider = ProviderProfile(ProviderKind.ANTHROPIC),
        )

        val event = deferredEvent.await()
        assertFalse(fakeBridge.startSessionCalled)
        assertTrue(sessionId.isNotBlank())
        assertTrue(
            event is RuntimeEvent.SessionStarted ||
            event is RuntimeEvent.AssistantDelta ||
            event is RuntimeEvent.SessionCompleted
        )
    }

    @Test
    fun orchestratorRequiresLoadedModelForLocalModelMode() = runBlocking {
        val fakeBridge = FakeRuntimeBridge()
        val orchestrator = AgentOrchestrator(fakeBridge, initialMode = AiMode.LOCAL_MODEL)

        assertEquals(AiMode.LOCAL_MODEL, orchestrator.currentMode.value)

        val deferredEvent = async { orchestrator.events.first() }

        val sessionId = orchestrator.startSession(
            projectId = "test-project",
            projectSlug = "test",
            projectKind = ProjectKind.PROJECT,
            prompt = "Build a counter",
            conversationHistory = emptyList(),
            provider = ProviderProfile(ProviderKind.ANTHROPIC),
        )

        val event = deferredEvent.await()
        assertFalse(fakeBridge.startSessionCalled)
        assertTrue(sessionId.isNotBlank())
        assertTrue(event is RuntimeEvent.SessionFailed)
        val failure = event as RuntimeEvent.SessionFailed
        assertEquals(sessionId, failure.sessionId)
        assertTrue(failure.reason.contains("No local GGUF model is loaded"))
    }
}
