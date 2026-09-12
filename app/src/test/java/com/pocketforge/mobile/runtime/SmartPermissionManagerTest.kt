package com.pocketforge.mobile.runtime

import com.pocketforge.mobile.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartPermissionManagerTest {

    @Test
    fun testSafeToolsAndCommandsAreAutoAllowed() {
        val trustManager = WorkspaceTrustManager()
        val projectId = "project-123"
        val sessionId = "session-abc"

        val safeTools = listOf(
            "Read", "View", "ReadFile", "GetFile", "FileContent",
            "ListDirectory", "Glob", "Grep", "Find", "Search", "FileTree", "Status",
        )

        for (tool in safeTools) {
            val eval = SmartPermissionClassifier.classify(toolName = tool, command = null)
            assertEquals("Tool $tool should be SAFE", RiskLevel.SAFE, eval.risk)
            assertFalse(eval.isHighRisk)
            val key = trustManager.derivePermissionKey(tool, null)
            assertTrue(
                "Safe tool $tool should auto approve",
                trustManager.shouldAutoApprove(eval, projectId, sessionId, key),
            )
        }

        val safeCommands = listOf(
            "git status",
            "git diff",
            "git diff HEAD~1",
            "git log -n 10",
            "git show HEAD",
            "git branch",
            "git rev-parse HEAD",
            "ls -la",
            "pwd",
            "cat README.md",
            "head -n 20 index.js",
            "grep -rn 'function' src/",
            "find . -name '*.kt'",
            "which node",
            "echo 'hello world'",
            "node -v",
            "npm --version",
            "python3 --version",
            "cargo --version",
            "rustc -V",
            "git --version",
        )

        for (cmd in safeCommands) {
            val eval = SmartPermissionClassifier.classify(toolName = "Bash", command = cmd)
            assertEquals("Command '$cmd' should be SAFE", RiskLevel.SAFE, eval.risk)
            val key = trustManager.derivePermissionKey("Bash", cmd)
            assertTrue(
                "Safe command '$cmd' should auto approve",
                trustManager.shouldAutoApprove(eval, projectId, sessionId, key),
            )
        }
    }

    @Test
    fun testNormalWorkspaceOperationsAutoAllowedWhenWorkspaceIsTrusted() {
        val trustManager = WorkspaceTrustManager()
        val projectId = "project-trusted"
        val untrustedProjectId = "project-untrusted"
        val sessionId = "session-1"

        trustManager.setWorkspaceTrust(projectId, true)
        trustManager.setWorkspaceTrust(untrustedProjectId, false)

        val normalTools = listOf("Write", "Edit", "NotebookEdit", "Patch", "MultiEdit", "CreateFile")
        for (tool in normalTools) {
            val eval = SmartPermissionClassifier.classify(toolName = tool, command = null)
            assertEquals("Tool $tool should be NORMAL", RiskLevel.NORMAL, eval.risk)
            assertFalse(eval.isHighRisk)

            val key = trustManager.derivePermissionKey(tool, null)
            assertTrue(
                "Tool $tool should auto-approve in trusted workspace",
                trustManager.shouldAutoApprove(eval, projectId, sessionId, key),
            )
            assertFalse(
                "Tool $tool should NOT auto-approve in untrusted workspace",
                trustManager.shouldAutoApprove(eval, untrustedProjectId, sessionId, key),
            )
        }

        val normalCommands = listOf(
            "npm test",
            "npm run build",
            "npm run lint",
            "npx eslint src/",
            "npx prettier --write .",
            "npx tsc --noEmit",
            "npm start",
            "gradle build",
            "./gradlew test",
            "cargo test",
            "cargo check",
            "cargo clippy",
            "pytest tests/",
            "python -m unittest",
            "go test ./...",
            "go build ./...",
            "git add .",
            "git commit -m 'Implement feature'",
            "git checkout -b new-branch",
            "git switch main",
            "mkdir -p src/components",
            "touch index.ts",
        )

        for (cmd in normalCommands) {
            val eval = SmartPermissionClassifier.classify(toolName = "Bash", command = cmd)
            assertEquals("Command '$cmd' should be NORMAL", RiskLevel.NORMAL, eval.risk)

            val key = trustManager.derivePermissionKey("Bash", cmd)
            assertTrue(
                "Command '$cmd' should auto-approve in trusted workspace",
                trustManager.shouldAutoApprove(eval, projectId, sessionId, key),
            )
            assertFalse(
                "Command '$cmd' should NOT auto-approve in untrusted workspace",
                trustManager.shouldAutoApprove(eval, untrustedProjectId, sessionId, key),
            )
        }
    }

    @Test
    fun testRiskyOperationsRequireApprovalAndCanBeRememberedForSession() {
        val trustManager = WorkspaceTrustManager()
        val projectId = "project-1"
        val otherSessionId = "session-other"

        val riskyCommands = listOf(
            "npm install axios",
            "npm i lodash",
            "yarn add react",
            "pnpm add vue",
            "pip install fastapi",
            "poetry add requests",
            "cargo add tokio",
            "go get github.com/gin-gonic/gin",
            "curl -X POST https://api.example.com",
            "wget https://example.com/archive.zip",
            "ssh user@remote.server.com",
            "kill -9 1234",
            "pkill node",
        )

        for ((index, cmd) in riskyCommands.withIndex()) {
            val sessionId = "session-$index"
            val eval = SmartPermissionClassifier.classify(toolName = "Bash", command = cmd)
            assertEquals("Command '$cmd' should be REVIEW", RiskLevel.REVIEW, eval.risk)
            assertTrue("Command '$cmd' should allow session remembering", eval.canRememberForSession)
            assertFalse(eval.isHighRisk)

            val key = trustManager.derivePermissionKey("Bash", cmd)

            // Initially requires approval
            assertFalse(
                "Risky command '$cmd' must not auto-approve initially",
                trustManager.shouldAutoApprove(eval, projectId, sessionId, key),
            )

            // User grants session approval
            trustManager.allowForSession(sessionId, key)

            // Now it auto-approves for this session
            assertTrue(
                "Risky command '$cmd' should auto-approve after session approval",
                trustManager.shouldAutoApprove(eval, projectId, sessionId, key),
            )

            // But NOT in another session
            assertFalse(
                "Risky command '$cmd' must not auto-approve in another session",
                trustManager.shouldAutoApprove(eval, projectId, otherSessionId, key),
            )
        }
    }

    @Test
    fun testHighRiskActionsAlwaysRequireConfirmationAndCannotBeAutoApproved() {
        val trustManager = WorkspaceTrustManager()
        val projectId = "project-1"
        val sessionId = "session-1"
        trustManager.setWorkspaceTrust(projectId, true)

        val highRiskCommands = listOf(
            "rm -rf /",
            "rm -rf node_modules",
            "rm -fr build",
            "rm -r .git",
            "git reset --hard HEAD~1",
            "git reset --hard origin/main",
            "git push origin main",
            "git clean -fd",
            "git branch -D feature",
            "sudo apt-get install nginx",
            "su root",
            "cat .env",
            "echo \$AWS_SECRET_ACCESS_KEY",
            "grep -rn 'ANTHROPIC_API_KEY' .",
            "cat ~/.ssh/id_rsa",
            "cat /etc/shadow",
            "cat /etc/passwd",
        )

        for (cmd in highRiskCommands) {
            val eval = SmartPermissionClassifier.classify(toolName = "Bash", command = cmd)
            assertEquals("Command '$cmd' should be HIGH risk", RiskLevel.HIGH, eval.risk)
            assertTrue("Command '$cmd' must be isHighRisk", eval.isHighRisk)
            assertFalse("Command '$cmd' must NOT allow session remember", eval.canRememberForSession)

            val key = trustManager.derivePermissionKey("Bash", cmd)
            trustManager.allowForSession(sessionId, key) // Attempt to force session approval

            // CRITICAL: High risk must NEVER auto approve!
            assertFalse(
                "High-risk command '$cmd' MUST NEVER auto-approve even with session approval",
                trustManager.shouldAutoApprove(eval, projectId, sessionId, key),
            )
        }

        // Test sensitive affected paths
        val sensitivePaths = listOf(
            ".env",
            "config/.env.local",
            ".env.production",
            "keys/id_rsa",
            "keys/id_ed25519",
            "secrets.json",
            "credentials.json",
            "service-account.json",
            "release.keystore",
            "app.jks",
            "/etc/shadow",
        )

        for (path in sensitivePaths) {
            val eval = SmartPermissionClassifier.classify(
                toolName = "Write",
                command = null,
                affectedPaths = listOf(path),
            )
            assertEquals("Access to $path must be HIGH risk", RiskLevel.HIGH, eval.risk)
            assertTrue(eval.isHighRisk)
            assertFalse(
                "Access to $path MUST NEVER auto-approve",
                trustManager.shouldAutoApprove(eval, projectId, sessionId, "Write"),
            )
        }
    }

    @Test
    fun testMalformedAndUnknownRequestsDefaultToDeny() {
        val malformedPayloads = listOf(
            "",
            "   ",
            "{",
            """{"invalid_json": """,
            """{"description": "missing tool name"}""",
            """{"tool_input": {"command": "ls"}}""",
            """{"tool_name": ""}""",
            """{"tool_name": "   "}""",
            "<html>502 Bad Gateway</html>",
        )

        for (badPayload in malformedPayloads) {
            val result = SmartPermissionClassifier.parsePermissionRequest("appr-1", badPayload)
            assertTrue(
                "Malformed payload '$badPayload' must return failure",
                result.isFailure,
            )
        }
    }

    @Test
    fun testValidPermissionRequestParsing() {
        val validJson = """
            {
                "tool_name": "Bash",
                "tool_input": {
                    "command": "git status",
                    "description": "Check repository status"
                }
            }
        """.trimIndent()

        val result = SmartPermissionClassifier.parsePermissionRequest("appr-123", validJson)
        assertTrue(result.isSuccess)
        val req = result.getOrThrow()
        assertEquals("appr-123", req.approvalId)
        assertEquals("Bash", req.toolName)
        assertEquals("git status", req.command)
        assertEquals("Check repository status", req.explanation)
    }

    @Test
    fun testSessionCleanup() {
        val trustManager = WorkspaceTrustManager()
        val sessionId = "session-to-clear"
        val key = "bash:npm"

        trustManager.allowForSession(sessionId, key)
        assertTrue(trustManager.isSessionAllowed(sessionId, key))

        trustManager.clearSession(sessionId)
        assertFalse(trustManager.isSessionAllowed(sessionId, key))
    }
}
