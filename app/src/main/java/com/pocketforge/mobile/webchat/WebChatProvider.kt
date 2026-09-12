package com.pocketforge.mobile.webchat

enum class WebChatProvider(
    val id: String,
    val displayName: String,
    val url: String,
    val description: String,
    val domain: String,
) {
    CLAUDE(
        id = "claude",
        displayName = "Claude",
        url = "https://claude.ai/new",
        description = "Anthropic Claude web chat interface (Projects, Artifacts, Code)",
        domain = "claude.ai",
    ),
    CHATGPT(
        id = "chatgpt",
        displayName = "ChatGPT",
        url = "https://chatgpt.com/",
        description = "OpenAI ChatGPT web interface (GPT-4o, Canvas, Coding)",
        domain = "chatgpt.com",
    ),
    DEEPSEEK(
        id = "deepseek",
        displayName = "DeepSeek",
        url = "https://chat.deepseek.com/",
        description = "DeepSeek Chat (V3 / R1 reasoning and code generation)",
        domain = "deepseek.com",
    ),
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini",
        url = "https://gemini.google.com/app",
        description = "Google Gemini web application (Gemini 2.0 / Pro)",
        domain = "gemini.google.com",
    ),
    HUGGINGCHAT(
        id = "huggingchat",
        displayName = "HuggingChat",
        url = "https://huggingface.co/chat/",
        description = "Open-source community models hosted by Hugging Face",
        domain = "huggingface.co",
    ),
    PERPLEXITY(
        id = "perplexity",
        displayName = "Perplexity",
        url = "https://www.perplexity.ai/",
        description = "AI search engine and code reasoning assistant",
        domain = "perplexity.ai",
    ),
    CUSTOM(
        id = "custom",
        displayName = "Custom URL",
        url = "https://",
        description = "Custom self-hosted or web AI interface (OpenWebUI, LibreChat, etc.)",
        domain = "custom",
    );

    fun resolveUrl(customInputUrl: String? = null): String {
        return if (this == CUSTOM) {
            val trimmed = customInputUrl?.trim().orEmpty()
            when {
                trimmed.isBlank() -> "https://google.com"
                trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
                else -> "https://$trimmed"
            }
        } else {
            url
        }
    }

    companion object {
        val DEFAULT = CLAUDE

        fun fromId(id: String?): WebChatProvider {
            if (id.isNullOrBlank()) return DEFAULT
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
        }
    }
}
