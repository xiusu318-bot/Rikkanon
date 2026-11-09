package me.rerere.asr.providers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.appendAmplitude
import me.rerere.asr.calculateRmsAmplitude
import java.io.ByteArrayOutputStream
import java.util.Collections

private const val TAG = "PluginASR"
private const val MAX_SEGMENT_BYTES = 6 * 1024 * 1024

class PluginASRController(
    private val context: Context,
    private val provider: ASRProviderSetting.Plugin,
    private val transcribeFn: suspend (pluginId: String, providerName: String, wavBase64: String, sampleRate: Int) -> String,
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var recorderJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var onTranscriptChange: ((String) -> Unit)? = null
    private var flushJob: Job? = null

    private val bufferLock = Any()
    private var currentBuffer = ByteArrayOutputStream()
    private var segmentStartElapsedMs = 0L
    private val completedTranscripts = Collections.synchronizedList(mutableListOf<String>())

    override fun start(onTranscriptChange: (String) -> Unit) {
        if (state.value.isRecording) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setError("Microphone permission is required")
            return
        }
        this.onTranscriptChange = onTranscriptChange
        synchronized(bufferLock) {
            currentBuffer = ByteArrayOutputStream()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
        }
        completedTranscripts.clear()
        flushJob = null
        _state.update { ASRState(status = ASRStatus.Listening, isAvailable = true) }
        startRecorder()
    }

    override fun stop() {
        recorderJob?.cancel()
        releaseRecorder()
        _state.update { it.copy(status = ASRStatus.Stopping) }
        scope.launch(Dispatchers.IO) {
            try {
                flushJob?.join()
                flushSegment()
            } catch (e: Exception) {
                Log.e(TAG, "Final flush failed", e)
                setError(e.message ?: "Plugin ASR final flush failed")
            } finally {
                _state.update { it.copy(status = ASRStatus.Idle) }
            }
        }
    }

    override fun dispose() {
        recorderJob?.cancel()
        flushJob?.cancel()
        releaseRecorder()
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun startRecorder() {
        recorderJob?.cancel()
        recorderJob = scope.launch(Dispatchers.IO) {
            val sampleRate = provider.sampleRate
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = minBufferSize.coerceAtLeast(sampleRate / 10 * 2).coerceAtLeast(4096)
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
            audioRecord = recorder
            try {
                recorder.startRecording()
                val buffer = ByteArray(bufferSize)
                val segmentMs = provider.segmentDurationSec.coerceAtLeast(0) * 1000L
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val amplitude = calculateRmsAmplitude(buffer, read)
                        _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }
                        val shouldFlush = synchronized(bufferLock) {
                            currentBuffer.write(buffer, 0, read)
                            if (segmentMs <= 0) {
                                currentBuffer.size() >= MAX_SEGMENT_BYTES
                            } else {
                                val elapsed = SystemClock.elapsedRealtime() - segmentStartElapsedMs
                                currentBuffer.size() >= MAX_SEGMENT_BYTES || elapsed >= segmentMs
                            }
                        }
                        if (shouldFlush) triggerFlush()
                    } else if (read < 0) {
                        throw IllegalStateException("AudioRecord read error: $read")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio recording failed", e)
                setError(e.message ?: "Audio recording failed")
            } finally {
                releaseRecorder()
            }
        }
    }

    private fun triggerFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch(Dispatchers.IO) {
            runCatching { flushSegment() }
                .onFailure { Log.e(TAG, "Segment flush failed", it) }
        }
    }

    private suspend fun flushSegment() {
        val pcmBytes = synchronized(bufferLock) {
            if (currentBuffer.size() == 0) return
            val bytes = currentBuffer.toByteArray()
            currentBuffer = ByteArrayOutputStream()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
            bytes
        }
        val wavBytes = pcm16ToWav(pcmBytes, provider.sampleRate, 1, 16)
        val b64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val text = transcribeFn(provider.pluginId, provider.providerName, b64, provider.sampleRate).trim()
        if (text.isNotEmpty()) {
            completedTranscripts.add(text)
            val transcript = completedTranscripts.filter { it.isNotBlank() }.joinToString(" ")
            _state.update { it.copy(transcript = transcript, errorMessage = null) }
            scope.launch { onTranscriptChange?.invoke(transcript) }
        }
    }

    private fun setError(message: String) {
        _state.update { it.copy(status = ASRStatus.Error, errorMessage = message) }
    }

    private fun releaseRecorder() {
        recorderJob = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    companion object {
        private fun pcm16ToWav(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val dataSize = pcm.size
            val out = ByteArrayOutputStream(44 + dataSize)
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeIntLE(out, 36 + dataSize)
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            writeIntLE(out, 16)
            writeShortLE(out, 1)
            writeShortLE(out, channels)
            writeIntLE(out, sampleRate)
            writeIntLE(out, byteRate)
            writeShortLE(out, blockAlign)
            writeShortLE(out, bitsPerSample)
            out.write("data".toByteArray(Charsets.US_ASCII))
            writeIntLE(out, dataSize)
            out.write(pcm)
            return out.toByteArray()
        }

        private fun writeIntLE(out: ByteArrayOutputStream, value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }

        private fun writeShortLE(out: ByteArrayOutputStream, value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }
    }
}
