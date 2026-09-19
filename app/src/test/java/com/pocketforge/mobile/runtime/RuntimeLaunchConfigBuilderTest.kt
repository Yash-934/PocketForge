package com.pocketforge.mobile.runtime

import com.pocketforge.mobile.model.ProviderKind
import com.pocketforge.mobile.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RuntimeLaunchConfigBuilderTest {
    @Test
    fun gatewayProfileUsesCustomBaseUrlAndModel() {
        val config = RuntimeLaunchConfigBuilder.build(
            ProviderProfile(ProviderKind.LLM_ROUTER, "https://gateway.example/", "model-a", true),
        )

        assertEquals("https://gateway.example", config.environment["ANTHROPIC_BASE_URL"])
        assertEquals("model-a", config.environment["ANTHROPIC_MODEL"])
        assertEquals("1", config.environment["DISABLE_AUTOUPDATER"])
        assertFalse(config.arguments.contains("--dangerously-skip-permissions"))
    }

    @Test
    fun kimiUsesItsAnthropicCompatibleEndpointDirectly() {
        val config = RuntimeLaunchConfigBuilder.build(ProviderProfile(ProviderKind.KIMI))

        assertEquals("https://api.moonshot.ai/anthropic", config.environment["ANTHROPIC_BASE_URL"])
        assertEquals("kimi-k2.6", config.environment["ANTHROPIC_MODEL"])
    }

    @Test
    fun configuresEveryClaudeModelRoleAndInMemoryAuth() {
        val config = RuntimeLaunchConfigBuilder.build(
            ProviderProfile(ProviderKind.CUSTOM, "https://example.test/anthropic", "custom-model", true),
            authToken = "temporary-secret",
        )

        assertEquals("custom-model", config.environment["ANTHROPIC_DEFAULT_OPUS_MODEL"])
        assertEquals("custom-model", config.environment["ANTHROPIC_DEFAULT_SONNET_MODEL"])
        assertEquals("custom-model", config.environment["ANTHROPIC_DEFAULT_HAIKU_MODEL"])
        assertEquals("custom-model", config.environment["CLAUDE_CODE_SUBAGENT_MODEL"])
        assertEquals("1", config.environment["CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY"])
        assertEquals("temporary-secret", config.environment["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("temporary-secret", config.environment["ANTHROPIC_API_KEY"])
    }

    @Test
    fun openRouterMatchesVerifiedClaudeCodeEnvironment() {
        val config = RuntimeLaunchConfigBuilder.build(
            ProviderProfile(ProviderKind.LLM_ROUTER, "https://openrouter.ai/api/", "stealth/ox-alpha", true),
            authToken = "temporary-openrouter-secret",
        )

        assertEquals("https://openrouter.ai/api", config.environment["ANTHROPIC_BASE_URL"])
        assertEquals("temporary-openrouter-secret", config.environment["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("temporary-openrouter-secret", config.environment["OPENROUTER_API_KEY"])
        assertEquals("", config.environment["ANTHROPIC_API_KEY"])
    }

    @Test
    fun claudeSubscriptionInjectsOAuthTokenAndClearsApiKeys() {
        val config = RuntimeLaunchConfigBuilder.build(
            ProviderProfile(ProviderKind.CLAUDE, "", "default", true),
            authToken = "test-oauth-setup-token",
        )

        assertEquals("test-oauth-setup-token", config.environment["CLAUDE_CODE_OAUTH_TOKEN"])
        assertEquals("", config.environment["ANTHROPIC_API_KEY"])
        assertEquals("", config.environment["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("1", config.environment["DISABLE_AUTOUPDATER"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun claudeSubscriptionFailsWhenTokenIsMissing() {
        RuntimeLaunchConfigBuilder.build(
            ProviderProfile(ProviderKind.CLAUDE, "", "default", false),
            authToken = null,
        )
    }

    @Test
    fun nvidiaNimUsesLocalFormatGatewayAndSetsSonnetModel() {
        val config = RuntimeLaunchConfigBuilder.build(
            ProviderProfile(ProviderKind.NVIDIA_NIM, "https://integrate.api.nvidia.com/v1", "meta/llama-3.3-70b-instruct", true),
            authToken = "nv-secret-key",
            localGatewayUrl = "http://127.0.0.1:45678",
        )

        assertEquals("http://127.0.0.1:45678", config.environment["ANTHROPIC_BASE_URL"])
        assertEquals("claude-sonnet-4-6", config.environment["ANTHROPIC_MODEL"])
        assertEquals("nv-secret-key", config.environment["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("nv-secret-key", config.environment["ANTHROPIC_API_KEY"])
    }
}
