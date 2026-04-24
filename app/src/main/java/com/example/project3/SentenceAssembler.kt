package com.example.project3

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SentenceAssembler {
    fun build(result: WhisperVerboseResult, requestedStartSec: Double, requestedEndSec: Double): List<SentenceTimestamp> {
        val allWords = result.words.orEmpty().filter { it.end - it.start > MIN_VALID_WORD_SEC }
        val allSegments = result.segments.orEmpty()
        val splitStart = requestedStartSec - SPLIT_WINDOW_PAD_SEC
        val splitEnd = requestedEndSec + SPLIT_WINDOW_PAD_SEC
        val fromWords = allWords.filter { overlapsWindow(it.start, it.end, splitStart, splitEnd) }
        val segments = allSegments.filter { overlapsWindow(it.start, it.end, requestedStartSec - 1.0, requestedEndSec + 1.0) }
        val sentences = if (fromWords.isNotEmpty()) splitWords(fromWords, segments) else splitSegments(segments)
        val clipped = sentences.mapNotNull { keepIfRelevant(it, requestedStartSec, requestedEndSec, allWords) }
        val normalized = if (fromWords.isNotEmpty() && allSegments.isNotEmpty()) {
            reinforceBoundaryPunctuation(clipped, allSegments)
        } else {
            clipped
        }
        if (normalized.isNotEmpty()) return normalized

        // Fallback: keep non-zero overlap sentences to avoid empty output
        // when clipping removes all boundary candidates.
        return sentences.mapNotNull { sentence ->
            val clippedStart = max(requestedStartSec, sentence.startSec)
            val clippedEnd = min(requestedEndSec, sentence.endSec)
            if (clippedEnd - clippedStart <= 0.0) return@mapNotNull null
            val text = normalizeText(sentence.text)
            if (text.isBlank()) null else sentence.copy(startSec = clippedStart, endSec = clippedEnd, text = text)
        }
    }

    private fun splitWords(words: List<WhisperWord>, segments: List<WhisperSegment>): List<SentenceTimestamp> {
        if (words.isEmpty()) return emptyList()
        val out = mutableListOf<SentenceTimestamp>()
        val current = mutableListOf<WhisperWord>()
        val boundaries = buildSegmentBoundaries(segments)
        val splitReasons = mutableMapOf<String, Int>()
        val recentGaps = ArrayDeque<Double>()

        for (word in words) {
            if (current.isNotEmpty()) {
                val prev = current.last()
                val decision = shouldSplit(current, prev, word, boundaries, gapStats(recentGaps))
                if (decision.shouldSplit && decision.reason == "hard_length") {
                    val bestCut = findBestInternalCutIndex(current, boundaries, gapStats(recentGaps))
                    if (bestCut != null) {
                        emitWords(current.take(bestCut + 1))?.let(out::add)
                        splitReasons["hard_length_best_cut"] = (splitReasons["hard_length_best_cut"] ?: 0) + 1
                        val tail = current.drop(bestCut + 1)
                        current.clear()
                        current.addAll(tail)
                    } else {
                        emitWords(current)?.let(out::add)
                        current.clear()
                        splitReasons["hard_length"] = (splitReasons["hard_length"] ?: 0) + 1
                    }
                } else if (decision.shouldSplit) {
                    if (decision.reason == "score_split") {
                        Log.d(TAG, "scoreSplit score=${"%.2f".format(decision.score)} evidence=${decision.evidence}")
                    }
                    splitReasons[decision.reason] = (splitReasons[decision.reason] ?: 0) + 1
                    emitWords(current)?.let(out::add)
                    current.clear()
                }
                val gap = (word.start - prev.end).coerceAtLeast(0.0)
                recentGaps.addLast(gap)
                if (recentGaps.size > 14) recentGaps.removeFirst()
            }
            current.add(word)
        }
        emitWords(current)?.let(out::add)
        if (splitReasons.isNotEmpty()) {
            Log.d(TAG, "splitWords reasons=${splitReasons.entries.joinToString(",") { "${it.key}:${it.value}" }}")
        }
        return mergeSmallFragments(out)
    }

    private fun splitSegments(segments: List<WhisperSegment>): List<SentenceTimestamp> {
        val raw = segments.mapNotNull { seg ->
            val text = normalizeText(seg.text)
            if (text.isBlank()) null else SentenceTimestamp(seg.start, seg.end, text)
        }
        if (raw.size <= 1) return raw

        val merged = mutableListOf<SentenceTimestamp>()
        var current = raw.first()
        for (i in 1 until raw.size) {
            val next = raw[i]
            val gap = next.startSec - current.endSec
            val curDuration = current.endSec - current.startSec
            val shouldMerge = !endsSentence(current.text) && curDuration < 1.4 && gap <= 0.5
            if (shouldMerge) {
                current = SentenceTimestamp(
                    startSec = current.startSec,
                    endSec = next.endSec,
                    text = normalizeText("${current.text} ${next.text}")
                )
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return mergeSmallFragments(merged)
    }

    private fun emitWords(words: List<WhisperWord>): SentenceTimestamp? {
        if (words.isEmpty()) return null
        val text = normalizeText(rebuildTextFromWords(words))
        if (text.isBlank()) return null
        return SentenceTimestamp(words.first().start, words.last().end, text)
    }

    private fun rebuildTextFromWords(words: List<WhisperWord>): String {
        val out = StringBuilder()
        words.forEach { token ->
            val raw = token.word
            val piece = raw.trim()
            if (piece.isBlank()) return@forEach
            if (out.isEmpty()) {
                out.append(piece)
                return@forEach
            }
            val noLeadingSpace = piece.firstOrNull()?.let { isPunctuation(it) } == true || startsWithApostropheToken(piece)
            if (!noLeadingSpace) out.append(' ')
            out.append(piece)
        }
        return out.toString()
    }

    private fun shouldSplit(
        current: List<WhisperWord>,
        prev: WhisperWord,
        next: WhisperWord,
        boundaries: List<Double>,
        stats: GapStats
    ): SplitDecision {
        val gap = next.start - prev.end
        val span = current.last().end - current.first().start
        val hardPunctuation = endsSentence(prev.word) && current.size >= 2
        if (hardPunctuation) return SplitDecision(true, "hard_punctuation")
        val longSilence = gap >= 1.1
        if (longSilence) return SplitDecision(true, "hard_silence")
        val tooLongSentence = current.size >= HARD_MAX_WORDS || span >= HARD_MAX_SPAN_SEC
        if (tooLongSentence) return SplitDecision(true, "hard_length")

        val nearSegmentBoundary = boundaries.any { it in (prev.end - 0.12)..(next.start + 0.12) }
        val mediumGap = gap >= 0.4
        val highGap = gap >= 0.65
        val weakPunctuation = endsWeakBoundary(prev.word)
        val gapZ = stats.zScore(gap)

        val evidence = mutableListOf<String>()
        var score = 0.0
        if (mediumGap) {
            score += 1.1
            evidence += "gap_medium"
        }
        if (highGap) {
            score += 1.0
            evidence += "gap_high"
        }
        if (weakPunctuation) {
            score += 1.1
            evidence += "weak_punctuation"
        }
        if (nearSegmentBoundary) {
            score += 0.7
            evidence += "segment_boundary"
        }
        if (gapZ >= 1.2) {
            score += 0.8
            evidence += "gap_z>=1.2"
        }
        if (gapZ >= 2.0) {
            score += 0.6
            evidence += "gap_z>=2.0"
        }

        val startSignal = startsStrongSentenceWord(next.word) || startsConnectorWord(next.word)
        val subordinateStart = startsSubordinateWord(next.word)
        val discourseStarter = startsDiscourseStarterWord(next.word)
        val hasAnchor = mediumGap || weakPunctuation || nearSegmentBoundary || gapZ >= 1.2
        if (startSignal && hasAnchor) {
            score += 0.8
            evidence += "start_signal_supported"
        } else if (startSignal) {
            evidence += "start_signal_ignored"
        }
        if (discourseStarter && hasAnchor) {
            score += 0.9
            evidence += "discourse_starter_boost"
        }
        if (subordinateStart) {
            // Damp splits for clause continuations like "by shortening ...".
            // Keep this as score shaping, not a hard block.
            score -= 0.6
            evidence += "subordinate_dampen"
        }

        val anchorCount = listOf(mediumGap, weakPunctuation, nearSegmentBoundary, gapZ >= 1.2).count { it }
        val strongAnchor = highGap || weakPunctuation || gapZ >= 2.0
        val anchoredEnough = strongAnchor || anchorCount >= 2
        val connectorStart = startsConnectorWord(next.word)
        val connectorBoostPath = connectorStart &&
            (mediumGap || gapZ >= 1.0 || nearSegmentBoundary) &&
            score >= CONNECTOR_SPLIT_THRESHOLD
        val safeSoftSplit = (score >= SOFT_SPLIT_THRESHOLD || connectorBoostPath) &&
            hasAnchor &&
            (anchoredEnough || connectorBoostPath) &&
            current.size >= 3 &&
            // Prevent clause-level over-split like "he | couldn't" without stronger anchor.
            (weakPunctuation || highGap || (startSignal && (mediumGap || gapZ >= 1.2)) || connectorBoostPath)
        val shouldSplit = safeSoftSplit
        return if (shouldSplit) {
            SplitDecision(true, "score_split", score, evidence.joinToString("+"))
        } else {
            SplitDecision(false, "none", score, evidence.joinToString("+"))
        }
    }

    private fun mergeSmallFragments(items: List<SentenceTimestamp>): List<SentenceTimestamp> {
        if (items.size <= 1) return items
        val merged = mutableListOf<SentenceTimestamp>()
        var i = 0
        while (i < items.size) {
            val cur = items[i]
            val dur = cur.endSec - cur.startSec
            val isTiny = cur.text.length < 14 && dur < 0.85
            if (isTiny && i + 1 < items.size && !endsSentence(cur.text)) {
                val next = items[i + 1]
                val gap = next.startSec - cur.endSec
                val mergedDuration = next.endSec - cur.startSec
                val canJoin = gap <= 0.32 && !startsStrongSentence(next.text) && mergedDuration <= 8.5
                if (!canJoin) {
                    merged += cur
                    i += 1
                    continue
                }
                val joinedText = normalizeText("${cur.text} ${next.text}")
                merged += SentenceTimestamp(
                    startSec = cur.startSec,
                    endSec = next.endSec,
                    text = joinedText
                )
                i += 2
                continue
            }
            merged += cur
            i += 1
        }
        return merged
    }

    private fun reinforceBoundaryPunctuation(
        items: List<SentenceTimestamp>,
        segments: List<WhisperSegment>
    ): List<SentenceTimestamp> {
        if (items.size <= 1 || segments.isEmpty()) return items
        val out = items.toMutableList()
        for (i in 0 until out.lastIndex) {
            val cur = out[i]
            if (endsSentence(cur.text)) continue
            val punct = segments.asSequence()
                .filter { abs(it.end - cur.endSec) <= 0.35 }
                .mapNotNull { extractTerminalPunctuation(it.text) }
                .firstOrNull() ?: continue
            out[i] = cur.copy(text = normalizeText(cur.text) + punct)
        }
        return out
    }

    private fun keepIfRelevant(
        sentence: SentenceTimestamp,
        requestedStartSec: Double,
        requestedEndSec: Double,
        allWords: List<WhisperWord>
    ): SentenceTimestamp? {
        if (sentence.endSec <= requestedStartSec - CLIP_EPSILON_SEC || sentence.startSec >= requestedEndSec + CLIP_EPSILON_SEC) {
            return null
        }
        val clippedStart = max(requestedStartSec, sentence.startSec)
        val clippedEnd = min(requestedEndSec, sentence.endSec)
        val clippedDuration = clippedEnd - clippedStart
        if (clippedDuration <= MIN_VALID_SENTENCE_SEC) {
            return null
        }
        val clippedText = clipSentenceTextByWords(
            sentence = sentence,
            clippedStart = clippedStart,
            clippedEnd = clippedEnd,
            allWords = allWords
        )
        if (clippedText.isBlank()) return null
        return sentence.copy(
            startSec = clippedStart,
            endSec = clippedEnd,
            text = clippedText
        )
    }

    private fun clipSentenceTextByWords(
        sentence: SentenceTimestamp,
        clippedStart: Double,
        clippedEnd: Double,
        allWords: List<WhisperWord>
    ): String {
        if (allWords.isEmpty()) return sentence.text
        val sentenceStart = sentence.startSec - WORD_MATCH_EPSILON_SEC
        val sentenceEnd = sentence.endSec + WORD_MATCH_EPSILON_SEC
        val clipStart = clippedStart - WORD_MATCH_EPSILON_SEC
        val clipEnd = clippedEnd + WORD_MATCH_EPSILON_SEC
        val selected = allWords.filter { word ->
            overlapsWindow(word.start, word.end, sentenceStart, sentenceEnd) &&
                overlapsWindow(word.start, word.end, clipStart, clipEnd)
        }
        val selectedOrNearest = if (selected.isNotEmpty()) {
            selected
        } else {
            val nearest = nearestWordWithinSentenceWindow(allWords, sentence, clippedStart, clippedEnd)
            if (nearest != null) {
                Log.d(TAG, "clipFallback nearest-word used at ${"%.3f".format(nearest.start)}-${"%.3f".format(nearest.end)}")
                listOf(nearest)
            } else {
                emptyList()
            }
        }
        if (selectedOrNearest.isEmpty()) {
            Log.d(
                TAG,
                "clipFallback safeShrink used sentence=${"%.2f".format(sentence.startSec)}-${"%.2f".format(sentence.endSec)} " +
                    "clip=${"%.2f".format(clippedStart)}-${"%.2f".format(clippedEnd)}"
            )
            return safeShrinkTextFallback(sentence, clippedStart, clippedEnd)
        }
        val rebuilt = normalizeText(rebuildTextFromWords(selectedOrNearest))
        return if (rebuilt.isBlank()) safeShrinkTextFallback(sentence, clippedStart, clippedEnd) else rebuilt
    }

    private fun normalizeText(value: String): String {
        return value.replace("\\s+".toRegex(), " ").trim()
    }

    private fun endsSentence(word: String): Boolean {
        return word.trimEnd().endsWith(".")
            || word.trimEnd().endsWith("?")
            || word.trimEnd().endsWith("!")
    }

    private fun startsStrongSentence(text: String): Boolean {
        val normalized = text.trimStart()
        if (normalized.isBlank()) return false
        return normalized.startsWith("\"")
            || normalized.startsWith("'")
            || normalized.first().isUpperCase()
    }

    private fun isPunctuation(ch: Char): Boolean {
        return ch == '.' || ch == ',' || ch == '!' || ch == '?' || ch == ':' || ch == ';' || ch == ')'
    }

    private fun startsWithApostropheToken(piece: String): Boolean {
        return piece.startsWith("'") || piece.startsWith("’")
    }

    private fun startsStrongSentenceWord(word: String): Boolean {
        val normalized = word.trimStart().trimStart('"', '\'', '“', '”', '‘', '’', '(', '[')
        val first = normalized.firstOrNull() ?: return false
        return first.isUpperCase()
    }

    private fun startsConnectorWord(word: String): Boolean {
        val normalized = word.trim()
            .trimStart('"', '\'', '“', '”', '‘', '’', '(', '[')
            .trimEnd(',', ';', ':', ')', ']', '"', '\'')
            .lowercase()
        return normalized in CONNECTOR_WORDS
    }

    private fun startsSubordinateWord(word: String): Boolean {
        val normalized = word.trim()
            .trimStart('"', '\'', '“', '”', '‘', '’', '(', '[')
            .trimEnd(',', ';', ':', ')', ']', '"', '\'')
            .lowercase()
        return normalized in SUBORDINATE_WORDS
    }

    private fun startsDiscourseStarterWord(word: String): Boolean {
        val normalized = word.trim()
            .trimStart('"', '\'', '“', '”', '‘', '’', '(', '[')
            .trimEnd(',', ';', ':', ')', ']', '"', '\'')
            .lowercase()
        return normalized in DISCOURSE_STARTERS
    }

    private fun endsWeakBoundary(word: String): Boolean {
        val trimmed = word.trimEnd()
        return trimmed.endsWith(",") || trimmed.endsWith(";") || trimmed.endsWith(":")
    }

    private fun buildSegmentBoundaries(segments: List<WhisperSegment>): List<Double> {
        if (segments.isEmpty()) return emptyList()
        return segments.asSequence()
            .filter { endsSentence(it.text) }
            .map { it.end }
            .toList()
    }

    private fun extractTerminalPunctuation(text: String): String? {
        val ch = text.trimEnd().lastOrNull() ?: return null
        return when (ch) {
            '.', '?', '!' -> ch.toString()
            else -> null
        }
    }

    private fun gapStats(gaps: Collection<Double>): GapStats {
        if (gaps.size < 4) return GapStats(0.0, 0.0)
        val mean = gaps.average()
        val variance = gaps.map { d ->
            val delta = d - mean
            delta * delta
        }.average()
        return GapStats(mean, kotlin.math.sqrt(variance))
    }

    private fun overlapsWindow(start: Double, end: Double, windowStart: Double, windowEnd: Double): Boolean {
        return end > windowStart && start < windowEnd
    }

    private fun safeShrinkTextFallback(
        sentence: SentenceTimestamp,
        clippedStart: Double,
        clippedEnd: Double
    ): String {
        val original = normalizeText(sentence.text)
        if (original.isBlank()) return original
        val tokens = original.split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return original
        val total = (sentence.endSec - sentence.startSec).coerceAtLeast(0.2)
        val kept = (clippedEnd - clippedStart).coerceAtLeast(0.0)
        val ratio = (kept / total).coerceIn(0.0, 1.0)
        val keepCount = (tokens.size * ratio).toInt().coerceAtLeast(1).coerceAtMost(tokens.size)
        val clipCenter = ((clippedStart + clippedEnd) / 2.0 - sentence.startSec) / total
        return when {
            clipCenter >= 0.66 -> tokens.takeLast(keepCount).joinToString(" ")
            clipCenter <= 0.34 -> tokens.take(keepCount).joinToString(" ")
            else -> {
                val startIdx = ((tokens.size - keepCount) / 2).coerceAtLeast(0)
                tokens.drop(startIdx).take(keepCount).joinToString(" ")
            }
        }
    }

    private fun nearestWordWithinSentenceWindow(
        allWords: List<WhisperWord>,
        sentence: SentenceTimestamp,
        clippedStart: Double,
        clippedEnd: Double
    ): WhisperWord? {
        val clipDur = (clippedEnd - clippedStart).coerceAtLeast(0.0)
        if (clipDur > SHORT_CLIP_SEC) return null
        val sentenceWindowStart = sentence.startSec - WORD_MATCH_EPSILON_SEC
        val sentenceWindowEnd = sentence.endSec + WORD_MATCH_EPSILON_SEC
        val center = (clippedStart + clippedEnd) / 2.0
        return allWords
            .asSequence()
            .filter { overlapsWindow(it.start, it.end, sentenceWindowStart, sentenceWindowEnd) }
            .minByOrNull { kotlin.math.abs(((it.start + it.end) / 2.0) - center) }
    }

    private fun findBestInternalCutIndex(
        words: List<WhisperWord>,
        boundaries: List<Double>,
        stats: GapStats
    ): Int? {
        if (words.size < 6) return null
        var bestIndex: Int? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in 1 until words.lastIndex) {
            val prev = words[i]
            val next = words[i + 1]
            val gap = (next.start - prev.end).coerceAtLeast(0.0)
            val nearSegmentBoundary = boundaries.any { it in (prev.end - 0.12)..(next.start + 0.12) }
            val gapZ = stats.zScore(gap)
            var score = 0.0
            if (endsSentence(prev.word)) score += 4.0
            if (endsWeakBoundary(prev.word)) score += 1.0
            if (nearSegmentBoundary) score += 0.6
            score += (gap * 2.0).coerceAtMost(2.2)
            if (gapZ >= 1.2) score += 0.7
            score += connectorStrength(next.word)
            if (startsSubordinateWord(next.word)) score -= 0.9
            if (score > bestScore) {
                bestScore = score
                bestIndex = i
            }
        }
        return bestIndex
    }

    private fun connectorStrength(word: String): Double {
        val normalized = word.trim()
            .trimStart('"', '\'', '“', '”', '‘', '’', '(', '[')
            .trimEnd(',', ';', ':', ')', ']', '"', '\'')
            .lowercase()
        return when (normalized) {
            "but", "so", "however", "therefore" -> 0.9
            "then", "yet", "still", "meanwhile", "anyway", "instead" -> 0.6
            "and", "also" -> 0.25
            else -> 0.0
        }
    }

    companion object {
        private const val TAG = "SentenceAssembler"
        private const val MIN_VALID_WORD_SEC = 0.01
        private const val SOFT_SPLIT_THRESHOLD = 2.8
        private const val CONNECTOR_SPLIT_THRESHOLD = 2.0
        private const val SPLIT_WINDOW_PAD_SEC = 1.8
        private const val CLIP_EPSILON_SEC = 0.10
        private const val MIN_VALID_SENTENCE_SEC = 0.05
        private const val WORD_MATCH_EPSILON_SEC = 0.15
        private const val SHORT_CLIP_SEC = 0.8
        private const val HARD_MAX_WORDS = 52
        private const val HARD_MAX_SPAN_SEC = 13.0
        private val CONNECTOR_WORDS = setOf(
            "and",
            "but",
            "so",
            "however",
            "already",
            "therefore",
            "then",
            "because",
            "meanwhile",
            "anyway",
            "also",
            "instead",
            "still",
            "yet"
        )
        private val DISCOURSE_STARTERS = setOf(
            "yes",
            "again",
            "no",
            "that's",
            "thats"
        )
        private val SUBORDINATE_WORDS = setOf(
            "by",
            "because",
            "while",
            "if",
            "when",
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

private data class SplitDecision(
    val shouldSplit: Boolean,
    val reason: String,
    val score: Double = 0.0,
    val evidence: String = ""
)

private data class GapStats(
    val mean: Double,
    val stddev: Double
) {
    fun zScore(value: Double): Double {
        if (stddev < 0.06) return 0.0
        return (value - mean) / stddev
    }
}
