package com.pocketforge.mobile.webchat

import com.pocketforge.mobile.model.Project
import com.pocketforge.mobile.model.WorkspaceEntry

enum class PromptContextMode(
    val title: String,
    val description: String,
) {
    SMART_CONTEXT(
        title = "Smart Context",
        description = "Project overview, key file paths, and format guidelines for easy importing",
    ),
    DEBUG_FIX(
        title = "Debug / Fix Errors",
        description = "Includes latest terminal logs or build failures alongside your request",
    ),
    ACTIVE_FILE(
        title = "Active File Only",
        description = "Includes the full content of your currently open file",
    ),
    RAW_PROMPT(
        title = "Raw Prompt",
        description = "Send only your question/prompt with no PocketForge boilerplate",
    ),
}

data class PreparedPromptResult(
    val prompt: String,
    val characterCount: Int,
    val estimatedTokens: Int,
    val mode: PromptContextMode,
    val summary: String,
)

object WebChatPromptBuilder {

    const val CODE_CONVENTION_INSTRUCTION =
        "PocketForge Auto-Import Convention:\n" +
        "When providing code modifications or new files, please format them with the relative file path like:\n" +
        "```<language>:<relative_path>\n" +
        "// complete or updated file code\n" +
        "```\n" +
        "or specify 'File: <relative_path>' right above the code block so PocketForge can automatically detect and apply the changes."

    fun buildPrompt(
        userQuery: String,
        mode: PromptContextMode = PromptContextMode.SMART_CONTEXT,
        project: Project? = null,
        workspaceFiles: List<WorkspaceEntry> = emptyList(),
        activeFilePath: String? = null,
        activeFileContent: String? = null,
        terminalErrors: String? = null,
    ): PreparedPromptResult {
        val cleanQuery = userQuery.trim()
        val text = when (mode) {
            PromptContextMode.RAW_PROMPT -> cleanQuery

            PromptContextMode.ACTIVE_FILE -> buildString {
                if (project != null) {
                    appendLine("# Project: ${project.name} (${project.slug})")
                }
                if (!activeFilePath.isNullOrBlank() && !activeFileContent.isNullOrBlank()) {
                    appendLine("## File: $activeFilePath")
                    appendLine("```")
                    appendLine(activeFileContent.trimEnd())
                    appendLine("```")
                    appendLine()
                }
                appendLine(CODE_CONVENTION_INSTRUCTION)
                appendLine()
                appendLine("## Task / Request:")
                appendLine(cleanQuery.ifBlank { "Please review and improve this file." })
            }

            PromptContextMode.DEBUG_FIX -> buildString {
                if (project != null) {
                    appendLine("# Project: ${project.name} (${project.slug})")
                    appendLine("Project Kind: ${project.kind}")
                }
                if (!terminalErrors.isNullOrBlank()) {
                    appendLine()
                    appendLine("## Recent Terminal Output / Errors:")
                    appendLine("```")
                    appendLine(terminalErrors.takeLast(3000).trim())
                    appendLine("```")
                }
                appendLine()
                appendLine(CODE_CONVENTION_INSTRUCTION)
                appendLine()
                appendLine("## Problem Description / Task:")
                appendLine(cleanQuery.ifBlank { "Please help diagnose and fix this error." })
            }

            PromptContextMode.SMART_CONTEXT -> buildString {
                if (project != null) {
                    appendLine("# PocketForge Workspace Context")
                    appendLine("- Project: ${project.name}")
                    appendLine("- Identifier: ${project.slug}")
                    appendLine("- Environment: ${project.kind} on Android")
                    appendLine()
                }

                val relevantFiles = workspaceFiles
                    .filter { !it.isDirectory && !it.path.startsWith(".") }
                    .take(35)
                if (relevantFiles.isNotEmpty()) {
                    appendLine("## Workspace Files:")
                    relevantFiles.forEach { file ->
                        appendLine("- ${file.path}")
                    }
                    appendLine()
                }

                if (!activeFilePath.isNullOrBlank() && !activeFileContent.isNullOrBlank()) {
                    appendLine("## Active Open File: $activeFilePath")
                    appendLine("```")
                    // Truncate if excessively huge for clipboard
                    if (activeFileContent.length > 8000) {
                        appendLine(activeFileContent.take(8000))
                        appendLine("// ... [truncated for clipboard]")
                    } else {
                        appendLine(activeFileContent.trimEnd())
                    }
                    appendLine("```")
                    appendLine()
                }

                appendLine(CODE_CONVENTION_INSTRUCTION)
                appendLine()
                appendLine("## User Request:")
                appendLine(cleanQuery.ifBlank { "Please review the project structure and suggest next steps." })
            }
        }

        val chars = text.length
        val estimatedTokens = (chars / 4).coerceAtLeast(1)
        val summary = when (mode) {
            PromptContextMode.SMART_CONTEXT -> "Includes project overview and file list"
            PromptContextMode.DEBUG_FIX -> "Includes terminal error logs"
            PromptContextMode.ACTIVE_FILE -> "Includes $activeFilePath"
            PromptContextMode.RAW_PROMPT -> "Raw user query only"
        }

        return PreparedPromptResult(
            prompt = text,
            characterCount = chars,
            estimatedTokens = estimatedTokens,
            mode = mode,
            summary = summary,
        )
    }
}
