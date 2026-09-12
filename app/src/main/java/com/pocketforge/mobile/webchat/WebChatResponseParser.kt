package com.pocketforge.mobile.webchat

data class DetectedFileChange(
    val relativePath: String,
    val content: String,
    val language: String,
    val lineCount: Int,
    val selected: Boolean = true,
)

data class ParsedWebResponse(
    val rawText: String,
    val detectedFiles: List<DetectedFileChange>,
    val explanationText: String,
) {
    val hasFiles: Boolean get() = detectedFiles.isNotEmpty()
}

object WebChatResponseParser {

    private val CODE_BLOCK_PATTERN_1 = Regex(
        """```([a-zA-Z0-9_\-]+)?[:\s]+([a-zA-Z0-9_.\-/]+\.[a-zA-Z0-9]+)\s*\n([\s\S]*?)```""",
        RegexOption.MULTILINE
    )

    private val CODE_BLOCK_PATTERN_2 = Regex(
        """(?:File|Path|Target):\s*[`\*]*([a-zA-Z0-9_.\-/]+\.[a-zA-Z0-9]+)[`\*]*\s*\n\s*```([a-zA-Z0-9_\-]+)?\s*\n([\s\S]*?)```""",
        RegexOption.MULTILINE
    )

    private val CODE_BLOCK_PATTERN_3 = Regex(
        """```([a-zA-Z0-9_\-]+)?\s*\n(?:(?://|#|/\*|<!--)\s*(?:[fF]ile(?:name)?:?\s*)?([a-zA-Z0-9_.\-/]+\.[a-zA-Z0-9]+)(?:\s*(?:\*/|-->))?)\s*\n([\s\S]*?)```""",
        RegexOption.MULTILINE
    )

    private val CODE_BLOCK_PATTERN_4 = Regex(
        """(?:###|\*\*|##)\s*[`\*]*([a-zA-Z0-9_.\-/]+\.[a-zA-Z0-9]+)[`\*]*\s*(?:\*\*)?\s*\n\s*```([a-zA-Z0-9_\-]+)?\s*\n([\s\S]*?)```""",
        RegexOption.MULTILINE
    )

    private val VALID_EXTENSIONS = setOf(
        "kt", "kts", "java", "xml", "json", "toml", "gradle", "properties",
        "js", "jsx", "ts", "tsx", "html", "css", "scss", "vue", "svelte",
        "py", "sh", "bash", "c", "cpp", "h", "rs", "go", "rb", "php", "md",
        "yaml", "yml", "sql", "env"
    )

    fun parse(rawResponse: String): ParsedWebResponse {
        val trimmed = rawResponse.trim()
        if (trimmed.isBlank()) {
            return ParsedWebResponse(rawText = "", detectedFiles = emptyList(), explanationText = "")
        }

        val detected = mutableListOf<DetectedFileChange>()
        val seenPaths = mutableSetOf<String>()

        // Pattern 1: ```lang:path/to/file.ext\n...```
        for (match in CODE_BLOCK_PATTERN_1.findAll(trimmed)) {
            val lang = match.groupValues[1].trim()
            val rawPath = match.groupValues[2].trim()
            val code = match.groupValues[3]
            val cleanPath = sanitizePath(rawPath)
            if (isValidFilePath(cleanPath) && seenPaths.add(cleanPath)) {
                detected.add(
                    DetectedFileChange(
                        relativePath = cleanPath,
                        content = code,
                        language = lang.ifBlank { detectLanguage(cleanPath) },
                        lineCount = code.lines().size,
                    )
                )
            }
        }

        // Pattern 2: File: path/to/file.ext\n```lang\n...```
        for (match in CODE_BLOCK_PATTERN_2.findAll(trimmed)) {
            val rawPath = match.groupValues[1].trim()
            val lang = match.groupValues[2].trim()
            val code = match.groupValues[3]
            val cleanPath = sanitizePath(rawPath)
            if (isValidFilePath(cleanPath) && seenPaths.add(cleanPath)) {
                detected.add(
                    DetectedFileChange(
                        relativePath = cleanPath,
                        content = code,
                        language = lang.ifBlank { detectLanguage(cleanPath) },
                        lineCount = code.lines().size,
                    )
                )
            }
        }

        // Pattern 3: ```lang\n// path/to/file.ext\n...```
        for (match in CODE_BLOCK_PATTERN_3.findAll(trimmed)) {
            val lang = match.groupValues[1].trim()
            val rawPath = match.groupValues[2].trim()
            val code = match.groupValues[3]
            val cleanPath = sanitizePath(rawPath)
            if (isValidFilePath(cleanPath) && seenPaths.add(cleanPath)) {
                detected.add(
                    DetectedFileChange(
                        relativePath = cleanPath,
                        content = code,
                        language = lang.ifBlank { detectLanguage(cleanPath) },
                        lineCount = code.lines().size,
                    )
                )
            }
        }

        // Pattern 4: ### file.ext\n```lang\n...```
        for (match in CODE_BLOCK_PATTERN_4.findAll(trimmed)) {
            val rawPath = match.groupValues[1].trim()
            val lang = match.groupValues[2].trim()
            val code = match.groupValues[3]
            val cleanPath = sanitizePath(rawPath)
            if (isValidFilePath(cleanPath) && seenPaths.add(cleanPath)) {
                detected.add(
                    DetectedFileChange(
                        relativePath = cleanPath,
                        content = code,
                        language = lang.ifBlank { detectLanguage(cleanPath) },
                        lineCount = code.lines().size,
                    )
                )
            }
        }

        // Extract explanation text by stripping matching code blocks or keep full text
        var explanation = trimmed
        if (detected.isNotEmpty()) {
            // Remove code blocks from explanation to leave notes / commentary
            explanation = explanation.replace(Regex("""```[\s\S]*?```"""), "[Code block applied]")
                .lines()
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
        }

        return ParsedWebResponse(
            rawText = trimmed,
            detectedFiles = detected,
            explanationText = explanation.ifBlank { trimmed },
        )
    }

    private fun sanitizePath(path: String): String {
        return path
            .replace('\\', '/')
            .trim()
            .removePrefix("./")
            .removePrefix("/")
    }

    private fun isValidFilePath(path: String): Boolean {
        if (path.isBlank() || path.contains("..") || path.contains("://") || path.contains("*")) {
            return false
        }
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in VALID_EXTENSIONS
    }

    private fun detectLanguage(path: String): String {
        return when (path.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "xml" -> "xml"
            "ts", "tsx" -> "typescript"
            "js", "jsx" -> "javascript"
            "html" -> "html"
            "css" -> "css"
            "json" -> "json"
            "py" -> "python"
            "sh", "bash" -> "shell"
            "rs" -> "rust"
            "go" -> "go"
            else -> "text"
        }
    }
}
