package com.pocketforge.mobile.runtime

import com.pocketforge.mobile.model.AiMode
import com.pocketforge.mobile.model.ChangeItem
import com.pocketforge.mobile.model.ChatMessage
import com.pocketforge.mobile.model.ProjectKind
import com.pocketforge.mobile.model.ProviderKind
import com.pocketforge.mobile.model.ProviderProfile
import com.pocketforge.mobile.model.RuntimeEvent
import com.pocketforge.mobile.model.ToolRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedAgentChatboxTest {

    private class TestRuntimeBridge : RuntimeBridge {
        val bridgeEvents = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 16)
        override val events: Flow<RuntimeEvent> = bridgeEvents

        var startSessionCount = 0
        var lastPrompt: String? = null
        var stopSessionCalled = false
        var stopActiveSessionCalled = false
        var undoLastChangesCalled = false
        var acceptLastChangesCalled = false

        override suspend fun startSession(
            projectId: String,
            projectSlug: String,
            projectKind: ProjectKind,
            prompt: String,
            conversationHistory: List<ChatMessage>,
            provider: ProviderProfile,
        ): String {
            startSessionCount++
            lastPrompt = prompt
            return "session-$startSessionCount"
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

        override suspend fun acceptLastChanges(projectId: String) {
            acceptLastChangesCalled = true
        }

        override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = emptyList()

        override suspend fun undoFileChange(projectId: String, path: String): Boolean = true

        override suspend fun acceptFileChange(projectId: String, path: String): Boolean = true

        override fun configureProjectRoot(projectId: String, rootPath: String) {}
    }

    @Test
    fun agentStopAndUndoFunctionality() = runBlocking {
        val testBridge = TestRuntimeBridge()
        val orchestrator = AgentOrchestrator(testBridge, initialMode = AiMode.CLAUDE_CODE)

        orchestrator.startSession(
            projectId = "test-p1",
            projectSlug = "login-fix",
            projectKind = ProjectKind.PROJECT,
            prompt = "login screen fix karo",
            conversationHistory = emptyList(),
            provider = ProviderProfile(ProviderKind.ANTHROPIC),
        )

        assertEquals("login screen fix karo", testBridge.lastPrompt)
        assertEquals(1, testBridge.startSessionCount)

        // Stop session
        orchestrator.stopActiveSession()
        assertTrue(testBridge.stopActiveSessionCalled)

        // Undo changes
        val undone = orchestrator.undoLastChanges("test-p1")
        assertTrue(undone)
        assertTrue(testBridge.undoLastChangesCalled)

        // Accept changes
        orchestrator.acceptLastChanges("test-p1")
        assertTrue(testBridge.acceptLastChangesCalled)
    }

    @Test
    fun agentRetryPromptLogic() = runBlocking {
        val testBridge = TestRuntimeBridge()
        val orchestrator = AgentOrchestrator(testBridge, initialMode = AiMode.CLAUDE_CODE)

        // First prompt
        val initialPrompt = "login screen fix karo"
        orchestrator.startSession(
            projectId = "test-p1",
            projectSlug = "login-fix",
            projectKind = ProjectKind.PROJECT,
            prompt = initialPrompt,
            conversationHistory = emptyList(),
            provider = ProviderProfile(ProviderKind.ANTHROPIC),
        )
        assertEquals(initialPrompt, testBridge.lastPrompt)
        assertEquals(1, testBridge.startSessionCount)

        // Simulate Retry action with the same prompt
        orchestrator.startSession(
            projectId = "test-p1",
            projectSlug = "login-fix",
            projectKind = ProjectKind.PROJECT,
            prompt = initialPrompt,
            conversationHistory = listOf(
                ChatMessage(
                    id = "msg-1",
                    fromUser = true,
                    text = initialPrompt,
                ),
            ),
            provider = ProviderProfile(ProviderKind.ANTHROPIC),
        )

        assertEquals(initialPrompt, testBridge.lastPrompt)
        assertEquals(2, testBridge.startSessionCount)
    }
}
