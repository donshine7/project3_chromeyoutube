package com.example.project3

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings

data class ChromePlaybackSnapshot(
    val url: String,
    val videoId: String,
    val positionMs: Long
)

object ChromePlaybackReader {
    private const val PREFS = "project3_main"
    private const val KEY_SELECTED_URL = "selected_url"
    private const val KEY_LAST_URL = "last_url"

    fun canReadSessions(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.contains(context.packageName)
    }

    fun readSnapshot(context: Context, target: String = PlaybackTarget.current(context)): ChromePlaybackSnapshot? {
        val normalizedTarget = PlaybackTarget.normalize(target)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val observedUrl = ChromeCaptureStore.getObservedUrl(context)
        val selectedUrl = prefs.getString(KEY_SELECTED_URL, null) ?: prefs.getString(KEY_LAST_URL, null)
        val url = if (PlaybackTarget.isChrome(normalizedTarget)) {
            observedUrl ?: selectedUrl
        } else {
            selectedUrl ?: observedUrl
        } ?: return null
        val videoId = YoutubeUrlParser.extractVideoId(url) ?: return null
        val controllerMs = activeController(context, normalizedTarget)?.playbackState?.let(::estimatePositionMs)
        val positionMs = when {
            controllerMs != null && controllerMs >= 0L -> {
                ChromeCaptureStore.savePlaybackSnapshot(context, url, ((controllerMs + 500L) / 1000L).toInt(), ChromeCaptureStore.SOURCE_MEDIA_SESSION)
                controllerMs
            }

            else -> {
                val cachedSec = ChromeCaptureStore.getCachedPositionSec(context, videoId)
                if (cachedSec > 0) cachedSec * 1000L else YoutubeUrlParser.extractSeconds(url).toLong() * 1000L
            }
        }
        return ChromePlaybackSnapshot(url = url, videoId = videoId, positionMs = positionMs)
    }

    fun readPrecisePositionMs(context: Context, target: String = PlaybackTarget.current(context)): Long? =
        activeController(context, target)?.playbackState?.let(::estimatePositionMs)

    fun readControllerPrecisePositionMs(context: Context, target: String = PlaybackTarget.current(context)): Long? =
        readPrecisePositionMs(context, target)

    fun isControllerPlaying(context: Context, target: String = PlaybackTarget.current(context)): Boolean? {
        val state = activeController(context, target)?.playbackState?.state ?: return null
        return state == PlaybackState.STATE_PLAYING
    }

    fun seekToMs(context: Context, positionMs: Long, target: String = PlaybackTarget.current(context)): Boolean {
        val controller = activeController(context, target) ?: return false
        return runCatching {
            controller.transportControls.seekTo(positionMs.coerceAtLeast(0L))
            true
        }.getOrDefault(false)
    }

    fun play(context: Context, target: String = PlaybackTarget.current(context)): Boolean {
        val controller = activeController(context, target) ?: return false
        return runCatching {
            controller.transportControls.play()
            true
        }.getOrDefault(false)
    }

    fun pause(context: Context, target: String = PlaybackTarget.current(context)): Boolean {
        val controller = activeController(context, target) ?: return false
        return runCatching {
            controller.transportControls.pause()
            true
        }.getOrDefault(false)
    }

    private fun activeController(context: Context, target: String): MediaController? {
        if (!canReadSessions(context)) return null
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
        val component = ComponentName(context, ChromeNotificationListenerService::class.java)
        val sessions = runCatching { manager.getActiveSessions(component) }.getOrElse { emptyList() }
        val packageName = PlaybackTarget.mediaPackage(target)
        return sessions.firstOrNull { it.packageName == packageName }
    }

    private fun estimatePositionMs(state: PlaybackState?): Long? {
        state ?: return null
        val base = state.position.coerceAtLeast(0L)
        if (state.state != PlaybackState.STATE_PLAYING) return base
        val elapsed = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L)
        return (base + (elapsed * state.playbackSpeed).toLong()).coerceAtLeast(0L)
    }
}
