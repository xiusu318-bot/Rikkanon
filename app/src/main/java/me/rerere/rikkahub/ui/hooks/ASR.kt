package me.rerere.rikkahub.ui.hooks

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.providers.DashScopeASRController
import me.rerere.asr.providers.MiMoASRController
import me.rerere.asr.providers.OpenAIRealtimeASRController
import me.rerere.asr.providers.PluginASRController
import me.rerere.asr.providers.StepASRController
import me.rerere.asr.providers.VolcengineASRController
import me.rerere.plugin.host.PluginHost
import me.rerere.plugin.manifest.PluginHookKind
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

@Composable
fun rememberCustomAsrState(): CustomAsrState {
    val context = LocalContext.current
    val settingsStore = koinInject<SettingsStore>()
    val httpClient = koinInject<OkHttpClient>()
    val pluginHost = koinInject<PluginHost>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()

    val asrState = remember {
        CustomAsrStateImpl(context.applicationContext, httpClient, pluginHost)
    }

    DisposableEffect(settings.selectedASRProviderId, settings.asrProviders) {
        asrState.updateProvider(settings.getSelectedASRProvider())
        onDispose { }
    }

    DisposableEffect(asrState) {
        onDispose {
            asrState.cleanup()
        }
    }

    return asrState
}

interface CustomAsrState {
    val state: StateFlow<ASRState>
    fun start(onTranscriptChange: (String) -> Unit)
    fun stop()
    fun cleanup()
}

private class CustomAsrStateImpl(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val pluginHost: PluginHost,
) : CustomAsrState {
    private var controller: ASRController? = null
    private val idleState = MutableStateFlow(ASRState())

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAcceptsDelayedFocusGain(false)
        .build()

    override val state: StateFlow<ASRState>
        get() = controller?.state ?: idleState

    fun updateProvider(provider: ASRProviderSetting?) {
        controller?.dispose()
        controller = provider?.let { createController(it) }
        if (controller == null) {
            idleState.value = ASRState()
        }
    }

    override fun start(onTranscriptChange: (String) -> Unit) {
        val result = audioManager.requestAudioFocus(audioFocusRequest)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            controller?.start(onTranscriptChange)
        }
    }

    override fun stop() {
        controller?.stop()
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    override fun cleanup() {
        controller?.dispose()
        controller = null
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    /** 插件返回 null 表示本次没有识别结果；字符串或 `{ text }` 为识别文本。 */
    private fun extractAsrText(res: kotlinx.serialization.json.JsonElement): String = when (res) {
        JsonNull -> ""
        is JsonPrimitive -> res.content
        is JsonObject -> res.getValue("text").jsonPrimitive.content
        else -> error("语音识别插件必须返回字符串、{ text } 或 null")
    }

    private fun createController(provider: ASRProviderSetting): ASRController? {
        return when (provider) {
            is ASRProviderSetting.OpenAIRealtime -> {
                if (provider.apiKey.isBlank()) return null
                OpenAIRealtimeASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.DashScope -> {
                if (provider.apiKey.isBlank()) return null
                DashScopeASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.Volcengine -> {
                if (provider.apiKey.isBlank()) return null
                VolcengineASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.MiMo -> {
                if (provider.apiKey.isBlank()) return null
                MiMoASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.Step -> {
                if (provider.apiKey.isBlank()) return null
                StepASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.Plugin -> {
                if (provider.pluginId.isBlank() || provider.providerName.isBlank()) return null
                PluginASRController(
                    context = context,
                    provider = provider,
                    transcribeFn = { pluginId, providerName, wavBase64, sampleRate ->
                        val arg = buildJsonObject {
                            put("audio", wavBase64)
                            put("format", "wav")
                            put("sampleRate", sampleRate)
                        }
                        val res = pluginHost.callMethod(pluginId, PluginHookKind.ASR, providerName, "transcribe", listOf(arg))
                        extractAsrText(res)
                    },
                    streamStartFn = { pluginId, providerName, sampleRate ->
                        if (pluginHost.hasMethod(pluginId, PluginHookKind.ASR, providerName, "startStream")) {
                            pluginHost.callMethod(
                                pluginId, PluginHookKind.ASR, providerName, "startStream",
                                listOf(buildJsonObject { put("sampleRate", sampleRate) })
                            )
                            true
                        } else false
                    },
                    streamPushFn = { pluginId, providerName, pcmBase64, sampleRate ->
                        val res = pluginHost.callMethod(
                            pluginId, PluginHookKind.ASR, providerName, "pushAudio",
                            listOf(buildJsonObject {
                                put("audio", pcmBase64)
                                put("format", "pcm16")
                                put("sampleRate", sampleRate)
                            })
                        )
                        extractAsrText(res).takeIf { it.isNotEmpty() }
                    },
                    streamStopFn = { pluginId, providerName ->
                        if (pluginHost.hasMethod(pluginId, PluginHookKind.ASR, providerName, "stopStream")) {
                            val res = pluginHost.callMethod(pluginId, PluginHookKind.ASR, providerName, "stopStream", emptyList())
                            extractAsrText(res).takeIf { it.isNotEmpty() }
                        } else null
                    },
                )
            }
        }
    }
}
