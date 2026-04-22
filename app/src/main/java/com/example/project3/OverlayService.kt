package com.example.project3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Browser
import android.text.TextUtils
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
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class OverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var statusTextView: TextView? = null
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
    private val whisperClient by lazy { WhisperApiClient() }
    private val sentenceAssembler by lazy { SentenceAssembler() }
    private val sentenceStore by lazy { SentenceTimestampStore(this) }
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

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC111111.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val title = TextView(this).apply {
            text = "Project3 Overlay"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * FONT_SCALE)
        }
        statusTextView = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * FONT_SCALE)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            text = buildCaptureStatus()
        }
        toggleButton = Button(this).apply {
            text = "Set Start"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SP)
            setOnClickListener { onCaptureToggle() }
        }
        val resetButton = Button(this).apply {
            text = "Reset"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SP)
            setOnClickListener { resetCaptureState() }
        }
        val closeButton = Button(this).apply {
            text = "Close"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SP)
            setOnClickListener { stopSelf() }
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

        root.addView(title)
        root.addView(statusTextView)
        root.addView(controlsRow)
        root.addView(outputScrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        root.addView(folderRow)
        root.addView(folderScrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
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
        title.setOnTouchListener(createDragTouchListener(root))
        wm.addView(root, params)
        windowManager = wm
        overlayView = root
        overlayParams = params
        refreshSentenceButtons()
        updateFolderFoldUi()
    }

    private fun onCaptureToggle() {
        if (pipelineRunning) {
            Toast.makeText(this, "STT in progress.", Toast.LENGTH_SHORT).show()
            return
        }
        val snapshot = ChromePlaybackReader.readSnapshot(this)
        if (snapshot == null || snapshot.positionMs < 0L) {
            statusTextView?.text = "Playback not detected. Open YouTube in Chrome."
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
        runStt(start, sec)
    }

    private fun runStt(startSec: Int, endSec: Int) {
        if (pipelineRunning) return
        pipelineRunning = true
        val apiKey = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
        val sourceUrl = resolveCurrentSourceUrl()
        if (apiKey.isNullOrBlank() || sourceUrl.isNullOrBlank()) {
            pipelineRunning = false
            statusTextView?.text = "Missing API key or URL."
            return
        }
        setOutputText("")
        statusTextView?.text = "Downloading with yt-dlp..."
        thread(name = "overlay-stt") {
            runCatching {
                val localSource = extractor.downloadToLocal(sourceUrl)
                mainHandler.post { statusTextView?.text = "Cutting audio segment..." }
                val segmentFile = segmentDownloader.cutSegmentFromLocal(localSource.path, startSec, endSec)
                mainHandler.post { statusTextView?.text = "Uploading to Whisper..." }
                val transcript = whisperClient.transcribeVerboseEnglish(apiKey, segmentFile)
                val shifted = offsetTranscript(transcript, startSec)
                val sentences = sentenceAssembler.build(shifted, startSec.toDouble(), endSec.toDouble())
                val canonical = YoutubeUrlParser.canonicalWatchUrlFromAny(sourceUrl) ?: sourceUrl
                val saveFile = sentenceStore.save(canonical, sentences)
                mainHandler.post {
                    setOutputText(renderSentenceResult(sentences))
                    statusTextView?.text = "STT done: ${saveFile.name}"
                    selectSavedContent(saveFile)
                    refreshSentenceButtons()
                }
            }.onFailure { err ->
                mainHandler.post {
                    statusTextView?.text = "STT failed: ${err.message ?: "unknown error"}"
                }
            }
            pipelineRunning = false
        }
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
                    val label = sentenceStore.loadYoutubeUrl(entry)?.let(YoutubeUrlParser::extractVideoId) ?: entry.contentId
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
                        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                        val playBtn = Button(this).apply {
                            isAllCaps = false
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SP)
                            text = "[${fmtSec(sentence.startSec)}-${fmtSec(sentence.endSec)}] ${sentence.text}"
                            isSingleLine = false
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                            setOnClickListener { playSentenceRepeat(folder, sentence, repeatCount = 5) }
                        }
                        val delBtn = Button(this).apply {
                            text = "Delete"
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_TEXT_SP)
                            setOnClickListener {
                                sentenceStore.deleteSentence(folder, sentence)
                                refreshSentenceButtons()
                            }
                        }
                        row.addView(playBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        row.addView(delBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                        container.addView(row)
                    }
                }
            }
        }
        updateFolderNavUi()
    }

    private fun playSentenceRepeat(folder: SentenceTimestampStore.FolderEntry, sentence: SentenceTimestamp, repeatCount: Int) {
        val folderUrl = sentenceStore.loadYoutubeUrl(folder) ?: return
        val folderVideoId = YoutubeUrlParser.extractVideoId(folderUrl) ?: return
        val sentenceKey = "${sentence.startSec}|${sentence.endSec}|${sentence.text}"
        if (activeSentenceKey == sentenceKey && repeatSession != null) {
            repeatCancelled = true
            finalizeRepeat(cancelled = true)
            return
        }
        repeatCancelled = false
        activeSentenceKey = sentenceKey
        val currentVideoId = resolveCurrentVideoId()
        if (currentVideoId != folderVideoId) {
            val target = "${YoutubeUrlParser.canonicalWatchUrl(folderVideoId)}&t=${sentence.startSec.toInt()}s"
            openYoutubeTargetInChrome(target)
            waitForVideoSwitchThenStart(folderVideoId, sentence, repeatCount)
            return
        }
        startRepeatSession(sentence, repeatCount, folderVideoId)
    }

    private fun startRepeatSession(sentence: SentenceTimestamp, repeatCount: Int, expectedVideoId: String?) {
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

    private fun waitForVideoSwitchThenStart(expectedVideoId: String, sentence: SentenceTimestamp, repeatCount: Int) {
        val startAt = System.currentTimeMillis()
        repeatHandler.post(object : Runnable {
            override fun run() {
                if (System.currentTimeMillis() - startAt > 8_000L) {
                    statusTextView?.text = "Repeat failed: video switch timeout"
                    return
                }
                val current = resolveCurrentVideoId()
                if (current == expectedVideoId) {
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
                        ChromePlaybackReader.seekToMs(this@OverlayService, s.startMs)
                        s.actionAtMs = now
                    }
                    val pos = ChromePlaybackReader.readControllerPrecisePositionMs(this@OverlayService)
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
                        ChromePlaybackReader.play(this@OverlayService)
                        s.actionAtMs = now
                    }
                    val playing = ChromePlaybackReader.isControllerPlaying(this@OverlayService) == true
                    if (playing) {
                        s.state = RepeatState.PLAYING
                        s.stateEnteredAtMs = now
                    } else if (now - s.stateEnteredAtMs > 2_500L) {
                        s.state = RepeatState.FAILED
                    }
                }

                RepeatState.PLAYING -> {
                    val pos = ChromePlaybackReader.readControllerPrecisePositionMs(this@OverlayService)
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
                        ChromePlaybackReader.pause(this@OverlayService)
                        ChromePlaybackReader.seekToMs(this@OverlayService, s.startMs)
                        s.actionAtMs = now
                    }
                    val pos = ChromePlaybackReader.readControllerPrecisePositionMs(this@OverlayService)
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
        runCatching { ChromePlaybackReader.pause(this) }
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
    }

    private fun resolveCurrentSourceUrl(): String? {
        val observed = ChromeCaptureStore.getObservedUrl(this)
        val selected = prefs.getString(KEY_SELECTED_URL, null)
        val fallback = prefs.getString(KEY_LAST_URL, null)
        return observed ?: selected ?: fallback
    }

    private fun resolveCurrentVideoId(): String? =
        ChromePlaybackReader.readSnapshot(this)?.videoId
            ?: ChromeCaptureStore.getObservedVideoId(this)
            ?: resolveCurrentSourceUrl()?.let(YoutubeUrlParser::extractVideoId)

    private fun selectSavedContent(savedFile: java.io.File) {
        val contentDir = savedFile.parentFile ?: return
        val dateDir = contentDir.parentFile ?: return
        selectedDate = dateDir.name
        selectedContent = SentenceTimestampStore.FolderEntry(dateDir.name, contentDir.name, contentDir)
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

    private fun setOutputText(text: String) {
        outputTextView?.text = text
        outputScrollView?.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
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

    private fun openYoutubeTargetInChrome(target: String) {
        val uri = Uri.parse(target)
        val chromeIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.android.chrome")
            putExtra(Browser.EXTRA_APPLICATION_ID, packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val canOpen = chromeIntent.resolveActivity(packageManager) != null
        if (canOpen) {
            startActivity(chromeIntent)
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
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

    private fun fmtSec(sec: Double): String {
        val totalTenths = (sec.coerceAtLeast(0.0) * 10.0).roundToInt()
        val totalSec = totalTenths / 10
        val tenths = totalTenths % 10
        val min = totalSec / 60
        val s = totalSec % 60
        return String.format("%02d:%02d.%d", min, s, tenths)
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
        private const val FONT_SCALE = 0.8f
        private const val BUTTON_TEXT_SP = 11f
        private const val CHANNEL_ID = "project3_overlay_channel"
        private const val NOTIFICATION_ID = 3101
        private const val PREFS_NAME = "project3_main"
        private const val KEY_API_KEY = "openai_api_key"
        private const val KEY_SELECTED_URL = "selected_url"
        private const val KEY_LAST_URL = "last_url"
    }
}
