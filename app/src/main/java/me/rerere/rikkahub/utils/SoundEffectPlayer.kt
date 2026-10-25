package me.rerere.rikkahub.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import androidx.annotation.RawRes

class SoundEffectPlayer(private val context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val loadedSounds = mutableMapOf<Int, Int>()
    private val readySounds = mutableSetOf<Int>()

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                readySounds.add(sampleId)
                val pending = pendingPlay.remove(sampleId)
                if (pending != null) {
                    soundPool.play(sampleId, pending, pending, 0, 0, 1f)
                }
            }
        }
    }

    private val pendingPlay = mutableMapOf<Int, Float>()

    fun preload(@RawRes vararg resIds: Int) {
        for (resId in resIds) {
            if (resId !in loadedSounds) {
                runCatching {
                    soundPool.load(context, resId, 1)
                }.onSuccess { soundId ->
                    if (soundId != 0) loadedSounds[resId] = soundId
                }.onFailure { e ->
                    Log.w("SoundEffectPlayer", "Failed to preload sound resource $resId", e)
                }
            }
        }
    }

    fun play(@RawRes resId: Int, volume: Float = 1f) {
        val soundId = loadedSounds[resId] ?: runCatching {
            soundPool.load(context, resId, 1)
        }.onFailure { e ->
            Log.w("SoundEffectPlayer", "Failed to load sound resource $resId", e)
        }.getOrNull()?.takeIf { it != 0 }?.also {
            loadedSounds[resId] = it
        } ?: return

        if (soundId in readySounds) {
            soundPool.play(soundId, volume, volume, 0, 0, 1f)
        } else {
            pendingPlay[soundId] = volume
        }
    }

    fun release() {
        soundPool.release()
        loadedSounds.clear()
        readySounds.clear()
        pendingPlay.clear()
    }
}
