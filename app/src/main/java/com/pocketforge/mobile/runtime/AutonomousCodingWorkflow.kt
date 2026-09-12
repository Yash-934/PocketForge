package com.pocketforge.mobile.runtime

import com.pocketforge.mobile.model.ChatMessage
import com.pocketforge.mobile.model.ProjectKind
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * AutonomousCodingWorkflow coordinates high-quality autonomous coding with Claude Code.
 *
 * Enforces the disciplined workflow:
 * inspect → plan → relevant files → edit → lint → build → test → analyze errors → repair → final diff
 *
 * Invariants:
 * 1. Persistent project session per workspace.
 * 2. Maximum repair attempts = 3.
 * 3. Loop stop on repeated identical failures (anti-thrashing circuit breaker).
 * 4. Smart context injection: relevant search/file references without raw whole-repo dumps.
 */
object AutonomousCodingWorkflow {

    const val MAX_REPAIR_ATTEMPTS = 3

    /**
     * Builds the optimized prompt for Claude Code that enforces the 10-phase autonomous workflow.
     */
    fun buildOptimizedAgentPrompt(
        currentPrompt: String,
        history: List<ChatMessage>,
        guestWorkspacePath: String,
        projectKind: ProjectKind,
        recentModifiedFiles: List<String> = emptyList(),
    ): String {
        val priorMessages = history
            .filter { msg ->
                (msg.fromUser || !msg.text.startsWith("Hi! Tell me")) &&
                !msg.text.startsWith("Failed to") &&
                !msg.text.startsWith("Error:") &&
                !msg.text.contains("API Error")
            }
            .dropLast(1) // Drop current prompt just added to history

        val sb = StringBuilder()

        // 1. Workspace Boundaries & Environment
        sb.appendLine("<workspace_environment>")
        if (projectKind == ProjectKind.QUICK_PROJECT) {
            sb.appendLine("Workspace Root: $guestWorkspacePath (Lightweight Quick Project)")
            sb.appendLine("Respond conversationally, and use terminal and file tools inside this directory.")
        } else {
            sb.appendLine("Workspace Root: $guestWorkspacePath (Primary Project Root)")
            sb.appendLine("Create, inspect, and modify files directly in this directory.")
            sb.appendLine("Do not create extraneous nested root folders unless explicitly requested.")
        }
        sb.appendLine("Pre-installed Toolchain: JDK 17, Android SDK 36 (ARM64 aapt2), Gradle 8.14.3, Node.js, Python, Rust/Cargo.")
        sb.appendLine("For Android projects, use AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, and Java 17 for offline build compatibility.")
        sb.appendLine("</workspace_environment>")
        sb.appendLine()

        // 2. Strict Autonomous Workflow Directives
        sb.appendLine("<autonomous_coding_workflow>")
        sb.appendLine("You are the primary autonomous coding agent in PocketForge. Follow this rigorous 10-phase engineering workflow for every task:")
        sb.appendLine("1. INSPECT: First discover the project layout and symbols using `Glob`, `Grep`, or `View`. Never guess or blindly edit without checking existing files.")
        sb.appendLine("2. PLAN: Formulate a concise execution plan identifying target files, dependencies, and changes.")
        sb.appendLine("3. RELEVANT FILES ONLY: Read only the specific files or sections needed. Avoid loading whole directory trees into context.")
        sb.appendLine("4. PRECISE EDIT: Make clean, idiomatic edits with `Edit` or `Write`. Maintain consistent indentation and type safety.")
        sb.appendLine("5. LINT: Run static analysis or linter (e.g. `npm run lint`, `npx tsc --noEmit`, `cargo check`, `gradle lint`) where applicable.")
        sb.appendLine("6. BUILD: Run project build command (e.g. `gradle assembleDebug` / `gradle compileDebugSources`, `npm run build`, `cargo build`) to confirm compilation.")
        sb.appendLine("7. TEST: Execute automated tests (e.g. `gradle test`, `npm test`, `cargo test`, `pytest`) to prevent regressions.")
        sb.appendLine("8. ANALYZE ERRORS: If lint, build, or test fails, isolate the exact line, symbol, or stack trace from output.")
        sb.appendLine("9. REPAIR (Max 3 attempts):")
        sb.appendLine("   - Repair identified errors with targeted fixes (maximum 3 attempts).")
        sb.appendLine("   - CIRCUIT BREAKER: If the identical error signature occurs repeatedly without progress, STOP the loop immediately and explain the blocker to the user rather than looping indefinitely.")
        sb.appendLine("10. FINAL DIFF & SUMMARY: Conclude with a clear summary of modified files, verification status, and architectural changes.")
        sb.appendLine("</autonomous_coding_workflow>")
        sb.appendLine()

        // 3. Recently Modified Files Context (Targeted, Not Full Repo Dump)
        if (recentModifiedFiles.isNotEmpty()) {
            sb.appendLine("<active_workspace_changes>")
            sb.appendLine("Recently modified files in this project:")
            recentModifiedFiles.take(15).forEach { file ->
                sb.appendLine("- $file")
            }
            sb.appendLine("Use `View` or `Grep` on these files if relevant to the request.")
            sb.appendLine("</active_workspace_changes>")
            sb.appendLine()
        }

        // 4. Conversation History (Compacted for High Signal)
        if (priorMessages.isNotEmpty()) {
            sb.appendLine("<conversation_history>")
            sb.appendLine("Prior project discussion (continue seamlessly):")
            sb.appendLine()
            for (msg in priorMessages.takeLast(10)) {
                val role = if (msg.fromUser) "User" else "Assistant"
                sb.appendLine("$role: ${msg.text.trim()}")
                if (msg.attachments.isNotEmpty()) {
                    sb.appendLine("Attached files:")
                    msg.attachments.forEach { attachment ->
                        sb.appendLine("- ${attachment.displayName}: $guestWorkspacePath/${attachment.relativePath} (${attachment.mimeType})")
                    }
                }
                sb.appendLine()
            }
            sb.appendLine("</conversation_history>")
            sb.appendLine()
        }

        // 5. Current User Request
        sb.appendLine("<current_user_request>")
        sb.appendLine(currentPrompt.trim())
        sb.appendLine("</current_user_request>")

        return sb.toString()
    }

    /**
     * RepairLoopTracker detects repeated identical failures and tracks repair attempts
     * to prevent infinite retry loops.
     */
    class RepairLoopTracker {
        private val attemptsPerSession = ConcurrentHashMap<String, Int>()
        private val errorHistoryPerSession = ConcurrentHashMap<String, MutableList<String>>()

        enum class Action {
            PROCEED_REPAIR,
            HALT_MAX_ATTEMPTS_EXCEEDED,
            HALT_REPEATED_IDENTICAL_FAILURE,
        }

        data class Assessment(
            val action: Action,
            val currentAttempt: Int,
            val errorFingerprint: String,
            val explanation: String,
        )

        /**
         * Record a build/test failure and assess whether repair should proceed or halt.
         */
        fun recordFailure(sessionId: String, rawErrorOutput: String): Assessment {
            val fingerprint = computeErrorFingerprint(rawErrorOutput)
            val currentCount = attemptsPerSession.compute(sessionId) { _, count -> (count ?: 0) + 1 } ?: 1
            val history = errorHistoryPerSession.computeIfAbsent(sessionId) { mutableListOf() }

            // Check if identical error occurred in previous attempt
            val isDuplicate = history.isNotEmpty() && history.last() == fingerprint
            history.add(fingerprint)

            return when {
                isDuplicate -> Assessment(
                    action = Action.HALT_REPEATED_IDENTICAL_FAILURE,
                    currentAttempt = currentCount,
                    errorFingerprint = fingerprint,
                    explanation = "Identical failure signature detected across consecutive repair attempts. Stopping loop to prevent thrashing.",
                )
                currentCount >= MAX_REPAIR_ATTEMPTS -> Assessment(
                    action = Action.HALT_MAX_ATTEMPTS_EXCEEDED,
                    currentAttempt = currentCount,
                    errorFingerprint = fingerprint,
                    explanation = "Maximum repair attempts ($MAX_REPAIR_ATTEMPTS) reached for this task.",
                )
                else -> Assessment(
                    action = Action.PROCEED_REPAIR,
                    currentAttempt = currentCount,
                    errorFingerprint = fingerprint,
                    explanation = "Proceeding with repair attempt $currentCount of $MAX_REPAIR_ATTEMPTS.",
                )
            }
        }

        fun getAttemptCount(sessionId: String): Int = attemptsPerSession[sessionId] ?: 0

        fun reset(sessionId: String) {
            attemptsPerSession.remove(sessionId)
            errorHistoryPerSession.remove(sessionId)
        }

        /**
         * Normalizes raw error output into a deterministic semantic fingerprint
         * by stripping timestamps, file path prefixes, and memory addresses.
         */
        fun computeErrorFingerprint(rawError: String): String {
            if (rawError.isBlank()) return "empty_error"
            val normalized = rawError.lines()
                .map { line ->
                    line.trim()
                        .replace(Regex("""/.*?/"""), "<path>/") // Normalize path prefixes
                        .replace(Regex("""\b0x[0-9a-fA-F]+\b"""), "<hex>") // Normalize memory addresses
                        .replace(Regex("""\b\d{4}-\d{2}-\d{2}[T\s]\d{2}:\d{2}:\d{2}(\.\d+)?\b"""), "<time>") // Timestamps
                        .replace(Regex("""\b(line\s+)?\d+:\d+\b""", RegexOption.IGNORE_CASE), "<pos>") // Line numbers
                }
                .filter { it.isNotBlank() && (it.contains("error", true) || it.contains("exception", true) || it.contains("failed", true)) }
                .joinToString("\n")
                .ifBlank { rawError.take(300).trim() }

            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(normalized.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
