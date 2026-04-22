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
    private const val CHROME_PACKAGE = "com.android.chrome"
    private const val PREFS = "project3_main"
    private const val KEY_SELECTED_URL = "selected_url"
    private const val KEY_LAST_URL = "last_url"

    fun canReadSessions(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.contains(context.packageName)
    }

    fun readSnapshot(context: Context): ChromePlaybackSnapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val observedUrl = ChromeCaptureStore.getObservedUrl(context)
        val selectedUrl = prefs.getString(KEY_SELECTED_URL, null) ?: prefs.getString(KEY_LAST_URL, null)
        val url = observedUrl ?: selectedUrl ?: return null
        val videoId = YoutubeUrlParser.extractVideoId(url) ?: return null
        val controllerMs = activeChromeController(context)?.playbackState?.let(::estimatePositionMs)
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

    fun readPrecisePositionMs(context: Context): Long? =
        activeChromeController(context)?.playbackState?.let(::estimatePositionMs)

    fun readControllerPrecisePositionMs(context: Context): Long? = readPrecisePositionMs(context)

    fun isControllerPlaying(context: Context): Boolean? {
        val state = activeChromeController(context)?.playbackState?.state ?: return null
        return state == PlaybackState.STATE_PLAYING
    }

    fun seekToMs(context: Context, positionMs: Long): Boolean {
        val controller = activeChromeController(context) ?: return false
        return runCatching {
            controller.transportControls.seekTo(positionMs.coerceAtLeast(0L))
            true
        }.getOrDefault(false)
    }

    fun play(context: Context): Boolean {
        val controller = activeChromeController(context) ?: return false
        return runCatching {
            controller.transportControls.play()
            true
        }.getOrDefault(false)
    }

    fun pause(context: Context): Boolean {
        val controller = activeChromeController(context) ?: return false
        return runCatching {
            controller.transportControls.pause()
            true
        }.getOrDefault(false)
    }

    private fun activeChromeController(context: Context): MediaController? {
        if (!canReadSessions(context)) return null
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
        val component = ComponentName(context, ChromeNotificationListenerService::class.java)
        val sessions = runCatching { manager.getActiveSessions(component) }.getOrElse { emptyList() }
        return sessions.firstOrNull { it.packageName == CHROME_PACKAGE }
    }

    private fun estimatePositionMs(state: PlaybackState?): Long? {
        state ?: return null
        val base = state.position.coerceAtLeast(0L)
        if (state.state != PlaybackState.STATE_PLAYING) return base
        val elapsed = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L)
        return (base + (elapsed * state.playbackSpeed).toLong()).coerceAtLeast(0L)
    }
}
