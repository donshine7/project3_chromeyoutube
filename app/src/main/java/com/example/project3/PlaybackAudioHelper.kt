package com.example.project3

import android.content.Context
import android.media.AudioManager

object PlaybackAudioHelper {
    fun ensureAudible(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) }
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (current <= 0) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max / 4).coerceAtLeast(1), 0)
        }
    }
}
