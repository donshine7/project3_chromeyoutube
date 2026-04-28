package com.example.project3

import android.content.Context

object PlaybackTarget {
    const val PREFS_NAME = "project3_main"
    const val KEY_PLAYBACK_TARGET = "playback_target"
    const val CHROME = "chrome"
    const val YOUTUBE_APP = "youtube_app"
    const val CHROME_PACKAGE = "com.android.chrome"
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"

    fun current(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return normalize(prefs.getString(KEY_PLAYBACK_TARGET, CHROME))
    }

    fun normalize(target: String?): String =
        if (target == YOUTUBE_APP) YOUTUBE_APP else CHROME

    fun mediaPackage(target: String): String =
        if (normalize(target) == YOUTUBE_APP) YOUTUBE_PACKAGE else CHROME_PACKAGE

    fun label(target: String): String =
        if (normalize(target) == YOUTUBE_APP) "YouTube app" else "Chrome YouTube"

    fun isChrome(target: String): Boolean = normalize(target) == CHROME

}
