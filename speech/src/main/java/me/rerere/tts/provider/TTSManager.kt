package me.rerere.tts.provider

import android.content.Context
import kotlinx.coroutines.flow.Flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.providers.ElevenLabsTTSProvider
import me.rerere.tts.provider.providers.FishAudioTTSProvider
import me.rerere.tts.provider.providers.GeminiTTSProvider
import me.rerere.tts.provider.providers.GroqTTSProvider
import me.rerere.tts.provider.providers.MiMoTTSProvider
import me.rerere.tts.provider.providers.MiniMaxTTSProvider
import me.rerere.tts.provider.providers.OpenAITTSProvider
import me.rerere.tts.provider.providers.QwenTTSProvider
import me.rerere.tts.provider.providers.StepTTSProvider
import me.rerere.tts.provider.providers.SystemTTSProvider
import me.rerere.tts.provider.providers.VolcengineTTSProvider
import me.rerere.tts.provider.providers.XAITTSProvider
import okhttp3.OkHttpClient

class TTSManager(
    private val context: Context,
    baseClient: OkHttpClient = OkHttpClient(),
    var pluginDelegate: TTSProvider<TTSProviderSetting.Plugin>? = null,
) {
    private val openAIProvider = OpenAITTSProvider(baseClient)
    private val geminiProvider = GeminiTTSProvider(baseClient)
    private val systemProvider = SystemTTSProvider()
    private val miniMaxProvider = MiniMaxTTSProvider(baseClient)
    private val qwenProvider = QwenTTSProvider(baseClient)
    private val groqProvider = GroqTTSProvider(baseClient)
    private val xaiProvider = XAITTSProvider(baseClient)
    private val miMoProvider = MiMoTTSProvider(baseClient)
    private val stepProvider = StepTTSProvider(baseClient)
    private val elevenLabsProvider = ElevenLabsTTSProvider(baseClient)
    private val fishAudioProvider = FishAudioTTSProvider(baseClient)
    private val volcengineProvider = VolcengineTTSProvider(baseClient)

    fun generateSpeech(
        providerSetting: TTSProviderSetting,
        request: TTSRequest
    ): Flow<AudioChunk> {
        return when (providerSetting) {
            is TTSProviderSetting.OpenAI -> openAIProvider.generateSpeech(context, providerSetting, request)
            is TTSProviderSetting.Gemini -> geminiProvider.generateSpeech(context, providerSetting, request)
            is TTSProviderSetting.SystemTTS -> systemProvider.generateSpeech(context, providerSetting, request)
            is TTSProviderSetting.MiniMax -> miniMaxProvider.generateSpeech(context, providerSetting, request)
            is TTSProviderSetting.Qwen -> qwenProvider.generateSpeech(context, providerSetting, request)
            is TTSProviderSetting.Groq -> groqProvider.generateSpeech(context, providerSetting, request)
            is TTSProviderSetting.XAI -> xaiProvider.generateSpeech(context, providerSetting, request)
            is TTSProviderSetting.MiMo -> miMoProvider.generateSpeech(context, providerSetting, request)
            is TTSProviderSetting.ElevenLabs -> elevenLabsProvider.generateSpeech(context, providerSetting, request)
            is TTSProviderSetting.FishAudio -> fishAudioProvider.generateSpeech(context, providerSetting, request)
            is TTSProviderSetting.Step -> stepProvider.generateSpeech(context, providerSetting, request)
            is TTSProviderSetting.Volcengine -> volcengineProvider.generateSpeech(context, providerSetting, request)
            is TTSProviderSetting.Plugin -> (pluginDelegate ?: error("插件 TTS 未初始化"))
                .generateSpeech(context, providerSetting, request)
        }
    }

    /**
     * 返回该 provider 硬编码的语气标记引导提示词（默认空）。
     * 供 text_to_speech 工具注入 system prompt 使用。
     */
    fun getPromptGuidance(providerSetting: TTSProviderSetting): String {
        return when (providerSetting) {
            is TTSProviderSetting.OpenAI -> openAIProvider.promptGuidance
            is TTSProviderSetting.Gemini -> geminiProvider.promptGuidance
            is TTSProviderSetting.SystemTTS -> systemProvider.promptGuidance
            is TTSProviderSetting.MiniMax -> miniMaxProvider.promptGuidance
            is TTSProviderSetting.Qwen -> qwenProvider.promptGuidance
            is TTSProviderSetting.Groq -> groqProvider.promptGuidance
            is TTSProviderSetting.XAI -> xaiProvider.promptGuidance
            is TTSProviderSetting.MiMo -> miMoProvider.promptGuidance
            is TTSProviderSetting.ElevenLabs -> elevenLabsProvider.promptGuidance
            is TTSProviderSetting.FishAudio -> fishAudioProvider.promptGuidance
            is TTSProviderSetting.Step -> stepProvider.promptGuidance
            is TTSProviderSetting.Volcengine -> volcengineProvider.promptGuidance
            is TTSProviderSetting.Plugin -> pluginDelegate?.promptGuidance.orEmpty()
        }
    }
}
