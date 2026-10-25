package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val networkSetting: NetworkSetting = NetworkSetting(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val fastModelId: Uuid = Uuid.random(),
    val fastModelReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    // 记忆整理用的模型，没设置时用快速模型
    val memoryModelId: Uuid? = null,
    val memoryModelReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val enableAutoTitle: Boolean = true,
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    // 翻译页的目标语言，存 BCP 47 语言标签
    val translateTargetLanguage: String = "zh-CN",
    val enableSuggestion: Boolean = true,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val compressModelId: Uuid = Uuid.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    // RemoteFileStore 上传临时素材用的桶，和备份的 s3Config 互不影响；items 字段在这里不使用
    val uploadS3Config: S3Config = S3Config(),
    val mediaGenerationProviders: List<MediaGenerationProviderSetting> = emptyList(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val enableSecretKeyAlias: Boolean = false,
    val secretKeys: List<SecretKeyEntry> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = false,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val sponsorAlertDismissedAt: Int = 0,
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
data class NetworkSetting(
    val userAgent: String = "",
    val proxyUrl: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val enableAutoRetry: Boolean = true,
)

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,
    @SerialName("serif")
    SERIF,
    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
enum class BackgroundEffectType {
    @SerialName("blur")
    BLUR,

    @SerialName("glass")
    GLASS,
}

@Serializable
enum class ConversationSortOrder {
    @SerialName("update_time")
    UPDATE_TIME,

    @SerialName("create_time")
    CREATE_TIME,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val updateCheckDisabledUntilEpochMillis: Long = 0L,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val enableFloatingChatWindow: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableQuoteHighlight: Boolean = false,
    val enableBlurEffect: Boolean = false,
    val backgroundEffectType: BackgroundEffectType = BackgroundEffectType.BLUR,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
    val conversationSortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

/**
 * 密钥代名词条目：
 * - [keyName]：暴露给 LLM 的代名词（如 `MY_SSH_PASS`、`GITHUB_TOKEN`）
 * - [secretValue]：仅保存在宿主本地设置中的真实密钥明文，在工具真正执行时由底层动态替换
 * - [description]：可选用途备注（在启用 `ask_key` 工具时可告知 LLM 该代名词的用途）
 */
@Serializable
data class SecretKeyEntry(
    val id: Uuid = Uuid.random(),
    val keyName: String = "",
    val secretValue: String = "",
    val description: String = "",
    val enabled: Boolean = true,
)

/**
 * 将字符串中的密钥代名词 [SecretKeyEntry.keyName] 替换为真实密钥值 [SecretKeyEntry.secretValue]。
 * 按代名词长度降序替换，避免短代名词误截断包含它的长代名词。
 */
fun Settings.applySecretKeyAliasesToString(input: String, enabled: Boolean = true): String {
    if (!enabled || input.isEmpty() || secretKeys.isEmpty()) return input
    var result = input
    secretKeys
        .filter { it.enabled && it.keyName.isNotBlank() && it.secretValue.isNotEmpty() }
        .sortedByDescending { it.keyName.length }
        .forEach { entry ->
            result = result.replace(entry.keyName, entry.secretValue)
        }
    return result
}

/**
 * 递归遍历工具入参 [JsonElement]，仅对字符串节点做密钥代名词替换，
 * 确保真实密钥中包含引号、反斜杠或换行符时不会破坏外层 JSON 结构。
 */
fun JsonElement.replaceSecretKeyAliases(settings: Settings, enabled: Boolean): JsonElement {
    if (!enabled || settings.secretKeys.isEmpty()) return this
    return when (this) {
        is JsonPrimitive -> {
            if (isString) {
                JsonPrimitive(settings.applySecretKeyAliasesToString(content, true))
            } else {
                this
            }
        }
        is JsonObject -> JsonObject(mapValues { (_, v) -> v.replaceSecretKeyAliases(settings, true) })
        is JsonArray -> JsonArray(map { it.replaceSecretKeyAliases(settings, true) })
    }
}

/**
 * 将工具输出文本中出现的真实密钥值 [SecretKeyEntry.secretValue] 反向替换回代名词 [SecretKeyEntry.keyName]，
 * 防止命令回显或报错信息将真实密钥泄露给云端模型。
 */
fun Settings.redactSecretKeyValues(output: String, enabled: Boolean = true): String {
    if (!enabled || output.isEmpty() || secretKeys.isEmpty()) return output
    var result = output
    secretKeys
        .filter { it.enabled && it.keyName.isNotBlank() && it.secretValue.isNotEmpty() }
        .sortedByDescending { it.secretValue.length }
        .forEach { entry ->
            result = result.replace(entry.secretValue, entry.keyName)
        }
    return result
}

