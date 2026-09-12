package com.pocketforge.mobile.runtime

import com.pocketforge.mobile.model.ChatMessage
import com.pocketforge.mobile.model.ProjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousCodingWorkflowTest {

    @Test
    fun testPromptContainsAllWorkflowPhases() {
        val prompt = AutonomousCodingWorkflow.buildOptimizedAgentPrompt(
            currentPrompt = "Fix login screen NullPointerException",
            history = listOf(
                ChatMessage("msg-1", fromUser = true, text = "Hello"),
                ChatMessage("msg-2", fromUser = false, text = "I'm ready to help."),
                ChatMessage("msg-3", fromUser = true, text = "Fix login screen NullPointerException"),
            ),
            guestWorkspacePath = "/workspace/my-app",
            projectKind = ProjectKind.PROJECT,
            recentModifiedFiles = listOf("src/Auth.kt", "src/LoginScreen.kt"),
        )

        // Verify key phases are strictly specified in the prompt directives
        assertTrue("Must contain INSPECT phase", prompt.contains("1. INSPECT"))
        assertTrue("Must contain PLAN phase", prompt.contains("2. PLAN"))
        assertTrue("Must contain RELEVANT FILES phase", prompt.contains("3. RELEVANT FILES ONLY"))
        assertTrue("Must contain PRECISE EDIT phase", prompt.contains("4. PRECISE EDIT"))
        assertTrue("Must contain LINT phase", prompt.contains("5. LINT"))
        assertTrue("Must contain BUILD phase", prompt.contains("6. BUILD"))
        assertTrue("Must contain TEST phase", prompt.contains("7. TEST"))
        assertTrue("Must contain ANALYZE ERRORS phase", prompt.contains("8. ANALYZE ERRORS"))
        assertTrue("Must contain REPAIR phase with max 3 attempts", prompt.contains("9. REPAIR (Max 3 attempts)"))
        assertTrue("Must contain CIRCUIT BREAKER rule", prompt.contains("CIRCUIT BREAKER"))
        assertTrue("Must contain FINAL DIFF & SUMMARY", prompt.contains("10. FINAL DIFF & SUMMARY"))

        // Verify recent files and workspace environment
        assertTrue("Must reference workspace path", prompt.contains("/workspace/my-app"))
        assertTrue("Must include modified files", prompt.contains("src/LoginScreen.kt"))
        assertTrue("Must include current user request", prompt.contains("Fix login screen NullPointerException"))
    }

    @Test
    fun testRepairLoopTrackerHaltOnDuplicateFailures() {
        val tracker = AutonomousCodingWorkflow.RepairLoopTracker()
        val sessionId = "session-test-1"

        val failureLog1 = """
            e: /workspace/my-app/src/Login.kt:14:5 Unresolved reference: UserAuth
            Execution failed for task ':app:compileDebugKotlin'.
        """.trimIndent()

        val failureLog2Identical = """
            e: /workspace/my-app/src/Login.kt:14:5 Unresolved reference: UserAuth
            Execution failed for task ':app:compileDebugKotlin'.
        """.trimIndent()

        // Attempt 1 -> Proceed with repair
        val assess1 = tracker.recordFailure(sessionId, failureLog1)
        assertEquals(AutonomousCodingWorkflow.RepairLoopTracker.Action.PROCEED_REPAIR, assess1.action)
        assertEquals(1, assess1.currentAttempt)

        // Attempt 2 with IDENTICAL failure signature -> Must halt due to anti-thrashing circuit breaker
        val assess2 = tracker.recordFailure(sessionId, failureLog2Identical)
        assertEquals(AutonomousCodingWorkflow.RepairLoopTracker.Action.HALT_REPEATED_IDENTICAL_FAILURE, assess2.action)
        assertTrue(assess2.explanation.contains("Identical failure signature detected"))
    }

    @Test
    fun testRepairLoopTrackerHaltOnMaxAttemptsExceeded() {
        val tracker = AutonomousCodingWorkflow.RepairLoopTracker()
        val sessionId = "session-test-2"

        // Attempt 1: Error A
        val assess1 = tracker.recordFailure(sessionId, "Error: Cannot find module 'express'")
        assertEquals(AutonomousCodingWorkflow.RepairLoopTracker.Action.PROCEED_REPAIR, assess1.action)
        assertEquals(1, assess1.currentAttempt)

        // Attempt 2: Error B (Different error)
        val assess2 = tracker.recordFailure(sessionId, "Error: Port 3000 is already in use")
        assertEquals(AutonomousCodingWorkflow.RepairLoopTracker.Action.PROCEED_REPAIR, assess2.action)
        assertEquals(2, assess2.currentAttempt)

        // Attempt 3: Error C (Different error) -> Reached MAX_REPAIR_ATTEMPTS (3)
        val assess3 = tracker.recordFailure(sessionId, "Error: JWT_SECRET environment variable is missing")
        assertEquals(AutonomousCodingWorkflow.RepairLoopTracker.Action.HALT_MAX_ATTEMPTS_EXCEEDED, assess3.action)
        assertEquals(3, assess3.currentAttempt)
        assertTrue(assess3.explanation.contains("Maximum repair attempts (3) reached"))
    }

    @Test
    fun testRepairLoopTrackerReset() {
        val tracker = AutonomousCodingWorkflow.RepairLoopTracker()
        val sessionId = "session-test-3"

        tracker.recordFailure(sessionId, "Error 1")
        assertEquals(1, tracker.getAttemptCount(sessionId))

        tracker.reset(sessionId)
        assertEquals(0, tracker.getAttemptCount(sessionId))
    }

    @Test
    fun testErrorFingerprintNormalization() {
        val tracker = AutonomousCodingWorkflow.RepairLoopTracker()

        val raw1 = "2026-09-12 04:30:00 [ERROR] at 0x7ffd58 line 12:4: Type mismatch: inferred type is String but Int was expected"
        val raw2 = "2026-09-12 04:31:22 [ERROR] at 0x8aef11 line 99:1: Type mismatch: inferred type is String but Int was expected"

        val fp1 = tracker.computeErrorFingerprint(raw1)
        val fp2 = tracker.computeErrorFingerprint(raw2)

        assertEquals("Normalized error signatures with different timestamps and memory addresses must match", fp1, fp2)
    }
}
