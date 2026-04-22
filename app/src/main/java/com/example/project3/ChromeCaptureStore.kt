package com.example.project3

import android.content.Context

object ChromeCaptureStore {
    private const val PREFS = "chrome_capture_store"
    private const val KEY_URL = "last_observed_url"
    private const val KEY_TIME = "last_observed_time"

    fun saveObservedUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URL, url)
            .putLong(KEY_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getObservedUrl(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, null)
    }

    fun getObservedUrlAgeMs(context: Context): Long? {
        val ts = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_TIME, -1L)
        return if (ts <= 0L) null else (System.currentTimeMillis() - ts)
    }
}
