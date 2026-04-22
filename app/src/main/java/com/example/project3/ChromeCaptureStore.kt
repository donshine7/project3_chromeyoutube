package com.example.project3

import android.content.Context

object ChromeCaptureStore {
    private const val PREFS = "chrome_capture_store"
    private const val KEY_URL = "last_observed_url"
    private const val KEY_TIME = "last_observed_time"
    private const val KEY_POSITION_SEC = "playback_position_sec"
    private const val KEY_SOURCE = "playback_source"
    private const val KEY_POSITION_UPDATED_AT = "playback_position_updated_at"
    private const val KEY_VIDEO_POS_PREFIX = "video_pos_"

    fun saveObservedUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previousVideoId = YoutubeUrlParser.extractVideoId(prefs.getString(KEY_URL, null))
        val nextVideoId = YoutubeUrlParser.extractVideoId(url)
        val editor = prefs.edit()
            .putString(KEY_URL, url)
            .putLong(KEY_TIME, System.currentTimeMillis())
        if (!previousVideoId.isNullOrBlank() && !nextVideoId.isNullOrBlank() && previousVideoId != nextVideoId) {
            editor.putInt(KEY_POSITION_SEC, 0)
                .putInt(KEY_SOURCE, SOURCE_UNKNOWN)
                .putLong(KEY_POSITION_UPDATED_AT, 0L)
            prefs.all.keys.filter { it.startsWith(KEY_VIDEO_POS_PREFIX) }.forEach(editor::remove)
        }
        editor.apply()
    }

    fun getObservedUrl(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, null)
    }

    fun getObservedUrlAgeMs(context: Context): Long? {
        val ts = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_TIME, -1L)
        return if (ts <= 0L) null else (System.currentTimeMillis() - ts)
    }

    fun getObservedVideoId(context: Context): String? = getObservedUrl(context)?.let(YoutubeUrlParser::extractVideoId)

    fun savePlaybackSnapshot(context: Context, url: String, positionSec: Int, source: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val safe = positionSec.coerceAtLeast(0)
        val editor = prefs.edit()
            .putString(KEY_URL, url)
            .putLong(KEY_TIME, now)
            .putInt(KEY_POSITION_SEC, safe)
            .putInt(KEY_SOURCE, source)
            .putLong(KEY_POSITION_UPDATED_AT, now)
        YoutubeUrlParser.extractVideoId(url)?.let { editor.putInt("$KEY_VIDEO_POS_PREFIX$it", safe) }
        editor.apply()
    }

    fun getPlaybackPositionSec(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_POSITION_SEC, 0).coerceAtLeast(0)

    fun getPlaybackSource(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SOURCE, SOURCE_UNKNOWN)

    fun getCachedPositionSec(context: Context, videoId: String): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("$KEY_VIDEO_POS_PREFIX$videoId", 0).coerceAtLeast(0)

    fun clearTransientPlaybackSample(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_POSITION_SEC, 0)
            .putInt(KEY_SOURCE, SOURCE_UNKNOWN)
            .putLong(KEY_POSITION_UPDATED_AT, 0L)
            .apply()
    }

    const val SOURCE_UNKNOWN = 0
    const val SOURCE_ACCESSIBILITY = 1
    const val SOURCE_MEDIA_SESSION = 2
}
