package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.uuid.Uuid

/** 所有提供商都还没有添加模型，视为尚未完成配置。 */
fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

/**
 * 按 ID 查找模型。
 *
 * @param fallback [uuid] 为空或已找不到对应模型时改用的模型 ID
 */
fun Settings.findModelById(uuid: Uuid?, fallback: Uuid? = null): Model? {
    if (uuid == null && fallback == null) return null
    return uuid?.let { this.providers.findModelById(it) }
        ?: fallback?.let { this.providers.findModelById(it) }
}

/** 在所有提供商的模型里按 ID 查找。 */
fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

/**
 * 当前助手的聊天模型，助手没有单独指定时用全局聊天模型。
 *
 * 已持久化的会话有自己的配置快照，应改用 [me.rerere.rikkahub.data.model.getChatModelOf]。
 */
fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

/**
 * 当前选中的助手，选中的 ID 已不存在时回退到第一个助手。
 *
 * 已持久化的会话应改用 [me.rerere.rikkahub.data.model.getAssistantOf]。
 */
fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

/** 按 ID 查找助手，不存在时返回 null，不做回退。 */
fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

/** 该助手绑定的快捷消息，顺序跟随全局快捷消息列表。 */
fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

/** 当前选中的 TTS 提供商，选中的 ID 已不存在时回退到第一个。 */
fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    return selectedTTSProviderId?.let { id ->
        ttsProviders.find { it.id == id }
    } ?: ttsProviders.firstOrNull()
}

/** 当前选中的 ASR 提供商，未选择或选中的 ID 已不存在时回退到第一个。 */
fun Settings.getSelectedASRProvider(): ASRProviderSetting? {
    return selectedASRProviderId?.let { id ->
        asrProviders.find { it.id == id }
    } ?: asrProviders.firstOrNull()
}

/**
 * 查找模型实际使用的提供商配置。
 *
 * 模型设置了 [Model.providerOverwrite] 且该覆盖配置处于启用状态时，返回与父提供商合并回退后的覆盖配置（不带模型列表）：
 * 覆盖配置中留空的必填/通用字段（如 `name`、`apiKey`、`baseUrl`、路径、服务账号信息等）自动回退继承父提供商。
 *
 * @param checkOverwrite 为 false 时忽略覆盖配置，始终返回模型所属的提供商
 * @return 模型不属于 [providers] 里任何提供商时返回 null，即使它带有覆盖配置
 */
fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null && providerOverwrite.enabled) {
        return providerOverwrite.resolveWithParent(provider)
    }
    return provider
}

/**
 * 将模型的提供商重写（子渠道）与父提供商合并：
 * 子渠道中留空的 `name`、`apiKey`、`baseUrl`、API 路径、Vertex 服务账号字段等自动回退到父提供商对应值，
 * 父提供商的 `customHeaders` 与 `balanceOption` 也会自动继承。
 */
fun ProviderSetting.resolveWithParent(parent: ProviderSetting): ProviderSetting {
    val resolvedName = this.name.ifBlank { parent.name }
    val resolvedApiKey = this.apiKeyOrBlank().ifBlank { parent.apiKeyOrBlank() }
    val resolvedBaseUrl = this.resolveInheritedBaseUrl(parent)
    val resolvedHeaders = if (parent.customHeaders.isEmpty()) {
        this.customHeaders
    } else if (this.customHeaders.isEmpty()) {
        parent.customHeaders
    } else {
        val overrideNames = this.customHeaders.mapTo(HashSet()) { it.name.lowercase() }
        parent.customHeaders.filter { it.name.lowercase() !in overrideNames } + this.customHeaders
    }
    val resolvedBalance = if (this.balanceOption.enabled) this.balanceOption else parent.balanceOption

    return when (this) {
        is ProviderSetting.OpenAI -> {
            val parentOpenAI = parent as? ProviderSetting.OpenAI
            this.copy(
                name = resolvedName,
                models = emptyList(),
                customHeaders = resolvedHeaders,
                balanceOption = resolvedBalance,
                apiKey = resolvedApiKey,
                baseUrl = resolvedBaseUrl,
                chatCompletionsPath = this.chatCompletionsPath.ifBlank {
                    parentOpenAI?.chatCompletionsPath?.takeIf { it.isNotBlank() } ?: "/chat/completions"
                },
                responsesPath = this.responsesPath.ifBlank {
                    parentOpenAI?.responsesPath?.takeIf { it.isNotBlank() } ?: "/responses"
                },
            )
        }

        is ProviderSetting.Claude -> this.copy(
            name = resolvedName,
            models = emptyList(),
            customHeaders = resolvedHeaders,
            balanceOption = resolvedBalance,
            apiKey = resolvedApiKey,
            baseUrl = resolvedBaseUrl,
        )

        is ProviderSetting.Google -> {
            val parentGoogle = parent as? ProviderSetting.Google
            this.copy(
                name = resolvedName,
                models = emptyList(),
                customHeaders = resolvedHeaders,
                balanceOption = resolvedBalance,
                apiKey = resolvedApiKey,
                baseUrl = resolvedBaseUrl,
                privateKey = this.privateKey.ifBlank { parentGoogle?.privateKey.orEmpty() },
                serviceAccountEmail = this.serviceAccountEmail.ifBlank { parentGoogle?.serviceAccountEmail.orEmpty() },
                location = this.location.ifBlank {
                    parentGoogle?.location?.takeIf { it.isNotBlank() } ?: "us-central1"
                },
                projectId = this.projectId.ifBlank { parentGoogle?.projectId.orEmpty() },
            )
        }

        is ProviderSetting.Plugin -> {
            val parentPlugin = parent as? ProviderSetting.Plugin
            this.copy(
                name = resolvedName,
                models = emptyList(),
                customHeaders = resolvedHeaders,
                balanceOption = resolvedBalance,
                pluginId = this.pluginId.ifBlank { parentPlugin?.pluginId.orEmpty() },
                providerName = this.providerName.ifBlank { parentPlugin?.providerName.orEmpty() },
                apiKey = resolvedApiKey,
                baseUrl = resolvedBaseUrl,
            )
        }
    }
}

/**
 * 计算子渠道实际生效的 Base URL：
 * 若子渠道自身已填写非空 Base URL 则直接使用；若留空则从父提供商继承（跨协议时自动转换第三方网关的 `/v1` ⇄ `/v1beta` 后缀）。
 */
fun ProviderSetting.resolveInheritedBaseUrl(parent: ProviderSetting): String {
    val own = this.baseUrlOrBlank().trim()
    if (own.isNotBlank()) return own

    val targetDefault = this.defaultProtocolBaseUrl()
    val parentUrl = parent.baseUrlOrBlank().trim()
    if (parentUrl.isBlank()) return targetDefault

    return if (this::class == parent::class) {
        parentUrl
    } else {
        parentUrl.convertToTargetBaseUrl(targetDefault)
    }
}

/**
 * 基于父提供商创建一份默认留空可继承字段（名称、API Key、Base URL）的重写草稿，
 * 使子渠道默认动态跟随父提供商的通用连接属性，同时保留 API 路径等带默认值的选项。
 */
fun ProviderSetting.createBlankOverride(): ProviderSetting = when (this) {
    is ProviderSetting.OpenAI -> this.copy(
        id = Uuid.random(),
        enabled = true,
        name = "",
        models = emptyList(),
        builtIn = false,
        description = {},
        shortDescription = {},
        apiKey = "",
        baseUrl = "",
    )

    is ProviderSetting.Claude -> this.copy(
        id = Uuid.random(),
        enabled = true,
        name = "",
        models = emptyList(),
        builtIn = false,
        description = {},
        shortDescription = {},
        apiKey = "",
        baseUrl = "",
    )

    is ProviderSetting.Google -> this.copy(
        id = Uuid.random(),
        enabled = true,
        name = "",
        models = emptyList(),
        builtIn = false,
        description = {},
        shortDescription = {},
        apiKey = "",
        baseUrl = "",
        privateKey = "",
        serviceAccountEmail = "",
        projectId = "",
    )

    is ProviderSetting.Plugin -> this.copy(
        id = Uuid.random(),
        enabled = true,
        name = "",
        models = emptyList(),
        builtIn = false,
        description = {},
        shortDescription = {},
        apiKey = "",
        baseUrl = "",
    )
}

internal fun ProviderSetting.apiKeyOrBlank(): String = when (this) {
    is ProviderSetting.OpenAI -> this.apiKey
    is ProviderSetting.Google -> this.apiKey
    is ProviderSetting.Claude -> this.apiKey
    is ProviderSetting.Plugin -> this.apiKey
}

internal fun ProviderSetting.baseUrlOrBlank(): String = when (this) {
    is ProviderSetting.OpenAI -> this.baseUrl
    is ProviderSetting.Google -> this.baseUrl
    is ProviderSetting.Claude -> this.baseUrl
    is ProviderSetting.Plugin -> this.baseUrl
}

internal fun ProviderSetting.defaultProtocolBaseUrl(): String = when (this) {
    is ProviderSetting.OpenAI -> ProviderSetting.OpenAI().baseUrl
    is ProviderSetting.Google -> ProviderSetting.Google().baseUrl
    is ProviderSetting.Claude -> ProviderSetting.Claude().baseUrl
    is ProviderSetting.Plugin -> ""
}

internal fun String.convertToTargetBaseUrl(targetDefaultBaseUrl: String): String {
    val sourceUrl = this.toHttpUrlOrNull() ?: return this
    val sourceHost = sourceUrl.host.lowercase()
    if (sourceHost in OFFICIAL_PROVIDER_HOSTS) return targetDefaultBaseUrl
    val targetUrl = targetDefaultBaseUrl.toHttpUrlOrNull() ?: return this
    val convertedPath = sourceUrl.encodedPath.convertToTargetPath(targetUrl.encodedPath)
    return sourceUrl.newBuilder().encodedPath(convertedPath).build().toString()
}

private fun String.convertToTargetPath(targetPath: String): String {
    val source = this.normalizePath()
    val target = targetPath.normalizePath()
    val replaced = when {
        source.lowercase().endsWith(V1_BETA_SUFFIX) -> source.dropLast(V1_BETA_SUFFIX.length) + target
        source.lowercase().endsWith(V1_SUFFIX) -> source.dropLast(V1_SUFFIX.length) + target
        source.isBlank() -> target
        else -> source + target
    }
    return replaced.normalizePath()
}

private fun String.normalizePath(): String {
    val value = this.trim()
    if (value.isEmpty() || value == "/") return ""
    val path = if (value.startsWith("/")) value else "/$value"
    return path.trimEnd('/')
}

private const val OPENAI_OFFICIAL_HOST = "api.openai.com"
private const val GOOGLE_OFFICIAL_HOST = "generativelanguage.googleapis.com"
private const val CLAUDE_OFFICIAL_HOST = "api.anthropic.com"
private const val V1_SUFFIX = "/v1"
private const val V1_BETA_SUFFIX = "/v1beta"
private val OFFICIAL_PROVIDER_HOSTS = setOf(
    OPENAI_OFFICIAL_HOST,
    GOOGLE_OFFICIAL_HOST,
    CLAUDE_OFFICIAL_HOST,
)

/** 模型所属的提供商，即模型列表里包含它的那个。 */
private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}
