package com.pocketforge.mobile.runtime

import android.util.Log
import com.pocketforge.mobile.model.RiskLevel
import com.pocketforge.mobile.model.ToolRequest
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * VS Code / Cursor-style smart permission evaluation and workspace trust manager for PocketForge.
 *
 * Permission tiers:
 * - SAFE: Read, search, inspect, git status/diff -> Automatic approval.
 * - NORMAL: Workspace edits, create, compile, lint, build, test -> Automatic when workspace is trusted.
 * - REVIEW: Dependency installs, network transfers, process/service actions -> User approval required (can remember for session).
 * - HIGH: rm -rf, git reset --hard, git push, credential access, sudo -> Always explicitly confirm, never auto-approved.
 *
 * CRITICAL SECURITY INVARIANT:
 * - Malformed, unparseable, or unknown permission requests MUST ALWAYS default to DENY.
 * - Never write "allow" on parse error, missing fields, or unexpected exceptions.
 */
data class PermissionEvaluation(
    val risk: RiskLevel,
    val reason: String,
    val isHighRisk: Boolean = (risk == RiskLevel.HIGH),
    val canRememberForSession: Boolean = (risk == RiskLevel.REVIEW),
)

data class RawPermissionRequest(
    val approvalId: String,
    val toolName: String,
    val command: String?,
    val affectedPaths: List<String>,
    val explanation: String,
)

object SmartPermissionClassifier {

    private val SAFE_TOOLS = setOf(
        "Read",
        "View",
        "ReadFile",
        "GetFile",
        "FileContent",
        "ListDirectory",
        "Glob",
        "Grep",
        "Find",
        "Search",
        "FileTree",
        "Status",
        "WebSearch",
        "FetchDocs",
    )

    private val NORMAL_WORKSPACE_TOOLS = setOf(
        "Write",
        "Edit",
        "NotebookEdit",
        "Patch",
        "MultiEdit",
        "CreateFile",
        "DeleteFile",
    )

    private val SENSITIVE_FILE_PATTERN = Regex(
        """(^|[\s/'"\\])(\.env(\.[a-zA-Z0-9_-]+)?|id_rsa[a-zA-Z0-9_-]*|id_ed25519[a-zA-Z0-9_-]*|id_dsa[a-zA-Z0-9_-]*|authorized_keys|known_hosts|credentials\.json|secrets\.json|service[-_]account\.json|[^/\s]+\.jks|[^/\s]+\.keystore|shadow|passwd|sudoers)($|[\s/'"\\])""",
        RegexOption.IGNORE_CASE,
    )

    private val SENSITIVE_TOKEN_ENV_PATTERN = Regex(
        """.*(AWS_SECRET_ACCESS_KEY|ANTHROPIC_API_KEY|OPENAI_API_KEY|GEMINI_API_KEY|GITHUB_TOKEN|GH_TOKEN|PRIVATE_KEY|SECRET_KEY).*""",
        RegexOption.IGNORE_CASE,
    )

    private val HIGH_RISK_COMMAND_PATTERNS = listOf(
        Regex("""\brm\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r|--recursive)\b""", RegexOption.IGNORE_CASE),
        Regex("""\brm\s+(-r|-R|-rf|-fr)\b""", RegexOption.IGNORE_CASE),
        Regex("""\brmdir\b""", RegexOption.IGNORE_CASE),
        Regex("""\bmkfs\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdd\s+if=""", RegexOption.IGNORE_CASE),
        Regex("""\bfdisk\b""", RegexOption.IGNORE_CASE),
        Regex("""\bchmod\s+(-R\s+)?777\b""", RegexOption.IGNORE_CASE),
        Regex("""\bchown\s+-R\b""", RegexOption.IGNORE_CASE),
        Regex("""\bgit\s+reset\s+--hard\b""", RegexOption.IGNORE_CASE),
        Regex("""\bgit\s+reset\s+--merge\b""", RegexOption.IGNORE_CASE),
        Regex("""\bgit\s+push\b""", RegexOption.IGNORE_CASE),
        Regex("""\bgit\s+clean\s+-[a-zA-Z]*f""", RegexOption.IGNORE_CASE),
        Regex("""\bgit\s+branch\s+-[dD]\b""", RegexOption.IGNORE_CASE),
        Regex("""\bgit\s+rebase\s+--abort\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(sudo|su|doas)\b""", RegexOption.IGNORE_CASE),
        Regex(""":\(\)\{\s*:\|:&\s*\};:""", RegexOption.IGNORE_CASE), // fork bomb
    )

    private val SAFE_COMMAND_PATTERNS = listOf(
        Regex("""^git\s+(status|diff|log|show|branch|tag|rev-parse|describe|remote|config\s+--get|ls-files|check-ignore|merge-base)(\s.*)?$""", RegexOption.IGNORE_CASE),
        Regex("""^(ls|pwd|cat|head|tail|wc|stat|file|which|whereis|type|echo|printf|grep|egrep|fgrep|find|uname|whoami|tree|du|df|date|uptime|sort|uniq|awk|jq|diff)(\s.*)?$""", RegexOption.IGNORE_CASE),
        Regex("""^(node|npm|npx|python|python3|gradle|\./gradlew|cargo|rustc|go|java|javac|git|php|ruby|bun|deno)\s+(--version|-v|-V)\b.*$""", RegexOption.IGNORE_CASE),
    )

    private val NORMAL_WORKSPACE_COMMAND_PATTERNS = listOf(
        Regex("""^npm\s+(run\s+)?(build|test|lint|check|typecheck|format|dev|start|preview|compile)(\s.*)?$""", RegexOption.IGNORE_CASE),
        Regex("""^npx\s+(eslint|prettier|tsc|vitest|jest|tsx|standard|biome)(\s.*)?$""", RegexOption.IGNORE_CASE),
        Regex("""^yarn\s+(build|test|lint|format|dev|start)(\s.*)?$""", RegexOption.IGNORE_CASE),
        Regex("""^pnpm\s+(build|test|lint|format|dev|start)(\s.*)?$""", RegexOption.IGNORE_CASE),
        Regex("""^(\./)?gradlew?\s+(build|test|check|compile.*|assemble.*|lint.*|ktlint.*)(\s.*)?$""", RegexOption.IGNORE_CASE),
        Regex("""^cargo\s+(check|test|build|clippy|fmt|run)(\s.*)?$""", RegexOption.IGNORE_CASE),
        Regex("""^(pytest|python3?\s+-m\s+(pytest|unittest|py_compile|flake8)|black|mypy|ruff|isort)(\s.*)?$""", RegexOption.IGNORE_CASE),
        Regex("""^go\s+(test|build|vet|run|fmt)(\s.*)?$""", RegexOption.IGNORE_CASE),
        Regex("""^(kotlinc|javac|rustc|gcc|g\+\+|clang|make|cmake\s+--build)(\s.*)?$""", RegexOption.IGNORE_CASE),
        Regex("""^git\s+(add|commit|checkout\s+-b|switch|checkout\s+--|restore)(\s.*)?$""", RegexOption.IGNORE_CASE),
        Regex("""^(mkdir|touch|cp|mv)(\s.*)?$""", RegexOption.IGNORE_CASE),
    )

    private val RISKY_PACKAGE_INSTALL_PATTERNS = listOf(
        Regex("""^npm\s+(i|install|add)\b""", RegexOption.IGNORE_CASE),
        Regex("""^yarn\s+add\b""", RegexOption.IGNORE_CASE),
        Regex("""^pnpm\s+(add|install)\b""", RegexOption.IGNORE_CASE),
        Regex("""^pip3?\s+install\b""", RegexOption.IGNORE_CASE),
        Regex("""^poetry\s+add\b""", RegexOption.IGNORE_CASE),
        Regex("""^pipenv\s+install\b""", RegexOption.IGNORE_CASE),
        Regex("""^cargo\s+add\b""", RegexOption.IGNORE_CASE),
        Regex("""^go\s+get\b""", RegexOption.IGNORE_CASE),
        Regex("""^composer\s+(require|install)\b""", RegexOption.IGNORE_CASE),
        Regex("""^gem\s+install\b""", RegexOption.IGNORE_CASE),
        Regex("""^(apt-get|apt|pkg|brew)\s+install\b""", RegexOption.IGNORE_CASE),
    )

    private val RISKY_NETWORK_PATTERNS = listOf(
        Regex("""\b(curl|wget|fetch|ssh|nc|netcat|scp|ftp|sftp|rsync|ping|telnet|nmap|socat)\b""", RegexOption.IGNORE_CASE),
    )

    private val RISKY_PROCESS_PATTERNS = listOf(
        Regex("""\b(kill|pkill|killall|systemctl|service|nohup)\b""", RegexOption.IGNORE_CASE),
    )

    fun classify(
        toolName: String,
        command: String?,
        affectedPaths: List<String> = emptyList(),
    ): PermissionEvaluation {
        val trimmedCommand = command?.trim().orEmpty()

        // 1. Check for Credential / Secret Access in paths or command (HIGH RISK)
        for (path in affectedPaths) {
            if (SENSITIVE_FILE_PATTERN.containsMatchIn(path)) {
                return PermissionEvaluation(
                    risk = RiskLevel.HIGH,
                    reason = "Accessing sensitive credential or security file: $path",
                )
            }
        }

        if (trimmedCommand.isNotEmpty()) {
            if (SENSITIVE_FILE_PATTERN.containsMatchIn(trimmedCommand)) {
                return PermissionEvaluation(
                    risk = RiskLevel.HIGH,
                    reason = "Command references sensitive credential or secret file",
                )
            }
            if (SENSITIVE_TOKEN_ENV_PATTERN.containsMatchIn(trimmedCommand)) {
                return PermissionEvaluation(
                    risk = RiskLevel.HIGH,
                    reason = "Command inspects or exposes sensitive secret environment tokens",
                )
            }

            // 2. Check for Destructive / High Risk commands (HIGH RISK)
            for (pattern in HIGH_RISK_COMMAND_PATTERNS) {
                if (pattern.containsMatchIn(trimmedCommand)) {
                    return PermissionEvaluation(
                        risk = RiskLevel.HIGH,
                        reason = "Destructive or critical system/git command: $trimmedCommand",
                    )
                }
            }
        }

        // 3. Safe read-only tools
        if (toolName in SAFE_TOOLS && trimmedCommand.isEmpty()) {
            return PermissionEvaluation(
                risk = RiskLevel.SAFE,
                reason = "Read-only file or search inspection ($toolName)",
            )
        }

        // 4. If command is present, evaluate command categories
        if (trimmedCommand.isNotEmpty()) {
            // Check Safe Commands
            for (pattern in SAFE_COMMAND_PATTERNS) {
                if (pattern.matches(trimmedCommand)) {
                    return PermissionEvaluation(
                        risk = RiskLevel.SAFE,
                        reason = "Safe inspection or diagnostic command",
                    )
                }
            }

            // Check Risky Package Installs
            for (pattern in RISKY_PACKAGE_INSTALL_PATTERNS) {
                if (pattern.containsMatchIn(trimmedCommand)) {
                    return PermissionEvaluation(
                        risk = RiskLevel.REVIEW,
                        reason = "Dependency/package installation",
                    )
                }
            }

            // Check Risky Network Access
            for (pattern in RISKY_NETWORK_PATTERNS) {
                if (pattern.containsMatchIn(trimmedCommand)) {
                    return PermissionEvaluation(
                        risk = RiskLevel.REVIEW,
                        reason = "Network or remote access operation",
                    )
                }
            }

            // Check Risky Process Manipulation
            for (pattern in RISKY_PROCESS_PATTERNS) {
                if (pattern.containsMatchIn(trimmedCommand)) {
                    return PermissionEvaluation(
                        risk = RiskLevel.REVIEW,
                        reason = "Process or service lifecycle action",
                    )
                }
            }

            // Check Normal Workspace build/lint/test commands
            for (pattern in NORMAL_WORKSPACE_COMMAND_PATTERNS) {
                if (pattern.matches(trimmedCommand)) {
                    return PermissionEvaluation(
                        risk = RiskLevel.NORMAL,
                        reason = "Standard workspace build, test, lint, or code generation",
                    )
                }
            }

            // Unclassified bash command -> Default to REVIEW (requires approval)
            return PermissionEvaluation(
                risk = RiskLevel.REVIEW,
                reason = "Unclassified command execution ($toolName)",
            )
        }

        // 5. Normal workspace editing tools
        if (toolName in NORMAL_WORKSPACE_TOOLS) {
            return PermissionEvaluation(
                risk = RiskLevel.NORMAL,
                reason = "Workspace file edit/creation ($toolName)",
            )
        }

        // 6. Any other unknown tool -> Default to REVIEW for security
        return PermissionEvaluation(
            risk = RiskLevel.REVIEW,
            reason = "External tool invocation ($toolName)",
        )
    }

    /**
     * Safely parse raw JSON from Claude Code permission hook.
     * CRITICAL: If JSON is invalid or missing required fields, returns Result.failure so caller DENIES by default.
     */
    fun parsePermissionRequest(approvalId: String, jsonText: String): Result<RawPermissionRequest> = runCatching {
        if (jsonText.isBlank()) {
            throw IllegalArgumentException("Permission request payload is empty")
        }
        val json = JSONObject(jsonText)
        val toolName = json.optString("tool_name").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing tool_name in permission request")
        val input = json.optJSONObject("tool_input") ?: JSONObject()
        val command = input.optString("command").ifBlank { null }
        val paths = listOf("file_path", "path", "notebook_path", "target_file", "target_path")
            .mapNotNull { key -> input.optString(key).takeIf(String::isNotBlank) }
        val explanation = input.optString("description")
            .ifBlank { command.orEmpty() }
            .ifBlank { "$toolName execution in project" }

        RawPermissionRequest(
            approvalId = approvalId,
            toolName = toolName,
            command = command,
            affectedPaths = paths,
            explanation = explanation,
        )
    }
}

/**
 * Manages workspace trust and active session permissions.
 */
class WorkspaceTrustManager {

    private val trustedWorkspaces = ConcurrentHashMap.newKeySet<String>()
    private val sessionApprovals = ConcurrentHashMap<String, MutableSet<String>>()

    fun isWorkspaceTrusted(projectId: String): Boolean {
        // By default, open projects in PocketForge are trusted workspaces unless explicitly untrusted
        return !untrustedWorkspaces.contains(projectId)
    }

    private val untrustedWorkspaces = ConcurrentHashMap.newKeySet<String>()

    fun setWorkspaceTrust(projectId: String, trusted: Boolean) {
        if (trusted) {
            untrustedWorkspaces.remove(projectId)
            trustedWorkspaces.add(projectId)
        } else {
            trustedWorkspaces.remove(projectId)
            untrustedWorkspaces.add(projectId)
        }
    }

    fun allowForSession(sessionId: String, permissionKey: String) {
        sessionApprovals.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }.add(permissionKey)
    }

    fun isSessionAllowed(sessionId: String, permissionKey: String): Boolean {
        return sessionApprovals[sessionId]?.contains(permissionKey) == true
    }

    fun clearSession(sessionId: String) {
        sessionApprovals.remove(sessionId)
    }

    fun derivePermissionKey(toolName: String, command: String?): String {
        return when {
            toolName == "Bash" && !command.isNullOrBlank() -> {
                val base = command.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
                "bash:$base"
            }
            else -> toolName
        }
    }

    fun shouldAutoApprove(
        evaluation: PermissionEvaluation,
        projectId: String,
        sessionId: String,
        permissionKey: String,
    ): Boolean {
        // High risk commands CAN NEVER be auto-approved
        if (evaluation.risk == RiskLevel.HIGH) {
            return false
        }

        // Safe actions are always auto-approved
        if (evaluation.risk == RiskLevel.SAFE) {
            return true
        }

        // Normal workspace actions are auto-approved if workspace is trusted
        if (evaluation.risk == RiskLevel.NORMAL && isWorkspaceTrusted(projectId)) {
            return true
        }

        // Risky actions are auto-approved only if remembered for this active session
        if (evaluation.risk == RiskLevel.REVIEW && isSessionAllowed(sessionId, permissionKey)) {
            return true
        }

        return false
    }
}
