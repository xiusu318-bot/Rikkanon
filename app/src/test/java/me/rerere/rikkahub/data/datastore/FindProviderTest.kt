package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FindProviderTest {
    @Test
    fun `findProvider returns base provider when model has no overwrite`() {
        val model = Model(modelId = "gpt-image-2", displayName = "gpt-image-2", type = ModelType.IMAGE)
        val provider = ProviderSetting.OpenAI(
            name = "OpenAI",
            models = listOf(model),
        )

        val resolved = model.findProvider(listOf(provider))

        assertEquals(provider.id, resolved?.id)
        assertEquals("OpenAI", resolved?.name)
    }

    @Test
    fun `findProvider returns overwrite instead of looking it up in settings providers`() {
        val overwrite = ProviderSetting.OpenAI(
            name = "Override",
            apiKey = "override-key",
            baseUrl = "https://override.example/v1",
        )
        val model = Model(
            modelId = "gpt-image-2",
            displayName = "gpt-image-2",
            type = ModelType.IMAGE,
            providerOverwrite = overwrite,
        )
        val provider = ProviderSetting.OpenAI(
            name = "OpenAI",
            apiKey = "base-key",
            models = listOf(model),
        )
        val providers = listOf(provider)

        val resolved = model.findProvider(providers)

        assertNotNull(resolved)
        assertEquals(overwrite.id, resolved?.id)
        assertEquals("Override", resolved?.name)
        assertEquals("https://override.example/v1", (resolved as ProviderSetting.OpenAI).baseUrl)
        assertEquals("override-key", resolved.apiKey)
        assertNotEquals(provider.id, resolved.id)
        assertNull(providers.find { it.id == resolved.id })
    }

    @Test
    fun `findProvider falls back to parent fields when overwrite leaves them blank`() {
        val overwrite = ProviderSetting.Claude(
            name = "",
            apiKey = "",
            baseUrl = "",
            promptCaching = true,
            promptCacheTtl = ClaudePromptCacheTtl.ONE_HOUR,
        )
        val model = Model(
            modelId = "claude-sonnet-4-5",
            displayName = "Claude Sonnet 4.5",
            providerOverwrite = overwrite,
        )
        val parent = ProviderSetting.OpenAI(
            name = "MainRelay",
            apiKey = "sk-parent-secret",
            baseUrl = "https://relay.example.com/v1",
            customHeaders = listOf(CustomHeader("X-Relay-Token", "abc")),
            models = listOf(model),
        )

        val resolved = model.findProvider(listOf(parent))

        assertTrue(resolved is ProviderSetting.Claude)
        val claude = resolved as ProviderSetting.Claude
        assertEquals("MainRelay", claude.name)
        assertEquals("sk-parent-secret", claude.apiKey)
        assertEquals("https://relay.example.com/v1", claude.baseUrl)
        assertTrue(claude.promptCaching)
        assertEquals(ClaudePromptCacheTtl.ONE_HOUR, claude.promptCacheTtl)
        assertEquals(listOf(CustomHeader("X-Relay-Token", "abc")), claude.customHeaders)
    }

    @Test
    fun `findProvider adapts parent baseUrl suffix when cross-protocol overwrite leaves baseUrl blank`() {
        val overwrite = ProviderSetting.Google(
            name = "",
            apiKey = "",
            baseUrl = "",
            useInteractionsApi = true,
        )
        val model = Model(
            modelId = "gemini-2.5-pro",
            displayName = "Gemini 2.5 Pro",
            providerOverwrite = overwrite,
        )
        val parent = ProviderSetting.OpenAI(
            name = "MainRelay",
            apiKey = "sk-parent-secret",
            baseUrl = "https://relay.example.com/api/v1",
            models = listOf(model),
        )

        val resolved = model.findProvider(listOf(parent)) as ProviderSetting.Google
        assertEquals("sk-parent-secret", resolved.apiKey)
        assertEquals("https://relay.example.com/api/v1beta", resolved.baseUrl)
        assertTrue(resolved.useInteractionsApi)
    }

    @Test
    fun `findProvider returns parent provider when overwrite is disabled`() {
        val overwrite = ProviderSetting.Claude(
            enabled = false,
            name = "DisabledOverride",
            apiKey = "override-key",
            baseUrl = "https://override.example/v1",
        )
        val model = Model(
            modelId = "claude-sonnet-4-5",
            displayName = "Claude Sonnet 4.5",
            providerOverwrite = overwrite,
        )
        val parent = ProviderSetting.OpenAI(
            name = "MainRelay",
            apiKey = "sk-parent",
            baseUrl = "https://relay.example.com/v1",
            models = listOf(model),
        )

        val resolved = model.findProvider(listOf(parent))
        assertEquals(parent.id, resolved?.id)
        assertTrue(resolved is ProviderSetting.OpenAI)
    }
}
