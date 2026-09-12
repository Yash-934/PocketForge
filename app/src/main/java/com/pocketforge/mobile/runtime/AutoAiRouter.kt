package com.pocketforge.mobile.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.pocketforge.mobile.localmodel.engine.LocalModelEngine
import com.pocketforge.mobile.model.AiMode
import com.pocketforge.mobile.model.ProviderKind
import com.pocketforge.mobile.model.ProviderProfile

/**
 * RoutingDecision represents the calculated AI routing destination and metadata.
 */
data class RoutingDecision(
    val targetMode: AiMode,
    val modelLabel: String,
    val reason: String,
    val isFallback: Boolean = false,
    val isPaidApi: Boolean = false,
)

/**
 * AutoAiRouter intelligently routes requests based on task complexity,
 * network connectivity, and model availability while respecting user overrides.
 *
 * Rules:
 * 1. Simple explanation / small edit → Local Model (Zero API cost)
 * 2. Offline (no internet connection) → Local Model
 * 3. Complex coding / refactor / multi-file / build / test → Claude Code (Autonomous Agent)
 * 4. Claude unavailable → Local Model or API according to user settings
 * 5. Paid API safeguard → Never use paid APIs silently; clearly display model & billing status
 * 6. User manual override → Always respected immediately
 */
object AutoAiRouter {

    private val COMPLEXITY_KEYWORDS = setOf(
        "build", "compile", "gradle", "npm", "cargo", "test", "lint", "refactor",
        "architecture", "multi-file", "dependency", "install", "debug", "sandbox",
        "terminal", "run", "fullstack", "full-stack", "backend", "database",
        "migration", "git", "deploy", "setup", "initialize", "create app", "fix build",
        "fix crash", "stack trace", "investigate", "rebuild", "assemble", "automate",
        "compose", "jetpack", "activity", "service", "proot", "rootfs", "script",
    )

    private val SIMPLE_QUERY_PATTERNS = listOf(
        Regex("""^(what\s+is|what\s+are|how\s+to|how\s+do\s+i|explain|tell\s+me|why\s+does|meaning\s+of|difference\s+between|hi|hello|hey|help)\b.*""", RegexOption.IGNORE_CASE),
        Regex("""^(fix\s+typo|rename\s+|change\s+color|change\s+text|add\s+comment|format|small\s+edit|summarize|translate)\b.*""", RegexOption.IGNORE_CASE),
        Regex("""^(kya\s+hai|kaise\s+karein|batao|samjhao|help\s+karo)\b.*""", RegexOption.IGNORE_CASE),
    )

    /**
     * Evaluates the prompt and environment to determine the optimal execution mode.
     */
    fun evaluate(
        prompt: String,
        context: Context?,
        localEngine: LocalModelEngine?,
        provider: ProviderProfile,
        userSelectedMode: AiMode,
        hasInternetOverride: Boolean? = null,
    ): RoutingDecision {
        // Rule 6: Respect explicit user manual override
        if (userSelectedMode != AiMode.AUTO) {
            val isPaid = isPaidProvider(provider, userSelectedMode)
            val label = when (userSelectedMode) {
                AiMode.CLAUDE_CODE -> "Claude Code (${if (provider.kind == ProviderKind.CLAUDE) "Claude Auth" else provider.model.ifBlank { provider.kind.title }})"
                AiMode.LOCAL_MODEL -> "Local Model (${localEngine?.activeModel?.value?.name ?: "On-Device GGUF"})"
                AiMode.WEB_CHAT -> "Web Chat Companion"
                AiMode.API -> "Direct API (${provider.kind.title} - ${provider.model})"
                AiMode.AUTO -> "Smart Auto Router"
            }
            return RoutingDecision(
                targetMode = userSelectedMode,
                modelLabel = label,
                reason = "Manual override by user",
                isFallback = false,
                isPaidApi = isPaid,
            )
        }

        // Auto Routing Mode:
        val isOnline = hasInternetOverride ?: isConnectedToInternet(context)
        val hasLocalModel = localEngine?.isLoaded?.value == true && localEngine.activeModel.value != null
        val localModelName = localEngine?.activeModel?.value?.name ?: "Local GGUF"
        val trimmedPrompt = prompt.trim()

        // Rule 2: Offline rule → Local Model
        if (!isOnline) {
            return if (hasLocalModel) {
                RoutingDecision(
                    targetMode = AiMode.LOCAL_MODEL,
                    modelLabel = "Local Model ($localModelName)",
                    reason = "Offline · Device is not connected to internet (Routed to Local GGUF)",
                    isFallback = true,
                    isPaidApi = false,
                )
            } else {
                RoutingDecision(
                    targetMode = AiMode.LOCAL_MODEL,
                    modelLabel = "Local Model (No GGUF loaded)",
                    reason = "Offline · No internet connection detected. Please import and load a local GGUF model in Settings.",
                    isFallback = true,
                    isPaidApi = false,
                )
            }
        }

        // Analyze task complexity
        val isSimple = isSimplePrompt(trimmedPrompt)
        val isComplex = isComplexPrompt(trimmedPrompt)

        // Rule 1: Simple explanation / small edit → Local Model (if available)
        if (isSimple && !isComplex && hasLocalModel) {
            return RoutingDecision(
                targetMode = AiMode.LOCAL_MODEL,
                modelLabel = "Local Model ($localModelName)",
                reason = "Auto: Simple query / edit routed to on-device Local Model (Zero API cost)",
                isFallback = false,
                isPaidApi = false,
            )
        }

        // Rule 3: Complex coding / refactor / full-stack → Claude Code (Primary Agent)
        val hasClaudeConfig = provider.hasSecret || provider.kind == ProviderKind.CLAUDE
        if (hasClaudeConfig) {
            val isPaid = isPaidProvider(provider, AiMode.CLAUDE_CODE)
            val modelName = if (provider.kind == ProviderKind.CLAUDE) "Claude Auth" else provider.model.ifBlank { provider.kind.title }
            return RoutingDecision(
                targetMode = AiMode.CLAUDE_CODE,
                modelLabel = "Claude Code ($modelName)",
                reason = if (isComplex) {
                    "Auto: Complex coding / autonomous workflow routed to Claude Code"
                } else {
                    "Auto: Autonomous coding agent (Claude Code)"
                },
                isFallback = false,
                isPaidApi = isPaid,
            )
        }

        // Rule 4: Claude unavailable fallback:
        if (hasLocalModel) {
            return RoutingDecision(
                targetMode = AiMode.LOCAL_MODEL,
                modelLabel = "Local Model ($localModelName)",
                reason = "Fallback to Local Model (Claude Code API credentials not configured)",
                isFallback = true,
                isPaidApi = false,
            )
        }

        // Direct API fallback if configured
        if (provider.hasSecret) {
            return RoutingDecision(
                targetMode = AiMode.API,
                modelLabel = "Direct API (${provider.kind.title})",
                reason = "Fallback to Direct API (${provider.kind.title})",
                isFallback = true,
                isPaidApi = isPaidProvider(provider, AiMode.API),
            )
        }

        // Default to Claude Code
        return RoutingDecision(
            targetMode = AiMode.CLAUDE_CODE,
            modelLabel = "Claude Code",
            reason = "Default autonomous agent",
            isFallback = false,
            isPaidApi = false,
        )
    }

    /**
     * Determines whether a prompt is a simple explanation or small edit.
     */
    fun isSimplePrompt(prompt: String): Boolean {
        if (prompt.isBlank()) return true
        val lower = prompt.lowercase()
        if (lower.length < 150 && !lower.contains("\n")) {
            for (pattern in SIMPLE_QUERY_PATTERNS) {
                if (pattern.containsMatchIn(lower)) return true
            }
        }
        val words = lower.split(Regex("\\s+"))
        return words.size <= 12 && !isComplexPrompt(prompt)
    }

    /**
     * Determines whether a prompt requires complex multi-step coding, refactoring, or tool execution.
     */
    fun isComplexPrompt(prompt: String): Boolean {
        if (prompt.isBlank()) return false
        val lower = prompt.lowercase()
        if (lower.contains("```") || lower.lines().size > 3) return true
        return COMPLEXITY_KEYWORDS.any { keyword ->
            Regex("""\b${Regex.escape(keyword)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        }
    }

    /**
     * Checks whether the given provider profile is a usage-billed (paid) cloud API.
     */
    fun isPaidProvider(provider: ProviderProfile, mode: AiMode): Boolean {
        if (mode == AiMode.LOCAL_MODEL || mode == AiMode.WEB_CHAT) return false
        return provider.kind == ProviderKind.ANTHROPIC ||
               provider.kind == ProviderKind.LLM_ROUTER ||
               provider.kind == ProviderKind.DEEPSEEK ||
               provider.kind == ProviderKind.KIMI
    }

    /**
     * Checks if the device has an active internet connection.
     */
    fun isConnectedToInternet(context: Context?): Boolean {
        if (context == null) return true
        return runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrElse {
            // Fallback check
            runCatching {
                val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
                val network = manager.activeNetwork ?: return false
                val capabilities = manager.getNetworkCapabilities(network) ?: return false
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }.getOrDefault(true)
        }
    }
}
