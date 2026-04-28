package com.example.project3

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var urlText: TextView
    private lateinit var startInput: EditText
    private lateinit var endInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var sttModeGroup: RadioGroup
    private lateinit var onDeviceProfileGroup: RadioGroup
    private lateinit var onDeviceProfileLabel: TextView
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
        sttModeGroup = findViewById(R.id.sttModeGroup)
        onDeviceProfileGroup = findViewById(R.id.onDeviceProfileGroup)
        onDeviceProfileLabel = findViewById(R.id.onDeviceProfileLabel)
        statusText = findViewById(R.id.statusText)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        apiKeyInput.setText(prefs.getString(KEY_API_KEY, ""))
        val mode = prefs.getString(KEY_STT_MODE, STT_MODE_API)
        sttModeGroup.check(if (mode == STT_MODE_ON_DEVICE) R.id.sttModeOnDevice else R.id.sttModeApi)
        val profile = prefs.getString(KEY_ON_DEVICE_PROFILE, ON_DEVICE_PROFILE_ACCURATE)
        onDeviceProfileGroup.check(
            if (profile == ON_DEVICE_PROFILE_FAST) R.id.onDeviceProfileFast else R.id.onDeviceProfileAccurate
        )
        updateOnDeviceProfileEnabled(mode == STT_MODE_ON_DEVICE)
        sttModeGroup.setOnCheckedChangeListener { _, checkedId ->
            updateOnDeviceProfileEnabled(checkedId == R.id.sttModeOnDevice)
        }

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
        val startSec = parseTimeToSeconds(startInput.text?.toString()) ?: run {
            updateStatus(getString(R.string.status_stage_failed, "Invalid start time"))
            return
        }
        val endSec = parseTimeToSeconds(endInput.text?.toString()) ?: run {
            updateStatus(getString(R.string.status_stage_failed, "Invalid end time"))
            return
        }
        if (endSec <= startSec) {
            updateStatus(getString(R.string.status_stage_failed, "End time must be greater than start time"))
            return
        }
        val mode = selectedSttMode()
        val apiKey = apiKeyInput.text?.toString().orEmpty().trim()
        if (mode == STT_MODE_API && apiKey.isBlank()) {
            updateStatus(getString(R.string.status_stage_failed, "OpenAI API key is required"))
            return
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_SELECTED_URL, sourceUrl)
            .putString(KEY_LAST_URL, sourceUrl)
            .putString(KEY_STT_MODE, mode)
            .putString(KEY_ON_DEVICE_PROFILE, selectedOnDeviceProfile())
            .apply()
        statusText.text = "Overlay pipeline starting..."
        startOverlayMode(startSec = startSec, endSec = endSec)
    }

    private fun updateStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }

    private fun parseTimeToSeconds(raw: String?): Int? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        val parts = value.split(":").map { it.trim() }
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return when (nums.size) {
            1 -> nums[0]
            2 -> nums[0] * 60 + nums[1]
            3 -> nums[0] * 3600 + nums[1] * 60 + nums[2]
            else -> null
        }
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

    private fun startOverlayMode(startSec: Int? = null, endSec: Int? = null) {
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
        val mode = selectedSttMode()
        val profile = selectedOnDeviceProfile()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_SELECTED_URL, selectedUrl)
            .putString(KEY_LAST_URL, selectedUrl)
            .putString(KEY_STT_MODE, mode)
            .putString(KEY_ON_DEVICE_PROFILE, profile)
            .apply()

        if (mode == STT_MODE_ON_DEVICE) {
            statusText.text = "Preparing on-device STT..."
            thread(name = "main-start-warmup", isDaemon = true) {
                OnDeviceWarmupCoordinator.ensureWarmupOnce(this, profile)
            }
        }

        val overlayIntent = Intent(this, OverlayService::class.java).apply {
            if (startSec != null && endSec != null) {
                putExtra(OverlayService.EXTRA_AUTO_START_SEC, startSec)
                putExtra(OverlayService.EXTRA_AUTO_END_SEC, endSec)
            }
        }
        ContextCompat.startForegroundService(this, overlayIntent)
        statusText.text = if (startSec != null && endSec != null) {
            "Overlay started. Auto STT queued: ${startSec}s-${endSec}s"
        } else {
            getString(R.string.status_overlay_started)
        }
    }

    private fun selectedSttMode(): String {
        return if (sttModeGroup.checkedRadioButtonId == R.id.sttModeOnDevice) {
            STT_MODE_ON_DEVICE
        } else {
            STT_MODE_API
        }
    }

    private fun selectedOnDeviceProfile(): String {
        return if (onDeviceProfileGroup.checkedRadioButtonId == R.id.onDeviceProfileFast) {
            ON_DEVICE_PROFILE_FAST
        } else {
            ON_DEVICE_PROFILE_ACCURATE
        }
    }

    private fun updateOnDeviceProfileEnabled(enabled: Boolean) {
        onDeviceProfileLabel.alpha = if (enabled) 1.0f else 0.45f
        for (i in 0 until onDeviceProfileGroup.childCount) {
            onDeviceProfileGroup.getChildAt(i).isEnabled = enabled
        }
    }

    companion object {
        private const val PREFS_NAME = "project3_main"
        private const val KEY_API_KEY = "openai_api_key"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_SELECTED_URL = "selected_url"
        private const val KEY_STT_MODE = "stt_mode"
        private const val KEY_ON_DEVICE_PROFILE = "on_device_profile"
        private const val STT_MODE_API = "api"
        private const val STT_MODE_ON_DEVICE = "on_device"
        private const val ON_DEVICE_PROFILE_ACCURATE = "accurate"
        private const val ON_DEVICE_PROFILE_FAST = "fast"
        private const val CHROME_POLL_INTERVAL_MS = 1500L
    }
}
