package com.example.project3

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var urlText: TextView
    private lateinit var startInput: EditText
    private lateinit var endInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var statusText: TextView
    private lateinit var sttResultText: TextView
    private lateinit var progressBar: ProgressBar

    private val extractor by lazy { YoutubeDirectUrlExtractor(this) }
    private val segmentDownloader by lazy { AudioSegmentDownloader(this) }
    private val whisperClient by lazy { WhisperApiClient() }
    private val sentenceAssembler by lazy { SentenceAssembler() }
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
        sttResultText = findViewById(R.id.sttResultText)
        progressBar = findViewById(R.id.progressBar)
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
            urlText.text = observed
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
        val apiKey = apiKeyInput.text?.toString().orEmpty().trim()
        if (apiKey.isBlank()) {
            updateStatus(getString(R.string.status_stage_failed, "OpenAI API key is required"))
            return
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_API_KEY, apiKey).apply()

        setLoading(true)
        sttResultText.text = ""

        Thread {
            runCatching {
                updateStatus(getString(R.string.status_stage_downloading))
                val localSource = extractor.downloadToLocal(sourceUrl)

                updateStatus(getString(R.string.status_stage_cutting))
                val segmentFile = segmentDownloader.cutSegmentFromLocal(localSource.path, startSec, endSec)

                updateStatus(getString(R.string.status_stage_stt_uploading))
                val transcript = whisperClient.transcribeVerboseEnglish(apiKey, segmentFile)
                val shifted = offsetTranscript(transcript, startSec)

                updateStatus(getString(R.string.status_stage_stt_processing))
                val sentences = sentenceAssembler.build(shifted, startSec.toDouble(), endSec.toDouble())
                runOnUiThread { sttResultText.text = renderSentenceResult(sentences) }

                updateStatus(getString(R.string.status_stage_stt_done))
            }.onFailure { error ->
                updateStatus(getString(R.string.status_stage_failed, error.message ?: "unknown error"))
            }
            runOnUiThread { setLoading(false) }
        }.start()
    }

    private fun offsetTranscript(result: WhisperVerboseResult, offsetSec: Int): WhisperVerboseResult {
        val d = offsetSec.toDouble()
        return result.copy(
            segments = result.segments?.map { seg ->
                seg.copy(
                    start = seg.start + d,
                    end = seg.end + d,
                    words = seg.words?.map { w -> w.copy(start = w.start + d, end = w.end + d) }
                )
            },
            words = result.words?.map { w -> w.copy(start = w.start + d, end = w.end + d) }
        )
    }

    private fun renderSentenceResult(sentences: List<SentenceTimestamp>): String {
        if (sentences.isEmpty()) return getString(R.string.stt_empty)
        return buildString {
            sentences.forEach { s ->
                append("[")
                append(formatSec(s.startSec))
                append(" - ")
                append(formatSec(s.endSec))
                append("] ")
                append(s.text)
                append('\n')
            }
        }.trim()
    }

    private fun setLoading(loading: Boolean) {
        runOnUiThread {
            progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            findViewById<Button>(R.id.downloadButton).isEnabled = !loading
        }
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

    private fun formatSec(sec: Double): String {
        val ms = (sec * 1000.0).toLong().coerceAtLeast(0L)
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1_000
        val t = (ms % 1_000) / 100
        return String.format(Locale.US, "%02d:%02d:%02d.%d", h, m, s, t)
    }

    companion object {
        private const val PREFS_NAME = "project3_main"
        private const val KEY_API_KEY = "openai_api_key"
        private const val CHROME_POLL_INTERVAL_MS = 1500L
    }
}
