package com.example.project3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.GradientDrawable
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Browser
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class OverlayService : Service() {
    private val tag = "OverlayService"
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var statusTextView: TextView? = null
    private var headerTitleTextView: TextView? = null
    private var perfTextView: TextView? = null
    private var outputTextView: TextView? = null
    private var outputScrollView: ScrollView? = null
    private var toggleButton: Button? = null
    private var folderButtonsContainer: LinearLayout? = null
    private var folderScrollView: ScrollView? = null
    private var btnFolderUp: Button? = null
    private var btnFolderFold: Button? = null

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val extractor by lazy { YoutubeDirectUrlExtractor(this) }
    private val segmentDownloader by lazy { AudioSegmentDownloader(this) }
    private val sentenceAssembler by lazy { SentenceAssembler() }
    private val sentenceStore by lazy { SentenceTimestampStore(this) }
    private val sentenceTranslationClient by lazy { SentenceTranslationClient() }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val repeatHandler by lazy { Handler(Looper.getMainLooper()) }

    private var browserFolded = false
    private var selectedDate: String? = null
    private var selectedContent: SentenceTimestampStore.FolderEntry? = null

    private var captureVideoId: String? = null
    private var capturedStartSec: Int? = null
    private var capturedEndSec: Int? = null
    private var waitingEndCapture = false
    private var pipelineRunning = false

    private var repeatSession: RepeatSession? = null
    private var repeatCancelled = false
    private var activeSentenceKey: String? = null
    private var expandedSentenceKey: String? = null
    private var lastPerfSummary: String? = null
    private var currentVideoTitle: String? = null
    private val showKoreanKeys = mutableSetOf<String>()
    private val translationLoadingKeys = mutableSetOf<String>()
    private val translatedTextByKey = mutableMapOf<String, String>()
    private var pendingAutoRange: Pair<Int, Int>? = null

    private data class SttChunk(
        val validStartSec: Int,
        val validEndSec: Int,
        val transcribeStartSec: Int,
        val transcribeEndSec: Int
    )

    private data class MergeSummary(
        val result: WhisperVerboseResult,
        val sourceSegmentCount: Int,
        val dedupedSegmentCount: Int,
        val sourceWordCount: Int,
        val dedupedWordCount: Int
    )

    private enum class RepeatState { SEEKING, WAITING_FOR_PLAY, PLAYING, RESTARTING, FAILED }

    private data class RepeatSession(
        val sentence: SentenceTimestamp,
        val expectedVideoId: String?,
        val repeatCount: Int,
        val startMs: Long,
        val endMs: Long,
        var currentLoop: Int = 1,
        var state: RepeatState = RepeatState.SEEKING,
        var startedAtMs: Long = System.currentTimeMillis(),
        var stateEnteredAtMs: Long = System.currentTimeMillis(),
        var actionAtMs: Long = 0L
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        runCatching { showOverlay() }.onFailure {
            stopSelf()
        }
    }

    override fun onDestroy() {
        repeatHandler.removeCallbacksAndMessages(null)
        overlayView?.let { v -> runCatching { windowManager?.removeView(v) } }
        overlayView = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLIPBOARD_CAPTURE_RESULT) {
            val after = intent.getStringExtra(EXTRA_CLIPBOARD_CAPTURE_AFTER).orEmpty()
            val url = intent.getStringExtra(EXTRA_CLIPBOARD_CAPTURE_URL)
            val startSec = intent.getIntExtra(EXTRA_CLIPBOARD_CAPTURE_START_SEC, -1)
            val endSec = intent.getIntExtra(EXTRA_CLIPBOARD_CAPTURE_END_SEC, -1)
            mainHandler.post { handleClipboardCaptureResult(after, url, startSec, endSec) }
            return START_STICKY
        }
        val startSec = intent?.getIntExtra(EXTRA_AUTO_START_SEC, -1) ?: -1
        val endSec = intent?.getIntExtra(EXTRA_AUTO_END_SEC, -1) ?: -1
        if (startSec >= 0 && endSec > startSec) {
            pendingAutoRange = startSec to endSec
            mainHandler.post { maybeRunPendingAutoRange() }
        }
        return START_STICKY
    }

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC111111.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        headerTitleTextView = TextView(this).apply {
            text = "Project3 Overlay\n-"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * FONT_SCALE)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        statusTextView = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * FONT_SCALE)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            text = buildCaptureStatus()
        }
        perfTextView = TextView(this).apply {
            setTextColor(0xFF80CBC4.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * FONT_SCALE)
            text = "Perf: -"
        }
        toggleButton = Button(this).apply {
            text = "Set Start"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SP)
            setOnClickListener { onCaptureToggle() }
        }
        val resetButton = Button(this).apply {
            text = "Reset"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SP)
            setOnClickListener { resetAllState() }
        }
        val closeButton = Button(this).apply {
            text = "Close"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SP)
            setOnClickListener { stopSelf() }
        }
        val resizeHandle = TextView(this).apply {
            text = "↕ Resize"
            gravity = Gravity.CENTER
            setTextColor(0xFFCFD8DC.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * FONT_SCALE)
            setPadding(0, dp(4), 0, dp(4))
        }
        val controlsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controlsRow.addView(toggleButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controlsRow.addView(resetButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        outputTextView = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * FONT_SCALE)
        }
        outputScrollView = ScrollView(this).apply {
            visibility = View.GONE
            addView(outputTextView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val folderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnFolderUp = Button(this).apply {
            text = "Up"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SP)
            setOnClickListener { navigateUp() }
        }
        btnFolderFold = Button(this).apply {
            text = "Fold"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SP)
            setOnClickListener {
                browserFolded = !browserFolded
                updateFolderFoldUi()
            }
        }
        folderRow.addView(btnFolderUp, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        folderRow.addView(btnFolderFold, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        folderButtonsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }
        folderScrollView = ScrollView(this).apply {
            addView(folderButtonsContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        root.addView(headerTitleTextView)
        root.addView(statusTextView)
        root.addView(perfTextView)
        root.addView(controlsRow)
        root.addView(outputScrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        root.addView(folderRow)
        root.addView(folderScrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(resizeHandle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(closeButton)

        val display = resources.displayMetrics
        val overlayWidth = (display.widthPixels * (2f / 3f)).toInt()
        val overlayHeight = (display.heightPixels * 0.5f).toInt()
        val edgeMargin = dp(12)
        val bottomSafeOffset = dp(56)
        val params = WindowManager.LayoutParams(
            overlayWidth,
            overlayHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Initial placement: bottom-right corner with a small margin.
            x = (display.widthPixels - overlayWidth - edgeMargin).coerceAtLeast(0)
            y = (display.heightPixels - overlayHeight - edgeMargin - bottomSafeOffset).coerceAtLeast(0)
        }
        val dragTouchListener = createDragTouchListener(root)
        val resizeTouchListener = createResizeTouchListener(root, display.heightPixels)
        root.setOnTouchListener(dragTouchListener)
        headerTitleTextView?.setOnTouchListener(dragTouchListener)
        statusTextView?.setOnTouchListener(dragTouchListener)
        resizeHandle.setOnTouchListener(resizeTouchListener)
        wm.addView(root, params)
        windowManager = wm
        overlayView = root
        overlayParams = params
        refreshOverlayVideoHeaderTitle()
        refreshSentenceButtons()
        updateFolderFoldUi()
        maybeRunPendingAutoRange()
    }

    private fun maybeRunPendingAutoRange() {
        val range = pendingAutoRange ?: return
        if (pipelineRunning) return
        pendingAutoRange = null
        val (startSec, endSec) = range
        waitingEndCapture = false
        capturedStartSec = startSec
        capturedEndSec = endSec
        toggleButton?.text = "Set Start"
        statusTextView?.text = "Auto STT: ${startSec}s-${endSec}s"
        runStt(startSec, endSec)
    }

    private fun requestFocusedClipboardCapture(after: String, startSec: Int = -1, endSec: Int = -1) {
        val intent = Intent(this, ClipboardCaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra(EXTRA_CLIPBOARD_CAPTURE_AFTER, after)
            putExtra(EXTRA_CLIPBOARD_CAPTURE_START_SEC, startSec)
            putExtra(EXTRA_CLIPBOARD_CAPTURE_END_SEC, endSec)
        }
        runCatching { startActivity(intent) }.onFailure { err ->
            statusTextView?.text = "Clipboard capture failed: ${summarizeError(err)}"
            Log.w(tag, "clipboardCaptureActivityLaunchFailed reason=${summarizeError(err)}")
        }
    }

    private fun handleClipboardCaptureResult(after: String, url: String?, startSec: Int, endSec: Int) {
        if (url.isNullOrBlank()) {
            statusTextView?.text = "No YouTube URL found in clipboard. Copy link first."
            Log.w(tag, "focusedClipboardCapture failed after=$after reason=no_youtube_url")
            return
        }
        Log.i(tag, "focusedClipboardCapture success after=$after videoId=${YoutubeUrlParser.extractVideoId(url)}")
        statusTextView?.text = "Clipboard URL captured."
        when (after) {
            AFTER_CAPTURE_TOGGLE -> continueCaptureToggleAfterClipboard(url)
            AFTER_CAPTURE_RUN_STT -> {
                if (startSec >= 0 && endSec > startSec) {
                    runStt(startSec, endSec)
                } else {
                    statusTextView?.text = "Invalid captured range."
                }
            }
            else -> fetchAndShowClipboardTitle(url)
        }
    }

    private fun onCaptureToggle() {
        if (pipelineRunning) {
            Toast.makeText(this, "STT in progress.", Toast.LENGTH_SHORT).show()
            return
        }
        val playbackTarget = currentPlaybackTarget()
        if (!PlaybackTarget.isChrome(playbackTarget) && !waitingEndCapture) {
            statusTextView?.text = "Reading clipboard..."
            requestFocusedClipboardCapture(AFTER_CAPTURE_TOGGLE)
            return
        }
        continueCaptureToggleAfterClipboard(null)
    }

    private fun continueCaptureToggleAfterClipboard(clipboardUrl: String?) {
        clipboardUrl?.let { fetchAndShowClipboardTitle(it) }
        val playbackTarget = currentPlaybackTarget()
        val snapshot = ChromePlaybackReader.readSnapshot(this, playbackTarget)
        if (snapshot == null || snapshot.positionMs < 0L) {
            statusTextView?.text = "Playback not detected. Open YouTube in ${PlaybackTarget.label(playbackTarget)}."
            return
        }
        val sec = ((snapshot.positionMs + 500L) / 1000L).toInt().coerceAtLeast(0)
        if (!waitingEndCapture) {
            captureVideoId = snapshot.videoId
            capturedStartSec = sec
            capturedEndSec = null
            waitingEndCapture = true
            toggleButton?.text = "Set End"
            statusTextView?.text = buildCaptureStatus()
            Toast.makeText(this, "Start: ${capturedStartSec}s", Toast.LENGTH_SHORT).show()
            return
        }

        if (!captureVideoId.isNullOrBlank() && captureVideoId != snapshot.videoId) {
            statusTextView?.text = "Video changed. Capture start again."
            resetCaptureState()
            return
        }
        val start = capturedStartSec ?: run {
            resetCaptureState()
            return
        }
        if (sec <= start) {
            statusTextView?.text = "End must be after start."
            return
        }
        capturedEndSec = sec
        waitingEndCapture = false
        toggleButton?.text = "Set Start"
        statusTextView?.text = buildCaptureStatus()
        if (PlaybackTarget.isChrome(playbackTarget)) {
            runStt(start, sec)
        } else {
            runStt(start, sec)
        }
    }

    private fun runStt(startSec: Int, endSec: Int) {
        if (pipelineRunning) return
        pipelineRunning = true
        val sttMode = prefs.getString(KEY_STT_MODE, SttEngineFactory.MODE_API) ?: SttEngineFactory.MODE_API
        val onDeviceProfile = prefs.getString(KEY_ON_DEVICE_PROFILE, SttEngineFactory.ON_DEVICE_PROFILE_ACCURATE)
            ?: SttEngineFactory.ON_DEVICE_PROFILE_ACCURATE
        val padBeforeSec = when {
            sttMode == SttEngineFactory.MODE_ON_DEVICE && onDeviceProfile == SttEngineFactory.ON_DEVICE_PROFILE_FAST ->
                STT_PAD_BEFORE_SEC_FAST
            else -> STT_PAD_BEFORE_SEC_ACCURATE
        }
        val padAfterSec = when {
            sttMode == SttEngineFactory.MODE_ON_DEVICE && onDeviceProfile == SttEngineFactory.ON_DEVICE_PROFILE_FAST ->
                STT_PAD_AFTER_SEC_FAST
            else -> STT_PAD_AFTER_SEC_ACCURATE
        }
        val transcribeStartSec = (startSec - padBeforeSec).coerceAtLeast(0)
        val transcribeEndSec = (endSec + padAfterSec).coerceAtLeast(transcribeStartSec + 1)
        val isFastOnDevice = sttMode == SttEngineFactory.MODE_ON_DEVICE &&
            onDeviceProfile == SttEngineFactory.ON_DEVICE_PROFILE_FAST
        val requestedDurationSec = (endSec - startSec).coerceAtLeast(1)
        val useChunking = isFastOnDevice && requestedDurationSec > FAST_CHUNK_MIN_REQUEST_SEC
        val chunks = if (useChunking) {
            buildFastChunks(startSec, endSec)
        } else {
            listOf(SttChunk(startSec, endSec, transcribeStartSec, transcribeEndSec))
        }
        val chunkBoundaries = chunks.dropLast(1).map { it.validEndSec.toDouble() }
        val sttEngine = SttEngineFactory.create(this, sttMode, onDeviceProfile)
        val apiKey = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
        val sourceUrl = resolveSourceUrlForDownload()
        val sourceVideoId = YoutubeUrlParser.extractVideoId(sourceUrl)
        if (!PlaybackTarget.isChrome(currentPlaybackTarget()) &&
            !captureVideoId.isNullOrBlank() &&
            !sourceVideoId.isNullOrBlank() &&
            captureVideoId != sourceVideoId
        ) {
            pipelineRunning = false
            updatePerfSummary(null)
            statusTextView?.text = "Clipboard URL does not match captured video. Copy the current video link again."
            Log.w(tag, "downloadBlocked clipboardVideoId=$sourceVideoId capturedVideoId=$captureVideoId")
            return
        }
        if ((sttMode == SttEngineFactory.MODE_API && apiKey.isNullOrBlank()) || sourceUrl.isNullOrBlank()) {
            pipelineRunning = false
            updatePerfSummary(null)
            statusTextView?.text = if (sourceUrl.isNullOrBlank()) {
                if (PlaybackTarget.isChrome(currentPlaybackTarget())) {
                    "Missing URL."
                } else {
                    "No YouTube URL found in clipboard. Copy link first."
                }
            } else {
                "Missing API key."
            }
            return
        }
        updatePerfSummary(null)
        setOutputText("")
        statusTextView?.text = "Downloading with yt-dlp..."
        thread(name = "overlay-stt") {
            runCatching {
                val t0 = System.currentTimeMillis()
                var localSource = extractor.downloadToLocal(sourceUrl)
                val tDownload = System.currentTimeMillis()
                Log.i(
                    tag,
                    "finalSource fromCache=${if (localSource.fromCache) 1 else 0} " +
                        "forceDownload=0 path=${localSource.path}"
                )
                mainHandler.post {
                    statusTextView?.text =
                        "Preparing ${chunks.size} chunk(s)..."
                }
                val outputProfile = if (sttMode == SttEngineFactory.MODE_ON_DEVICE) {
                    AudioSegmentDownloader.OutputProfile.WAV_16K_MONO
                } else {
                    AudioSegmentDownloader.OutputProfile.COMPRESSED_WEBM
                }
                Log.i(
                    tag,
                    "runSttPlan mode=$sttMode profile=$onDeviceProfile requestedSec=$requestedDurationSec useChunking=$useChunking " +
                        "singlePad=${padBeforeSec}/${padAfterSec} chunks=${chunks.size}"
                )
                Log.i(
                    tag,
                    "chunkPlan count=${chunks.size} ranges=${chunks.joinToString(";") { "${it.transcribeStartSec}-${it.transcribeEndSec}" }}"
                )
                val chunkTranscripts = mutableListOf<WhisperVerboseResult>()
                var cutElapsedMs = 0L
                var sttElapsedMs = 0L
                val chunkCutMs = mutableListOf<Long>()
                val chunkSttMs = mutableListOf<Long>()
                for ((index, chunk) in chunks.withIndex()) {
                    mainHandler.post {
                        statusTextView?.text = "Chunk ${index + 1}/${chunks.size}: cutting ${chunk.transcribeStartSec}s-${chunk.transcribeEndSec}s"
                    }
                    val cutStart = System.currentTimeMillis()
                    val segmentFile = runCatching {
                        segmentDownloader.cutSegmentFromLocal(
                            localSource.path,
                            chunk.transcribeStartSec,
                            chunk.transcribeEndSec,
                            outputProfile
                        )
                    }.recoverCatching { firstErr ->
                        if (!shouldFallbackRedownload(localSource, firstErr)) throw firstErr
                        Log.w(
                            tag,
                            "fallbackRedownloadTriggered reason=${summarizeError(firstErr)} " +
                                "path=${localSource.path}"
                        )
                        mainHandler.post { statusTextView?.text = "Retrying download without cache..." }
                        localSource = extractor.downloadToLocal(sourceUrl, forceDownload = true)
                        Log.i(
                            tag,
                            "finalSource fromCache=${if (localSource.fromCache) 1 else 0} " +
                                "forceDownload=1 path=${localSource.path}"
                        )
                        segmentDownloader.cutSegmentFromLocal(
                            localSource.path,
                            chunk.transcribeStartSec,
                            chunk.transcribeEndSec,
                            outputProfile
                        )
                    }.getOrThrow()
                    val cutMs = System.currentTimeMillis() - cutStart
                    cutElapsedMs += cutMs
                    chunkCutMs += cutMs
                    mainHandler.post {
                        statusTextView?.text =
                            if (sttMode == SttEngineFactory.MODE_ON_DEVICE) {
                                "Chunk ${index + 1}/${chunks.size}: Running on-device Whisper..."
                            } else {
                                "Uploading to Whisper..."
                            }
                    }
                    val sttStart = System.currentTimeMillis()
                    val transcript = sttEngine.transcribeVerboseEnglish(apiKey, segmentFile)
                    val sttMs = System.currentTimeMillis() - sttStart
                    sttElapsedMs += sttMs
                    chunkSttMs += sttMs
                    val partialPerf = buildPerfSummary(sttElapsedMs, requestedDurationSec)
                    Log.i(
                        tag,
                        "chunkTiming idx=${index + 1}/${chunks.size} range=${chunk.transcribeStartSec}-${chunk.transcribeEndSec} " +
                            "cut=${cutMs}ms stt=${sttMs}ms segs=${transcript.segments?.size ?: 0} words=${transcript.words?.size ?: 0}"
                    )
                    chunkTranscripts += offsetTranscript(transcript, chunk.transcribeStartSec)
                    val partialMerge = mergeWhisperResults(chunkTranscripts)
                    val partialSentences = sentenceAssembler.build(
                        partialMerge.result,
                        startSec.toDouble(),
                        endSec.toDouble(),
                        chunkBoundaries
                    )
                    mainHandler.post {
                        setOutputText(renderSentenceResult(partialSentences))
                        updatePerfSummary("$partialPerf (partial ${index + 1}/${chunks.size})")
                        if (index + 1 < chunks.size) {
                            statusTextView?.text = "Chunk ${index + 1}/${chunks.size} done. Processing next..."
                        }
                    }
                }
                val assembleStart = System.currentTimeMillis()
                val merge = mergeWhisperResults(chunkTranscripts)
                val merged = merge.result
                val sentencesRaw = sentenceAssembler.build(
                    merged,
                    startSec.toDouble(),
                    endSec.toDouble(),
                    chunkBoundaries
                )
                val sentences = finalCleanupSentences(sentencesRaw)
                val tAssemble = System.currentTimeMillis()
                val canonical = YoutubeUrlParser.canonicalWatchUrlFromAny(sourceUrl) ?: sourceUrl
                val saveFile = sentenceStore.save(
                    youtubeUrl = canonical,
                    videoTitle = localSource.title,
                    sentences = sentences,
                    replaceStartSec = startSec.toDouble(),
                    replaceEndSec = endSec.toDouble(),
                    debugTranscript = merged,
                    debugRawSentences = sentencesRaw
                )
                val tSave = System.currentTimeMillis()
                val perfSummary = buildPerfSummary(sttElapsedMs, requestedDurationSec)
                Log.i(
                    tag,
                    "timing mode=$sttMode profile=$onDeviceProfile pad=${padBeforeSec}/${padAfterSec} " +
                        "chunks=${chunks.size} " +
                        "download=${tDownload - t0}ms cut=${cutElapsedMs}ms " +
                        "stt=${sttElapsedMs}ms assemble=${tAssemble - assembleStart}ms save=${tSave - tAssemble}ms " +
                        "wordTs=1 wordsRaw=${merge.sourceWordCount} wordsDeduped=${merge.dedupedWordCount} " +
                        "sentencesRaw=${sentencesRaw.size} sentencesFinal=${sentences.size} " +
                        "chunkCutMs=[${chunkCutMs.joinToString(",")}] chunkSttMs=[${chunkSttMs.joinToString(",")}] " +
                        "mergeSegments=${merge.dedupedSegmentCount}/${merge.sourceSegmentCount} " +
                        "mergeWords=${merge.dedupedWordCount}/${merge.sourceWordCount} " +
                        "total=${tSave - t0}ms"
                )
                mainHandler.post {
                    currentVideoTitle = localSource.title?.takeIf { it.isNotBlank() } ?: currentVideoTitle
                    setOutputText(renderSentenceResult(sentences))
                    statusTextView?.text = "STT done: ${saveFile.name} | $perfSummary"
                    updatePerfSummary(perfSummary)
                    selectSavedContent(saveFile)
                    refreshOverlayVideoHeaderTitle()
                    refreshSentenceButtons()
                }
            }.onFailure { err ->
                mainHandler.post {
                    val detail = summarizeError(err)
                    statusTextView?.text = "STT failed: $detail"
                    updatePerfSummary("failed")
                }
                Log.e(tag, "runStt failed", err)
            }
            pipelineRunning = false
        }
    }

    private fun buildFastChunks(
        requestStartSec: Int,
        requestEndSec: Int
    ): List<SttChunk> {
        val result = mutableListOf<SttChunk>()
        val maxTargetLen = FAST_CHUNK_SEC
        var currentStart = requestStartSec
        while (currentStart < requestEndSec) {
            val currentEnd = (currentStart + maxTargetLen).coerceAtMost(requestEndSec)
            val transcribeStart = if (currentStart == requestStartSec) {
                (currentStart - FAST_CHUNK_EDGE_PAD_SEC).coerceAtLeast(0)
            } else {
                (currentStart - FAST_CHUNK_INTERNAL_PAD_SEC).coerceAtLeast(0)
            }
            val transcribeEnd = if (currentEnd >= requestEndSec) {
                (currentEnd + FAST_CHUNK_EDGE_PAD_SEC).coerceAtLeast(transcribeStart + 1)
            } else {
                (currentEnd + FAST_CHUNK_INTERNAL_PAD_SEC).coerceAtLeast(transcribeStart + 1)
            }
            result += SttChunk(
                validStartSec = currentStart,
                validEndSec = currentEnd,
                transcribeStartSec = transcribeStart,
                transcribeEndSec = transcribeEnd
            )
            if (currentEnd >= requestEndSec) break
            currentStart = (currentEnd - FAST_CHUNK_OVERLAP_SEC).coerceAtLeast(currentStart + 1)
        }
        return result
    }

    private fun mergeWhisperResults(parts: List<WhisperVerboseResult>): MergeSummary {
        if (parts.isEmpty()) {
            return MergeSummary(
                WhisperVerboseResult("", null, null, null, null),
                sourceSegmentCount = 0,
                dedupedSegmentCount = 0,
                sourceWordCount = 0,
                dedupedWordCount = 0
            )
        }
        val sourceSegments = parts.sumOf { it.segments?.size ?: 0 }
        val sourceWords = parts.sumOf { it.words?.size ?: 0 }
        val mergedSegments = parts
            .flatMap { it.segments.orEmpty() }
            .sortedBy { it.start }
            .fold(mutableListOf<WhisperSegment>()) { acc, seg ->
                val duplicateIdx = acc.indexOfLast { existing ->
                    val nearSameTime = kotlin.math.abs(existing.start - seg.start) <= MERGE_SEGMENT_TIME_EPS &&
                        kotlin.math.abs(existing.end - seg.end) <= MERGE_SEGMENT_TIME_EPS
                    val overlapRatio = overlapRatio(existing.start, existing.end, seg.start, seg.end)
                    val textA = normalizeMergeText(existing.text)
                    val textB = normalizeMergeText(seg.text)
                    val containsDuplicate = overlapRatio >= MERGE_SEGMENT_OVERLAP_RATIO &&
                        (textA.contains(textB) || textB.contains(textA))
                    nearSameTime && textA == textB || containsDuplicate
                }
                if (duplicateIdx >= 0) {
                    val existing = acc[duplicateIdx]
                    val existingDur = existing.end - existing.start
                    val newDur = seg.end - seg.start
                    val keepNew = newDur > existingDur || seg.text.length > existing.text.length
                    if (keepNew) acc[duplicateIdx] = seg
                } else {
                    acc += seg
                }
                acc
            }
        val mergedWords = parts
            .flatMap { it.words.orEmpty() }
            .sortedBy { it.start }
            .fold(mutableListOf<WhisperWord>()) { acc, word ->
                val prev = acc.lastOrNull()
                val nearDuplicate = prev != null &&
                    kotlin.math.abs(prev.start - word.start) <= MERGE_WORD_TIME_EPS &&
                    kotlin.math.abs(prev.end - word.end) <= MERGE_WORD_TIME_EPS &&
                    normalizeMergeText(prev.word) == normalizeMergeText(word.word)
                val looseDuplicate = prev != null &&
                    normalizeMergeText(prev.word) == normalizeMergeText(word.word) &&
                    overlapRatio(prev.start, prev.end, word.start, word.end) >= MERGE_WORD_OVERLAP_RATIO_LOOSE
                if (!nearDuplicate && !looseDuplicate) acc += word
                acc
            }
        val mergedText = if (mergedSegments.isNotEmpty()) {
            mergedSegments.joinToString(separator = " ") { it.text.trim() }.replace(Regex("\\s+"), " ").trim()
        } else {
            parts.joinToString(separator = " ") { it.text.trim() }.replace(Regex("\\s+"), " ").trim()
        }
        return MergeSummary(
            result = WhisperVerboseResult(
                text = mergedText,
                language = parts.firstNotNullOfOrNull { it.language },
                duration = null,
                segments = mergedSegments.ifEmpty { null },
                words = mergedWords.ifEmpty { null }
            ),
            sourceSegmentCount = sourceSegments,
            dedupedSegmentCount = mergedSegments.size,
            sourceWordCount = sourceWords,
            dedupedWordCount = mergedWords.size
        )
    }

    private fun normalizeMergeText(raw: String): String =
        raw.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun finalCleanupSentences(raw: List<SentenceTimestamp>): List<SentenceTimestamp> {
        if (raw.isEmpty()) return raw
        val sorted = raw.sortedBy { it.startSec }
        val merged = mutableListOf<SentenceTimestamp>()
        var i = 0
        while (i < sorted.size) {
            val cur = sorted[i]
            if (i + 1 < sorted.size) {
                val next = sorted[i + 1]
                val gap = (next.startSec - cur.endSec).coerceAtLeast(0.0)
                val continuationJoin = !endsSentenceText(cur.text) &&
                    startsContinuationText(next.text) &&
                    gap <= 0.45 &&
                    (next.endSec - cur.startSec) <= 10.0
                val incompleteTailJoin = !endsSentenceText(cur.text) &&
                    gap <= 0.20 &&
                    (next.endSec - cur.startSec) <= 18.0 &&
                    (endsWithIncompleteTailText(cur.text) || protectsTextBoundary(cur.text, next.text))
                val hardCutRepairJoin = !endsSentenceText(cur.text) &&
                    gap <= FINAL_HARD_CUT_REPAIR_GAP_SEC &&
                    (next.endSec - cur.startSec) <= FINAL_HARD_CUT_REPAIR_MAX_SPAN_SEC &&
                    cur.text.trim().split(Regex("\\s+")).count { it.isNotBlank() } >= 4 &&
                    next.text.trim().split(Regex("\\s+")).count { it.isNotBlank() } >= 2 &&
                    !startsClearSentenceStarterText(next.text)
                if (continuationJoin || incompleteTailJoin || hardCutRepairJoin) {
                    merged += cur.copy(
                        endSec = next.endSec,
                        text = "${cur.text} ${next.text}".replace(Regex("\\s+"), " ").trim()
                    )
                    Log.d(
                        tag,
                        "finalCleanup join_${when {
                            incompleteTailJoin -> "incomplete"
                            hardCutRepairJoin -> "hard_cut_repair"
                            else -> "continuation"
                        }} gap=${"%.2f".format(gap)}"
                    )
                    i += 2
                    continue
                }
            }
            merged += cur
            i += 1
        }

        val filtered = mutableListOf<SentenceTimestamp>()
        for (idx in merged.indices) {
            val cur = merged[idx]
            val words = cur.text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val dur = (cur.endSec - cur.startSec).coerceAtLeast(0.0)
            val prev = filtered.lastOrNull()
            val next = merged.getOrNull(idx + 1)
            val prevGap = prev?.let { (cur.startSec - it.endSec).coerceAtLeast(0.0) } ?: 99.0
            val nextGap = next?.let { (it.startSec - cur.endSec).coerceAtLeast(0.0) } ?: 99.0
            val tinySingle = words.size <= 1 && dur < 1.0
            val repeatSingle = tinySingle && prev != null && normalizeMergeText(prev.text) == normalizeMergeText(cur.text)
            if (repeatSingle) {
                Log.d(tag, "finalCleanup drop_duplicate_short text=${cur.text}")
                continue
            }
            filtered += cur
        }
        return restoreMissingTerminalPunctuation(filtered)
    }

    private fun startsContinuationText(text: String): Boolean {
        val tokens = text.trimStart()
            .trimStart('"', '\'', '“', '”', '‘', '’', '(', '[')
            .split(Regex("\\s+"))
            .map {
                it.trim(',', ';', ':', '.', '!', '?', ')', ']', '"', '\'')
                    .lowercase()
            }
            .filter { it.isNotBlank() }
        val token = tokens.getOrNull(0) ?: return false
        if (token == "to" && tokens.getOrNull(1) == "make" && tokens.getOrNull(2) == "matters") return false
        return token in setOf("to", "for", "of", "by", "in", "on", "with", "from", "that", "which", "because", "prove", "using")
    }

    private fun startsClearSentenceStarterText(text: String): Boolean {
        val tokens = text.trim()
            .split(Regex("\\s+"))
            .map {
                it.trim(',', ';', ':', '.', '!', '?', ')', ']', '"', '\'')
                    .lowercase()
            }
            .filter { it.isNotBlank() }
        val t0 = tokens.getOrNull(0) ?: return false
        val t1 = tokens.getOrNull(1).orEmpty()
        val t2 = tokens.getOrNull(2).orEmpty()
        if (t0 in setOf("yes", "no", "yeah", "ok", "okay", "right")) return true
        if (t0 == "according" && t1 == "to") return true
        if ((t0 == "that's" || t0 == "thats") && t1 == "right") return true
        if (t0 == "right" && t1 == "i" && t2 == "mean") return true
        if (t0 == "i" && t1 == "mean") return true
        if (t0 == "this" && t1 == "abrupt") return true
        if (t0 == "these" && t1 == "abrupt") return true
        if (t0 == "the" && t1 == "international") return true
        if (t0 == "to" && t1 == "make" && t2 == "matters") return true
        if (t0 == "previously") return true
        if (t0 == "absolutely") return true
        if (t0 == "so" && t1 == "if") return true
        if (t0 == "so" && (t1 == "i'm" || t1 == "im" || t1 == "i")) return true
        if (t0 == "when") return true
        if (t0 == "at") return true
        if (t0 == "google" && t1 == "recently") return true
        if (t0 in setOf("this", "these", "those", "there", "he", "she", "they", "we", "it") &&
            t1 in setOf("recently", "also", "now", "will", "is", "are", "was", "were", "has", "have", "had")
        ) {
            return true
        }
        return false
    }

    private fun restoreMissingTerminalPunctuation(items: List<SentenceTimestamp>): List<SentenceTimestamp> {
        if (items.isEmpty()) return items
        return items.mapIndexed { index, item ->
            if (!shouldRestoreTerminalPunctuation(item, index, items)) {
                item
            } else {
                Log.d(tag, "finalCleanup restore_period t=${"%.1f".format(item.endSec)} text=${item.text.take(60)}")
                item.copy(text = "${item.text.trimEnd()}.")
            }
        }
    }

    private fun shouldRestoreTerminalPunctuation(
        item: SentenceTimestamp,
        index: Int,
        items: List<SentenceTimestamp>
    ): Boolean {
        if (endsSentenceText(item.text)) return false
        if (index >= items.lastIndex) return false
        val words = item.text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 4) return false
        if (endsWithIncompleteTailText(item.text)) return false
        val last = lastTokenText(item.text) ?: return false
        if (last.endsWith("'s") || last.endsWith("’s")) return false
        if (last in DANGLING_FINAL_WORDS) return false
        return true
    }

    private fun endsWithIncompleteTailText(text: String): Boolean {
        val token = lastTokenText(text) ?: return false
        return token in setOf(
            "to",
            "for",
            "of",
            "by",
            "in",
            "on",
            "with",
            "from",
            "during",
            "between",
            "and",
            "or",
            "the",
            "a",
            "an"
        )
    }

    private fun protectsTextBoundary(curText: String, nextText: String): Boolean {
        val prev = lastTokenText(curText) ?: return false
        val next = firstTokenText(nextText) ?: return false
        if (prev == "south" && next == "korea") return true
        if (prev == "google" && next == "deepmind") return true
        if (prev == "alpha" && next == "go") return true
        if (prev == "seung" && next == "hyun") return true
        if (prev == "lee" && next == "sedol") return true
        if (prev == "yoon" && next == "koo") return true
        return false
    }

    private fun firstTokenText(text: String): String? =
        text.trim()
            .split(Regex("\\s+"))
            .firstOrNull()
            ?.trim(',', ';', ':', '.', '!', '?', ')', ']', '"', '\'')
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

    private fun lastTokenText(text: String): String? =
        text.trim()
            .split(Regex("\\s+"))
            .lastOrNull()
            ?.trim(',', ';', ':', '.', '!', '?', ')', ']', '"', '\'')
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

    private fun isStandaloneUtterance(text: String): Boolean {
        val normalized = text.trim().trimEnd('.', '!', '?').lowercase()
        return normalized in setOf("ok", "yes", "no", "yeah", "mm", "one", "right", "alone")
    }

    private fun endsSentenceText(text: String): Boolean {
        val t = text.trimEnd()
        return t.endsWith(".") || t.endsWith("?") || t.endsWith("!")
    }

    private fun overlapRatio(aStart: Double, aEnd: Double, bStart: Double, bEnd: Double): Double {
        val inter = (kotlin.math.min(aEnd, bEnd) - kotlin.math.max(aStart, bStart)).coerceAtLeast(0.0)
        val minDur = kotlin.math.min((aEnd - aStart).coerceAtLeast(0.001), (bEnd - bStart).coerceAtLeast(0.001))
        return (inter / minDur).coerceIn(0.0, 1.0)
    }

    private fun refreshSentenceButtons() {
        val container = folderButtonsContainer ?: return
        container.removeAllViews()
        val folders = sentenceStore.listDateContentFolders()
        if (folders.isEmpty()) {
            selectedDate = null
            selectedContent = null
            container.addView(makeHintText("No saved sentences."))
            updateFolderNavUi()
            return
        }
        if (selectedDate != null && folders.none { it.date == selectedDate }) selectedDate = null
        if (selectedContent != null && folders.none { it.path == selectedContent?.path }) selectedContent = null
        refreshOverlayVideoHeaderTitle()
        when {
            selectedDate == null -> {
                folders.map { it.date }.distinct().sortedDescending().forEach { date ->
                    container.addView(makeEntryRow(date, onOpen = {
                        selectedDate = date
                        selectedContent = null
                        refreshSentenceButtons()
                    }, onDelete = {
                        sentenceStore.deleteDateFolder(date)
                        if (selectedDate == date) {
                            selectedDate = null
                            selectedContent = null
                        }
                        refreshSentenceButtons()
                    }))
                }
            }

            selectedContent == null -> {
                val entries = folders.filter { it.date == selectedDate }.sortedByDescending { it.contentId }
                entries.forEach { entry ->
                    val label = sentenceStore.loadVideoTitle(entry)
                        ?: sentenceStore.loadYoutubeUrl(entry)?.let(YoutubeUrlParser::extractVideoId)
                        ?: entry.contentId
                    container.addView(makeEntryRow(label, onOpen = {
                        selectedContent = entry
                        refreshSentenceButtons()
                    }, onDelete = {
                        sentenceStore.deleteContentFolder(entry)
                        if (selectedContent?.path == entry.path) selectedContent = null
                        refreshSentenceButtons()
                    }))
                }
            }

            else -> {
                val folder = selectedContent ?: return
                val sentences = sentenceStore.loadSentences(folder).sortedBy { it.startSec }
                if (sentences.isEmpty()) {
                    container.addView(makeHintText("No sentences in folder."))
                } else {
                    sentences.forEach { sentence ->
                        val sentenceKey = sentenceUiKey(sentence)
                        val expanded = expandedSentenceKey == sentenceKey
                        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                        val leftColor = when {
                            translationLoadingKeys.contains(sentenceKey) -> 0xFF455A64.toInt()
                            showKoreanKeys.contains(sentenceKey) -> 0xFF2E7D32.toInt()
                            else -> 0xFF1565C0.toInt()
                        }
                        val rightColor = if (activeSentenceKey == sentenceKey && repeatSession != null) {
                            0xFFEF6C00.toInt()
                        } else {
                            0xFF6A1B9A.toInt()
                        }
                        val displayText = if (showKoreanKeys.contains(sentenceKey)) {
                            translatedTextByKey[sentenceKey] ?: sentence.text
                        } else {
                            sentence.text
                        }
                        val splitBtn = Button(this).apply {
                            isAllCaps = false
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SP)
                            text = "[${fmtSec(sentence.startSec)}-${fmtSec(sentence.endSec)}] $displayText"
                            setTextColor(0xFFFFFFFF.toInt())
                            isSingleLine = false
                            background = GradientDrawable(
                                GradientDrawable.Orientation.LEFT_RIGHT,
                                intArrayOf(leftColor, leftColor, rightColor, rightColor)
                            ).apply { cornerRadius = dp(6).toFloat() }
                            if (expanded) {
                                maxLines = Int.MAX_VALUE
                                ellipsize = null
                            } else {
                                maxLines = 2
                                ellipsize = TextUtils.TruncateAt.END
                            }
                            setOnTouchListener { v, event ->
                                if (event.action == MotionEvent.ACTION_UP) {
                                    val splitX = v.width / 2f
                                    if (event.x <= splitX) {
                                        onSentenceTranslateClicked(folder, sentence)
                                    } else {
                                        onSentenceButtonClicked(folder, sentence)
                                    }
                                }
                                true
                            }
                        }
                        val delBtn = Button(this).apply {
                            text = "Delete"
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SP)
                            setOnClickListener {
                                if (expandedSentenceKey == sentenceKey) {
                                    expandedSentenceKey = null
                                }
                                showKoreanKeys.remove(sentenceKey)
                                translationLoadingKeys.remove(sentenceKey)
                                translatedTextByKey.remove(sentenceKey)
                                sentenceStore.deleteSentence(folder, sentence)
                                refreshSentenceButtons()
                            }
                        }
                        row.addView(splitBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        row.addView(delBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                        container.addView(row)
                    }
                }
            }
        }
        updateFolderNavUi()
    }

    private fun sentenceUiKey(sentence: SentenceTimestamp): String =
        "${sentence.startSec}|${sentence.endSec}|${sentence.text}"

    private fun onSentenceButtonClicked(folder: SentenceTimestampStore.FolderEntry, sentence: SentenceTimestamp) {
        val sentenceKey = sentenceUiKey(sentence)
        val isEnglishExpanded = expandedSentenceKey == sentenceKey && !showKoreanKeys.contains(sentenceKey)
        if (isEnglishExpanded) {
            expandedSentenceKey = null
            if (activeSentenceKey == sentenceKey && repeatSession != null) {
                repeatCancelled = true
                finalizeRepeat(cancelled = true)
            }
            refreshSentenceButtons()
            return
        }
        showKoreanKeys.remove(sentenceKey)
        expandedSentenceKey = sentenceKey
        playSentenceRepeat(folder, sentence, repeatCount = 5)
        refreshSentenceButtons()
    }

    private fun onSentenceTranslateClicked(folder: SentenceTimestampStore.FolderEntry, sentence: SentenceTimestamp) {
        val sentenceKey = sentenceUiKey(sentence)
        val isKoreanExpanded = expandedSentenceKey == sentenceKey && showKoreanKeys.contains(sentenceKey)
        if (isKoreanExpanded) {
            expandedSentenceKey = null
            showKoreanKeys.remove(sentenceKey)
            if (activeSentenceKey == sentenceKey && repeatSession != null) {
                repeatCancelled = true
                finalizeRepeat(cancelled = true)
            }
            refreshSentenceButtons()
            return
        }
        expandedSentenceKey = sentenceKey
        showKoreanKeys.add(sentenceKey)
        translatedTextByKey[sentenceKey]?.takeIf { it.isNotBlank() }?.let {
            refreshSentenceButtons()
            return
        }
        if (translationLoadingKeys.contains(sentenceKey)) {
            refreshSentenceButtons()
            return
        }
        val apiKey = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
        if (apiKey.isNullOrBlank()) {
            statusTextView?.text = "API key is required for translation."
            refreshSentenceButtons()
            return
        }
        translationLoadingKeys.add(sentenceKey)
        refreshSentenceButtons()
        statusTextView?.text = "Translating sentence..."
        thread(name = "overlay-translate") {
            sentenceTranslationClient.translateEnglishToKorean(sentence.text, apiKey)
                .onSuccess { translated ->
                    val cleaned = translated.trim()
                    if (cleaned.isNotBlank()) {
                        mainHandler.post {
                            translatedTextByKey[sentenceKey] = cleaned
                            statusTextView?.text = "Translation: $cleaned"
                            refreshSentenceButtons()
                        }
                    } else {
                        mainHandler.post { statusTextView?.text = "Translation empty." }
                    }
                }
                .onFailure { err ->
                    mainHandler.post {
                        statusTextView?.text = "Translation failed: ${err.message ?: "unknown error"}"
                    }
                }
            mainHandler.post {
                translationLoadingKeys.remove(sentenceKey)
                refreshSentenceButtons()
            }
        }
    }

    private fun playSentenceRepeat(folder: SentenceTimestampStore.FolderEntry, sentence: SentenceTimestamp, repeatCount: Int) {
        val folderUrl = sentenceStore.loadYoutubeUrl(folder) ?: return
        val folderVideoId = YoutubeUrlParser.extractVideoId(folderUrl) ?: return
        val sentenceKey = sentenceUiKey(sentence)
        repeatCancelled = false
        activeSentenceKey = sentenceKey
        refreshSentenceButtons()
        val target = "${YoutubeUrlParser.canonicalWatchUrl(folderVideoId)}&t=${sentence.startSec.toInt()}s"
        val playbackTarget = currentPlaybackTarget()
        repeatHandler.removeCallbacksAndMessages(null)
        ChromeCaptureStore.clearTransientPlaybackSample(this)
        if (PlaybackTarget.isChrome(playbackTarget) && resolveCurrentVideoId() == folderVideoId) {
            statusTextView?.text = "Repeating current Chrome video..."
            startRepeatSession(sentence, repeatCount, folderVideoId)
            return
        }
        statusTextView?.text = "Opening saved video in ${PlaybackTarget.label(playbackTarget)}..."
        openYoutubeTarget(target, playbackTarget)
        val minWaitMs = if (PlaybackTarget.isChrome(playbackTarget)) 900L else 1_500L
        waitForVideoSwitchThenStart(folderVideoId, sentence, repeatCount, minWaitMs = minWaitMs)
    }

    private fun startRepeatSession(sentence: SentenceTimestamp, repeatCount: Int, expectedVideoId: String?) {
        PlaybackAudioHelper.ensureAudible(this)
        val startMs = (sentence.startSec * 1000.0).toLong().coerceAtLeast(0L)
        val endMs = (sentence.endSec * 1000.0).toLong().coerceAtLeast(startMs + 300L)
        repeatSession = RepeatSession(
            sentence = sentence,
            expectedVideoId = expectedVideoId,
            repeatCount = repeatCount.coerceAtLeast(1),
            startMs = startMs,
            endMs = endMs
        )
        repeatHandler.removeCallbacksAndMessages(null)
        repeatHandler.post(repeatTickRunnable)
    }

    private fun waitForVideoSwitchThenStart(
        expectedVideoId: String,
        sentence: SentenceTimestamp,
        repeatCount: Int,
        minWaitMs: Long = 0L
    ) {
        val startAt = System.currentTimeMillis()
        repeatHandler.post(object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startAt
                if (elapsed > 10_000L) {
                    statusTextView?.text = "Repeat failed: video switch timeout"
                    return
                }
                val current = resolveCurrentVideoId()
                if (elapsed >= minWaitMs && current == expectedVideoId) {
                    startRepeatSession(sentence, repeatCount, expectedVideoId)
                    return
                }
                repeatHandler.postDelayed(this, 220L)
            }
        })
    }

    private val repeatTickRunnable = object : Runnable {
        override fun run() {
            val s = repeatSession ?: return
            if (repeatCancelled) {
                finalizeRepeat(cancelled = true)
                return
            }
            val now = System.currentTimeMillis()
            if (now - s.startedAtMs > 180_000L) {
                s.state = RepeatState.FAILED
            }
            val currentVideo = resolveCurrentVideoId()
            if (!s.expectedVideoId.isNullOrBlank() && !currentVideo.isNullOrBlank() && currentVideo != s.expectedVideoId) {
                s.state = RepeatState.FAILED
            }

            when (s.state) {
                RepeatState.SEEKING -> {
                    if (now - s.actionAtMs > 300L) {
                        ChromePlaybackReader.seekToMs(this@OverlayService, s.startMs, currentPlaybackTarget())
                        s.actionAtMs = now
                    }
                    val pos = ChromePlaybackReader.readControllerPrecisePositionMs(this@OverlayService, currentPlaybackTarget())
                    if (pos != null && kotlin.math.abs(pos - s.startMs) <= 650L) {
                        s.state = RepeatState.WAITING_FOR_PLAY
                        s.stateEnteredAtMs = now
                        s.actionAtMs = 0L
                    } else if (now - s.stateEnteredAtMs > 3_000L) {
                        s.state = RepeatState.FAILED
                    }
                }

                RepeatState.WAITING_FOR_PLAY -> {
                    if (now - s.actionAtMs > 450L) {
                        PlaybackAudioHelper.ensureAudible(this@OverlayService)
                        ChromePlaybackReader.play(this@OverlayService, currentPlaybackTarget())
                        s.actionAtMs = now
                    }
                    val playing = ChromePlaybackReader.isControllerPlaying(this@OverlayService, currentPlaybackTarget()) == true
                    if (playing) {
                        s.state = RepeatState.PLAYING
                        s.stateEnteredAtMs = now
                    } else if (now - s.stateEnteredAtMs > 2_500L) {
                        s.state = RepeatState.FAILED
                    }
                }

                RepeatState.PLAYING -> {
                    val pos = ChromePlaybackReader.readControllerPrecisePositionMs(this@OverlayService, currentPlaybackTarget())
                    if (pos == null) {
                        s.state = RepeatState.FAILED
                    } else if (pos >= s.endMs) {
                        if (s.currentLoop >= s.repeatCount) {
                            finalizeRepeat(cancelled = false)
                            return
                        }
                        s.currentLoop += 1
                        s.state = RepeatState.RESTARTING
                        s.stateEnteredAtMs = now
                        s.actionAtMs = 0L
                    }
                }

                RepeatState.RESTARTING -> {
                    if (now - s.actionAtMs > 320L) {
                        ChromePlaybackReader.pause(this@OverlayService, currentPlaybackTarget())
                        ChromePlaybackReader.seekToMs(this@OverlayService, s.startMs, currentPlaybackTarget())
                        s.actionAtMs = now
                    }
                    val pos = ChromePlaybackReader.readControllerPrecisePositionMs(this@OverlayService, currentPlaybackTarget())
                    if (pos != null && kotlin.math.abs(pos - s.startMs) <= 650L) {
                        s.state = RepeatState.WAITING_FOR_PLAY
                        s.stateEnteredAtMs = now
                        s.actionAtMs = 0L
                    } else if (now - s.stateEnteredAtMs > 2_000L) {
                        s.state = RepeatState.FAILED
                    }
                }

                RepeatState.FAILED -> {
                    finalizeRepeat(cancelled = false, failed = true)
                    return
                }
            }
            statusTextView?.text = "Repeat ${s.currentLoop}/${s.repeatCount}: ${fmtSec(s.sentence.startSec)}-${fmtSec(s.sentence.endSec)}"
            repeatHandler.postDelayed(this, 100L)
        }
    }

    private fun finalizeRepeat(cancelled: Boolean, failed: Boolean = false) {
        repeatHandler.removeCallbacksAndMessages(null)
        runCatching { ChromePlaybackReader.pause(this, currentPlaybackTarget()) }
        val s = repeatSession
        if (s != null) {
            statusTextView?.text = when {
                failed -> "Repeat failed."
                cancelled -> "Repeat cancelled."
                else -> "Repeat done (${s.repeatCount})."
            }
        }
        repeatSession = null
        repeatCancelled = false
        activeSentenceKey = null
        refreshSentenceButtons()
    }

    private fun resolveCurrentSourceUrl(): String? {
        val playbackTarget = currentPlaybackTarget()
        val clipboardUrl = if (PlaybackTarget.isChrome(playbackTarget)) {
            null
        } else {
            prefs.getString(KEY_CLIPBOARD_CAPTURED_URL, null)
        }
        val observed = ChromeCaptureStore.getObservedUrl(this)
        val selected = prefs.getString(KEY_SELECTED_URL, null)
        val fallback = prefs.getString(KEY_LAST_URL, null)
        return if (PlaybackTarget.isChrome(playbackTarget)) {
            observed ?: selected ?: fallback
        } else {
            clipboardUrl ?: selected ?: fallback ?: observed
        }
    }

    private fun resolveSourceUrlForDownload(): String? {
        val playbackTarget = currentPlaybackTarget()
        if (PlaybackTarget.isChrome(playbackTarget)) {
            val observed = ChromeCaptureStore.getObservedUrl(this)
            val selected = prefs.getString(KEY_SELECTED_URL, null)
            val fallback = prefs.getString(KEY_LAST_URL, null)
            val resolved = observed ?: selected ?: fallback
            Log.i(tag, "downloadSourceResolved target=chrome hasUrl=${!resolved.isNullOrBlank()}")
            return resolved
        }

        val capturedAtMs = prefs.getLong(KEY_CLIPBOARD_CAPTURED_AT_MS, 0L)
        val ageMs = System.currentTimeMillis() - capturedAtMs
        val clipboardUrl = prefs.getString(KEY_CLIPBOARD_CAPTURED_URL, null)
        if (clipboardUrl.isNullOrBlank()) {
            Log.w(tag, "downloadSourceBlocked target=youtube_app reason=focused_clipboard_url_missing")
            return null
        }
        if (capturedAtMs <= 0L || ageMs > CLIPBOARD_CAPTURE_FRESH_MS) {
            Log.w(tag, "downloadSourceBlocked target=youtube_app reason=focused_clipboard_url_stale ageMs=$ageMs")
            return null
        }
        Log.i(
            tag,
            "downloadSourceResolved target=youtube_app videoId=${YoutubeUrlParser.extractVideoId(clipboardUrl)} " +
                "source=focused_clipboard ageMs=$ageMs"
        )
        return clipboardUrl
    }

    private fun resolveCurrentVideoId(): String? {
        val playbackTarget = currentPlaybackTarget()
        val snapshotVideoId = ChromePlaybackReader.readSnapshot(this, playbackTarget)?.videoId
        val sourceVideoId = resolveCurrentSourceUrl()?.let(YoutubeUrlParser::extractVideoId)
        val observedVideoId = ChromeCaptureStore.getObservedVideoId(this)
        return if (PlaybackTarget.isChrome(playbackTarget)) {
            snapshotVideoId ?: observedVideoId ?: sourceVideoId
        } else {
            snapshotVideoId ?: sourceVideoId ?: observedVideoId
        }
    }

    private fun currentPlaybackTarget(): String =
        PlaybackTarget.current(this)

    private fun captureClipboardYoutubeUrl(showStatus: Boolean): String? {
        val normalized = YoutubeClipboardReader.readYoutubeUrl(this)
        if (normalized.isNullOrBlank()) {
            Log.w(tag, "clipboardCapture failed reason=no_youtube_url")
            return null
        }
        prefs.edit()
            .putString(KEY_SELECTED_URL, normalized)
            .putString(KEY_LAST_URL, normalized)
            .apply()
        ChromeCaptureStore.saveObservedUrl(this, normalized)
        Log.i(tag, "clipboardCapture success videoId=${YoutubeUrlParser.extractVideoId(normalized)}")
        if (showStatus) {
            statusTextView?.text = "Clipboard YouTube URL captured: $normalized"
        }
        return normalized
    }

    private fun fetchAndShowClipboardTitle(sourceUrl: String) {
        val videoId = YoutubeUrlParser.extractVideoId(sourceUrl)
        statusTextView?.text = "Clipboard URL captured. Loading title..."
        thread(name = "overlay-title", isDaemon = true) {
            runCatching {
                extractor.fetchMetadata(sourceUrl)
            }.onSuccess { metadata ->
                mainHandler.post {
                    val currentSelected = prefs.getString(KEY_SELECTED_URL, null)
                    if (currentSelected != sourceUrl || pipelineRunning) return@post
                    val title = metadata.title?.takeIf { it.isNotBlank() } ?: return@post
                    currentVideoTitle = title
                    refreshOverlayVideoHeaderTitle()
                    statusTextView?.text = if (waitingEndCapture && capturedStartSec != null) {
                        "Start: ${capturedStartSec}s | $title"
                    } else {
                        "Title: $title"
                    }
                }
                Log.i(tag, "metadataResolved videoId=${metadata.id ?: videoId} titlePresent=${!metadata.title.isNullOrBlank()}")
            }.onFailure { err ->
                Log.w(tag, "metadataLookupFailed videoId=$videoId reason=${summarizeError(err)}")
                mainHandler.post {
                    if (!pipelineRunning) {
                        statusTextView?.text = "Clipboard URL captured. Title lookup failed."
                    }
                }
            }
        }
    }

    private fun selectSavedContent(savedFile: java.io.File) {
        val contentDir = savedFile.parentFile ?: return
        val dateDir = contentDir.parentFile ?: return
        selectedDate = dateDir.name
        selectedContent = SentenceTimestampStore.FolderEntry(dateDir.name, contentDir.name, contentDir)
        currentVideoTitle = selectedContent?.let { sentenceStore.loadVideoTitle(it) } ?: currentVideoTitle
    }

    private fun navigateUp() {
        when {
            selectedContent != null -> selectedContent = null
            selectedDate != null -> selectedDate = null
            else -> Unit
        }
        refreshSentenceButtons()
    }

    private fun updateFolderNavUi() {
        btnFolderUp?.isEnabled = selectedDate != null || selectedContent != null
        btnFolderUp?.text = when {
            selectedContent != null -> "Up (Sentence)"
            selectedDate != null -> "Up (Content)"
            else -> "Up (Date)"
        }
    }

    private fun updateFolderFoldUi() {
        folderScrollView?.visibility = if (browserFolded) View.GONE else View.VISIBLE
        btnFolderFold?.text = if (browserFolded) "Unfold" else "Fold"
    }

    private fun makeHintText(text: String): TextView = TextView(this).apply {
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * FONT_SCALE)
        this.text = text
    }

    private fun makeEntryRow(label: String, onOpen: () -> Unit, onDelete: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val openBtn = Button(this@OverlayService).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SP)
                isAllCaps = false
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener { onOpen() }
            }
            val delBtn = Button(this@OverlayService).apply {
                text = "Delete"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SP)
                setOnClickListener { onDelete() }
            }
            addView(openBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(delBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun resetCaptureState() {
        capturedStartSec = null
        capturedEndSec = null
        captureVideoId = null
        waitingEndCapture = false
        toggleButton?.text = "Set Start"
        statusTextView?.text = buildCaptureStatus()
        setOutputText("")
        ChromeCaptureStore.clearTransientPlaybackSample(this)
    }

    private fun resetAllState() {
        expandedSentenceKey = null
        showKoreanKeys.clear()
        translationLoadingKeys.clear()
        translatedTextByKey.clear()
        currentVideoTitle = null
        if (repeatSession != null) {
            repeatCancelled = true
            finalizeRepeat(cancelled = true)
        }
        resetCaptureState()
        refreshSentenceButtons()
    }

    private fun refreshOverlayVideoHeaderTitle() {
        val header = headerTitleTextView ?: return
        val selectedTitle = selectedContent?.let { sentenceStore.loadVideoTitle(it) }
        val title = selectedTitle
            ?.takeIf { it.isNotBlank() }
            ?: currentVideoTitle?.takeIf { it.isNotBlank() }
            ?: "-"
        header.text = "Project3 Overlay\n$title"
    }

    private fun setOutputText(text: String) {
        outputTextView?.text = text
        outputScrollView?.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun updatePerfSummary(summary: String?) {
        lastPerfSummary = summary
        perfTextView?.text = if (summary.isNullOrBlank()) {
            "Perf: -"
        } else {
            "Perf: $summary"
        }
    }

    private fun buildPerfSummary(sttElapsedMs: Long, requestedSec: Int): String {
        val sttSec = sttElapsedMs / 1000.0
        val req = requestedSec.coerceAtLeast(1)
        val ratio = sttSec / req.toDouble()
        return "perf STT/request=${"%.2f".format(ratio)}x (${String.format("%.1f", sttSec)}s/${req}s)"
    }

    private fun buildCaptureStatus(): String {
        val s = capturedStartSec?.let { "${it}s" } ?: "unset"
        val e = capturedEndSec?.let { "${it}s" } ?: "unset"
        val v = captureVideoId ?: "unset"
        return "start=$s end=$e video=$v"
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
            sentences.forEach {
                append("[${fmtSec(it.startSec)} - ${fmtSec(it.endSec)}] ${it.text}\n")
            }
        }.trim()
    }

    private fun openYoutubeTarget(target: String, playbackTarget: String) {
        val uri = Uri.parse(target)
        val normalizedTarget = PlaybackTarget.normalize(playbackTarget)
        val packageName = PlaybackTarget.mediaPackage(normalizedTarget)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(packageName)
            addCategory(Intent.CATEGORY_BROWSABLE)
            putExtra(Browser.EXTRA_APPLICATION_ID, packageName)
            if (PlaybackTarget.isChrome(normalizedTarget)) {
                putExtra(Browser.EXTRA_CREATE_NEW_TAB, false)
                putExtra("create_new_tab", false)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try {
            startActivity(intent)
        } catch (err: ActivityNotFoundException) {
            if (PlaybackTarget.isChrome(normalizedTarget)) {
                statusTextView?.text = "Chrome is not available."
                return
            }
            openYoutubeTargetWithAppScheme(target, err)
        } catch (err: SecurityException) {
            statusTextView?.text = "Cannot open ${PlaybackTarget.label(normalizedTarget)}: ${err.message ?: "permission denied"}"
        }
    }

    private fun openYoutubeTargetWithAppScheme(target: String, cause: Throwable) {
        val videoId = YoutubeUrlParser.extractVideoId(target)
        if (videoId.isNullOrBlank()) {
            statusTextView?.text = "Cannot open YouTube app: ${cause.message ?: "video id missing"}"
            return
        }
        val seconds = YoutubeUrlParser.extractSeconds(target).coerceAtLeast(0)
        val appUri = Uri.parse("vnd.youtube:$videoId?start=$seconds")
        val appIntent = Intent(Intent.ACTION_VIEW, appUri).apply {
            setPackage(PlaybackTarget.YOUTUBE_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try {
            startActivity(appIntent)
        } catch (err: ActivityNotFoundException) {
            statusTextView?.text = "YouTube app is not available."
        } catch (err: SecurityException) {
            statusTextView?.text = "Cannot open YouTube app: ${err.message ?: "permission denied"}"
        }
    }

    private fun createDragTouchListener(target: View): View.OnTouchListener {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        return View.OnTouchListener { _, event ->
            val wm = windowManager ?: return@OnTouchListener false
            val lp = overlayParams ?: return@OnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - touchX).toInt()
                    lp.y = startY + (event.rawY - touchY).toInt()
                    wm.updateViewLayout(target, lp)
                    true
                }

                else -> false
            }
        }
    }

    private fun createResizeTouchListener(target: View, screenHeightPx: Int): View.OnTouchListener {
        var startHeight = 0
        var touchY = 0f
        val minHeight = (screenHeightPx * OVERLAY_MIN_HEIGHT_RATIO).toInt().coerceAtLeast(dp(220))
        val maxHeight = (screenHeightPx * OVERLAY_MAX_HEIGHT_RATIO).toInt().coerceAtLeast(minHeight + dp(120))
        return View.OnTouchListener { _, event ->
            val wm = windowManager ?: return@OnTouchListener false
            val lp = overlayParams ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startHeight = lp.height
                    touchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaY = (event.rawY - touchY).toInt()
                    lp.height = (startHeight + deltaY).coerceIn(minHeight, maxHeight)
                    wm.updateViewLayout(target, lp)
                    true
                }

                else -> false
            }
        }
    }

    private fun fmtSec(sec: Double): String {
        val totalTenths = (sec.coerceAtLeast(0.0) * 10.0).roundToInt()
        val totalSec = totalTenths / 10
        val tenths = totalTenths % 10
        val min = totalSec / 60
        val s = totalSec % 60
        return String.format("%02d:%02d.%d", min, s, tenths)
    }

    private fun summarizeError(err: Throwable): String {
        val root = generateSequence(err) { it.cause }.last()
        val type = root.javaClass.simpleName.ifBlank { "Error" }
        val message = root.message?.trim().orEmpty().replace("\n", " ")
        val detail = if (message.isBlank()) "no message" else message
        return "$type: ${detail.take(220)}"
    }

    private fun shouldFallbackRedownload(source: DownloadedAudioSource, err: Throwable): Boolean {
        if (!source.fromCache) return false
        if (!isLikelyInvalidLocalSourcePath(source.path)) return false
        val rootMessage = generateSequence(err) { it.cause }.last().message?.lowercase().orEmpty()
        return rootMessage.contains("invalid data found when processing input")
            || rootMessage.contains("error opening input")
            || rootMessage.contains("downloaded file validation failed")
    }

    private fun isLikelyInvalidLocalSourcePath(path: String): Boolean {
        val lower = path.lowercase()
        if (lower.endsWith(".ytdl") || lower.endsWith(".part") || lower.endsWith(".tmp")) return true
        val file = File(path)
        if (!file.exists()) return true
        val extensionAllowed = setOf("webm", "m4a", "mp4", "opus", "ogg", "mp3", "wav", "mka")
        if (file.extension.lowercase() !in extensionAllowed) return true
        return file.length() < 1L * 1024L * 1024L
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Project3 Overlay")
            .setContentText("Overlay controls are active.")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Overlay Service", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_CLIPBOARD_CAPTURE_RESULT = "com.example.project3.CLIPBOARD_CAPTURE_RESULT"
        const val EXTRA_AUTO_START_SEC = "extra_auto_start_sec"
        const val EXTRA_AUTO_END_SEC = "extra_auto_end_sec"
        const val EXTRA_CLIPBOARD_CAPTURE_AFTER = "extra_clipboard_capture_after"
        const val EXTRA_CLIPBOARD_CAPTURE_URL = "extra_clipboard_capture_url"
        const val EXTRA_CLIPBOARD_CAPTURE_START_SEC = "extra_clipboard_capture_start_sec"
        const val EXTRA_CLIPBOARD_CAPTURE_END_SEC = "extra_clipboard_capture_end_sec"
        const val AFTER_CAPTURE_TOGGLE = "toggle"
        const val AFTER_CAPTURE_RUN_STT = "run_stt"
        private const val FONT_SCALE = 0.8f
        private const val BUTTON_TEXT_SP = 11f
        private const val CHANNEL_ID = "project3_overlay_channel"
        private const val NOTIFICATION_ID = 3101
        private const val PREFS_NAME = "project3_main"
        private const val KEY_API_KEY = "openai_api_key"
        private const val KEY_SELECTED_URL = "selected_url"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_CLIPBOARD_CAPTURED_URL = "clipboard_captured_url"
        private const val KEY_CLIPBOARD_CAPTURED_AT_MS = "clipboard_captured_at_ms"
        private const val KEY_STT_MODE = "stt_mode"
        private const val KEY_ON_DEVICE_PROFILE = "on_device_profile"
        private const val CLIPBOARD_CAPTURE_FRESH_MS = 2 * 60 * 1000L
        private const val STT_PAD_BEFORE_SEC_ACCURATE = 6
        private const val STT_PAD_AFTER_SEC_ACCURATE = 4
        private const val STT_PAD_BEFORE_SEC_FAST = 2
        private const val STT_PAD_AFTER_SEC_FAST = 2
        private const val FAST_CHUNK_SEC = 30
        private const val FAST_CHUNK_OVERLAP_SEC = 4
        private const val FAST_CHUNK_EDGE_PAD_SEC = 1
        private const val FAST_CHUNK_INTERNAL_PAD_SEC = 2
        private const val FAST_CHUNK_MIN_REQUEST_SEC = 30
        private const val MERGE_SEGMENT_TIME_EPS = 0.25
        private const val MERGE_SEGMENT_OVERLAP_RATIO = 0.6
        private const val MERGE_WORD_TIME_EPS = 0.18
        private const val MERGE_WORD_OVERLAP_RATIO_LOOSE = 0.6
        private const val OVERLAY_MIN_HEIGHT_RATIO = 0.28
        private const val OVERLAY_MAX_HEIGHT_RATIO = 0.88
        private const val FINAL_HARD_CUT_REPAIR_GAP_SEC = 0.28
        private const val FINAL_HARD_CUT_REPAIR_MAX_SPAN_SEC = 30.0
        private val DANGLING_FINAL_WORDS = setOf(
            "to",
            "for",
            "of",
            "by",
            "in",
            "on",
            "with",
            "from",
            "during",
            "between",
            "and",
            "or",
            "the",
            "a",
            "an",
            "if",
            "when",
            "because",
            "that",
            "which",
            "who",
            "whose",
            "whom",
            "where",
            "after",
            "before",
            "since",
            "unless",
            "although",
            "though"
        )
    }
}
