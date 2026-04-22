package com.example.project3

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var urlText: TextView
    private lateinit var startInput: EditText
    private lateinit var endInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var statusText: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private val chromePoller = object : Runnable {
        override fun run() {
            refreshChromeConnection()
            mainHandler.postDelayed(this, CHROME_POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlText = findViewById(R.id.urlText)
        startInput = findViewById(R.id.startInput)
        endInput = findViewById(R.id.endInput)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        statusText = findViewById(R.id.statusText)
        apiKeyInput.setText(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_API_KEY, ""))

        findViewById<Button>(R.id.downloadButton).setOnClickListener {
            startDownloadFlow()
        }
        findViewById<Button>(R.id.openAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        handleIncomingIntent(intent)
        refreshChromeConnection()
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(chromePoller)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(chromePoller)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val candidate = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        val normalized = YoutubeUrlParser.normalizeUrl(candidate)
        if (normalized != null) {
            ChromeCaptureStore.saveObservedUrl(this, normalized)
            urlText.text = normalized
        }
    }

    private fun refreshChromeConnection() {
        val observed = ChromeCaptureStore.getObservedUrl(this)
        if (observed != null) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_SELECTED_URL, observed)
                .putString(KEY_LAST_URL, observed)
                .apply()
            urlText.text = observed
            statusText.text = if (isNotificationListenerEnabled()) {
                getString(R.string.status_idle)
            } else {
                getString(R.string.status_waiting_media_listener)
            }
            return
        }
        if (!isAccessibilityEnabled()) {
            statusText.text = getString(R.string.status_waiting_accessibility)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/${ChromeAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return !enabled.isNullOrBlank() && enabled.contains(expected, ignoreCase = true)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val expected = "$packageName/${ChromeNotificationListenerService::class.java.name}"
        if (enabled.isNullOrBlank()) return false
        return enabled.split(":").any {
            it.equals(expected, ignoreCase = true) || it.startsWith("$packageName/", ignoreCase = true)
        }
    }

    private fun startDownloadFlow() {
        val sourceUrl = YoutubeUrlParser.normalizeUrl(urlText.text?.toString()) ?: run {
            updateStatus(getString(R.string.status_stage_failed, "YouTube URL is missing"))
            return
        }
        val apiKey = apiKeyInput.text?.toString().orEmpty().trim()
        if (apiKey.isBlank()) {
            updateStatus(getString(R.string.status_stage_failed, "OpenAI API key is required"))
            return
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_SELECTED_URL, sourceUrl)
            .putString(KEY_LAST_URL, sourceUrl)
            .apply()
        statusText.text = "Overlay pipeline ready. Tap Set Start / Set End in overlay."
        startOverlayMode()
    }

    private fun updateStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }

    companion object {
        private const val PREFS_NAME = "project3_main"
        private const val KEY_API_KEY = "openai_api_key"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_SELECTED_URL = "selected_url"
        private const val CHROME_POLL_INTERVAL_MS = 1500L
    }

    override fun onStart() {
        super.onStart()
        findViewById<Button>(R.id.openMediaCaptureButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.startOverlayButton).setOnClickListener {
            startOverlayMode()
        }
    }

    private fun startOverlayMode() {
        if (!isAccessibilityEnabled()) {
            statusText.text = getString(R.string.status_waiting_accessibility)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (!isNotificationListenerEnabled()) {
            statusText.text = getString(R.string.status_waiting_media_listener)
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        val selectedUrl = YoutubeUrlParser.normalizeUrl(urlText.text?.toString())
            ?: ChromeCaptureStore.getObservedUrl(this)
        val apiKey = apiKeyInput.text?.toString().orEmpty().trim()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_SELECTED_URL, selectedUrl)
            .putString(KEY_LAST_URL, selectedUrl)
            .apply()

        val overlayIntent = Intent(this, OverlayService::class.java)
        ContextCompat.startForegroundService(this, overlayIntent)
        statusText.text = getString(R.string.status_overlay_started)
    }
}
