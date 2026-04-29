package com.example.project3

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Window
import android.widget.TextView
import androidx.core.content.ContextCompat

class ClipboardCaptureActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var captured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.setDimAmount(0f)
        window.setGravity(Gravity.BOTTOM or Gravity.START)
        window.attributes = window.attributes.apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(12)
            y = dp(68)
        }
        setContentView(
            TextView(this).apply {
                text = "Reading link..."
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 11f
                background = GradientDrawable().apply {
                    setColor(0xE6222222.toInt())
                    cornerRadius = dp(8).toFloat()
                }
            }
        )
        window.setLayout(dp(156), dp(44))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.setLayout(dp(156), dp(44))
        }
    }

    override fun onResume() {
        super.onResume()
        if (captured) return
        captured = true
        handler.postDelayed({ captureAndReturn() }, 160L)
    }

    private fun captureAndReturn() {
        val after = intent.getStringExtra(OverlayService.EXTRA_CLIPBOARD_CAPTURE_AFTER).orEmpty()
        val startSec = intent.getIntExtra(OverlayService.EXTRA_CLIPBOARD_CAPTURE_START_SEC, -1)
        val endSec = intent.getIntExtra(OverlayService.EXTRA_CLIPBOARD_CAPTURE_END_SEC, -1)
        val normalized = YoutubeClipboardReader.readYoutubeUrl(this)

        if (!normalized.isNullOrBlank()) {
            val now = System.currentTimeMillis()
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_SELECTED_URL, normalized)
                .putString(KEY_LAST_URL, normalized)
                .putString(KEY_CLIPBOARD_CAPTURED_URL, normalized)
                .putLong(KEY_CLIPBOARD_CAPTURED_AT_MS, now)
                .apply()
            ChromeCaptureStore.saveObservedUrl(this, normalized)
        }

        val result = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_CLIPBOARD_CAPTURE_RESULT
            putExtra(OverlayService.EXTRA_CLIPBOARD_CAPTURE_AFTER, after)
            putExtra(OverlayService.EXTRA_CLIPBOARD_CAPTURE_URL, normalized)
            putExtra(OverlayService.EXTRA_CLIPBOARD_CAPTURE_START_SEC, startSec)
            putExtra(OverlayService.EXTRA_CLIPBOARD_CAPTURE_END_SEC, endSec)
        }
        ContextCompat.startForegroundService(this, result)
        bringYoutubeToFront()
        finish()
        overridePendingTransition(0, 0)
    }

    private fun bringYoutubeToFront() {
        val launchIntent = packageManager.getLaunchIntentForPackage(PlaybackTarget.YOUTUBE_PACKAGE) ?: return
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        runCatching {
            startActivity(launchIntent)
            overridePendingTransition(0, 0)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS_NAME = "project3_main"
        private const val KEY_SELECTED_URL = "selected_url"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_CLIPBOARD_CAPTURED_URL = "clipboard_captured_url"
        private const val KEY_CLIPBOARD_CAPTURED_AT_MS = "clipboard_captured_at_ms"
    }
}
