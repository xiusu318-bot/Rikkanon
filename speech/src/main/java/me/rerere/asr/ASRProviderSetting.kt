package me.rerere.asr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
sealed class ASRProviderSetting {
    abstract val id: Uuid
    abstract val name: String

    // Describes our adapter, not every API offered by this vendor.
    val supportsServerVadVoiceMode: Boolean
        get() = this is OpenAIRealtime || this is DashScope || this is Volcengine

    abstract fun copyProvider(
        id: Uuid = this.id,
        name: String = this.name,
    ): ASRProviderSetting

    @Serializable
    @SerialName("openai_realtime")
    data class OpenAIRealtime(
        override val id: Uuid = Uuid.random(),
        override val name: String = "OpenAI Realtime ASR",
        val apiKey: String = "",
        val websocketUrl: String = "wss://api.openai.com/v1/realtime?intent=transcription",
        val model: String = "gpt-4o-transcribe",
        val language: String = "",
        val prompt: String = "",
        val sampleRate: Int = 24000,
        val vadThreshold: Float = 0.5f,
        val prefixPaddingMs: Int = 300,
        val silenceDurationMs: Int = 500,
    ) : ASRProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): ASRProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("dashscope")
    data class DashScope(
        override val id: Uuid = Uuid.random(),
        override val name: String = "DashScope ASR",
        val apiKey: String = "",
        val websocketUrl: String = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
        val model: String = "qwen3-asr-flash-realtime",
        val language: String = "",
        val sampleRate: Int = 16000,
        val vadThreshold: Float = 0.0f,
        val silenceDurationMs: Int = 400,
    ) : ASRProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): ASRProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("volcengine")
    data class Volcengine(
        override val id: Uuid = Uuid.random(),
        override val name: String = "Volcengine ASR",
        val apiKey: String = "",
        val websocketUrl: String = VOLCENGINE_ASR_WEBSOCKET_URL,
        val resourceId: String = "volc.seedasr.sauc.duration",
        val language: String = "",
        val silenceDurationMs: Int = 800,
    ) : ASRProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): ASRProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("mimo")
    data class MiMo(
        override val id: Uuid = Uuid.random(),
        override val name: String = "MiMo ASR",
        val apiKey: String = "",
        val baseUrl: String = "https://api.xiaomimimo.com/v1",
        val model: String = "mimo-v2.5-asr",
        val language: String = "auto",
        val sampleRate: Int = 16000,
        val segmentDurationSec: Int = 30,
    ) : ASRProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): ASRProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("step")
    data class Step(
        override val id: Uuid = Uuid.random(),
        override val name: String = "Step ASR",
        val apiKey: String = "",
        val baseUrl: String = "https://api.stepfun.com",
        val model: String = "stepaudio-2.5-asr",
        val language: String = "auto",
        val sampleRate: Int = 16000,
        val segmentDurationSec: Int = 30,
        val enableItn: Boolean = true,
        val enableTimestamp: Boolean = false,
        val hotwords: List<String> = emptyList(),
    ) : ASRProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): ASRProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("plugin")
    data class Plugin(
        override val id: Uuid = Uuid.random(),
        override val name: String = "Plugin ASR",
        val pluginId: String = "",
        val providerName: String = "",
        val sampleRate: Int = 16000,
        val segmentDurationSec: Int = 30,
    ) : ASRProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): ASRProviderSetting = copy(id = id, name = name)
    }

    companion object {
        val Types by lazy {
            listOf(
                OpenAIRealtime::class,
                DashScope::class,
                Volcengine::class,
                MiMo::class,
                Step::class,
            )
        }
    }
}

const val VOLCENGINE_ASR_WEBSOCKET_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
