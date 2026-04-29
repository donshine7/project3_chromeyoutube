package com.example.project3

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SentenceAssembler {
    fun build(
        result: WhisperVerboseResult,
        requestedStartSec: Double,
        requestedEndSec: Double,
        chunkBoundaryHints: List<Double> = emptyList()
    ): List<SentenceTimestamp> {
        val allWords = result.words.orEmpty().filter { isUsableWord(it) }
        val allSegments = result.segments.orEmpty()
        val splitStart = requestedStartSec - SPLIT_WINDOW_PAD_SEC
        val splitEnd = requestedEndSec + SPLIT_WINDOW_PAD_SEC
        val fromWords = allWords.filter { overlapsWindow(it.start, it.end, splitStart, splitEnd) }
        val segments = allSegments.filter { overlapsWindow(it.start, it.end, requestedStartSec - 1.0, requestedEndSec + 1.0) }
        val sentences = if (fromWords.isNotEmpty()) {
            splitWords(fromWords, segments, chunkBoundaryHints)
        } else {
            splitSegments(segments)
        }
        val clipped = sentences.mapNotNull { keepIfRelevant(it, requestedStartSec, requestedEndSec, allWords) }
        val punctuationBoundaries = if (allWords.isNotEmpty() && allSegments.isNotEmpty()) {
            buildPunctuationBoundaries(allSegments, allWords)
        } else {
            emptyList()
        }
        val normalized = if (fromWords.isNotEmpty() && allSegments.isNotEmpty()) {
            reinforceBoundaryPunctuation(clipped, allSegments, punctuationBoundaries)
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

    private fun splitWords(
        words: List<WhisperWord>,
        segments: List<WhisperSegment>,
        chunkBoundaryHints: List<Double>
    ): List<SentenceTimestamp> {
        if (words.isEmpty()) return emptyList()
        val out = mutableListOf<SentenceTimestamp>()
        val current = mutableListOf<WhisperWord>()
        val boundaries = buildSegmentBoundaries(segments)
        val punctuationBoundaries = buildPunctuationBoundaries(segments, words)
        val segmentStarts = segments.map { it.start }
        val splitReasons = mutableMapOf<String, Int>()
        val recentGaps = ArrayDeque<Double>()

        for ((index, word) in words.withIndex()) {
            if (current.isNotEmpty()) {
                val prev = current.last()
                val lookaheadWords = words.subList(index, min(index + 4, words.size))
                val decision = shouldSplit(
                    current = current,
                    prev = prev,
                    next = word,
                    boundaries = boundaries,
                    segmentStarts = segmentStarts,
                    stats = gapStats(recentGaps),
                    lookaheadWords = lookaheadWords,
                    punctuationBoundaries = punctuationBoundaries,
                    chunkBoundaryHints = chunkBoundaryHints
                )
                if (decision.shouldSplit && decision.reason.startsWith("hard_length")) {
                    val allowLowQualityCut = decision.reason == "hard_length_emergency"
                    val bestStarterCut = if (!allowLowQualityCut) {
                        findStarterPreferredCutIndex(current, boundaries)
                    } else {
                        null
                    }
                    val bestCut = bestStarterCut ?: findBestInternalCutIndex(
                        words = current,
                        boundaries = boundaries,
                        stats = gapStats(recentGaps),
                        allowLowQuality = allowLowQualityCut
                    )
                    if (bestCut != null) {
                        emitWords(current.take(bestCut + 1))?.let(out::add)
                        val key = when {
                            bestStarterCut != null -> "hard_length_starter_preferred_cut"
                            allowLowQualityCut -> "hard_length_emergency_best_cut"
                            else -> "hard_length_soft_best_cut"
                        }
                        splitReasons[key] = (splitReasons[key] ?: 0) + 1
                        val tail = current.drop(bestCut + 1)
                        current.clear()
                        current.addAll(tail)
                    } else {
                        if (allowLowQualityCut) {
                            emitWords(current)?.let(out::add)
                            current.clear()
                            splitReasons["hard_length_emergency_flush"] = (splitReasons["hard_length_emergency_flush"] ?: 0) + 1
                        }
                    }
                } else if (decision.shouldSplit) {
                    if (decision.reason == "score_split") {
                        Log.d(
                            TAG,
                            "scoreSplit t=${"%.1f".format(word.start)} prev=${prev.word} next=${word.word} " +
                                "gap=${"%.2f".format((word.start - prev.end).coerceAtLeast(0.0))} " +
                                "span=${"%.1f".format(prev.end - current.first().start)} " +
                                "score=${"%.2f".format(decision.score)} evidence=${decision.evidence}"
                        )
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
        return postProcessWordSentences(mergeSmallFragments(out))
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

    private fun isUsableWord(word: WhisperWord): Boolean {
        if (word.word.isBlank()) return false
        return word.end + WORD_TIME_EPSILON_SEC >= word.start
    }

    private fun shouldSplit(
        current: List<WhisperWord>,
        prev: WhisperWord,
        next: WhisperWord,
        boundaries: List<Double>,
        segmentStarts: List<Double>,
        stats: GapStats,
        lookaheadWords: List<WhisperWord>,
        punctuationBoundaries: List<PunctuationBoundary>,
        chunkBoundaryHints: List<Double>
    ): SplitDecision {
        val gap = next.start - prev.end
        val span = current.last().end - current.first().start
        if (shouldAlwaysProtectBoundary(prev.word, next.word)) {
            return SplitDecision(false, "protected_boundary_strong")
        }
        if (shouldProtectBoundary(prev.word, next.word) && gap <= PROTECTED_BOUNDARY_GAP_SEC) {
            return SplitDecision(false, "protected_boundary")
        }
        val hardPunctuation = endsSentence(prev.word) && current.size >= 2
        if (hardPunctuation) return SplitDecision(true, "hard_punctuation")
        val continuationAfterGap = startsLowercaseContinuation(next.word) || startsSubordinateWord(next.word)
        val hardSilenceThreshold = if (continuationAfterGap) HARD_SILENCE_CONTINUATION_SEC else HARD_SILENCE_SEC
        val longSilence = gap >= hardSilenceThreshold
        if (longSilence) return SplitDecision(true, "hard_silence")
        val emergencyTooLong = current.size >= HARD_MAX_WORDS_EMERGENCY || span >= HARD_MAX_SPAN_SEC_EMERGENCY
        if (emergencyTooLong) return SplitDecision(true, "hard_length_emergency")
        val softTooLong = current.size >= HARD_MAX_WORDS_SOFT || span >= HARD_MAX_SPAN_SEC_SOFT

        val nearSegmentBoundary = boundaries.any { it in (prev.end - 0.12)..(next.start + 0.12) }
        val nearSegmentStart = segmentStarts.any { kotlin.math.abs(it - next.start) <= 0.25 }
        val nearPunctuationBoundary = punctuationBoundaries.any { kotlin.math.abs(it.startSec - next.start) <= PUNCTUATION_BOUNDARY_MATCH_SEC }
        val nearChunkBoundary = chunkBoundaryHints.any { kotlin.math.abs(it - next.start) <= 0.8 }
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
        if (nearPunctuationBoundary) {
            score += 2.2
            evidence += "segment_punctuation_boundary"
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
        val dialogueStarter = startsDialogueStarterWord(next.word)
        val lowercaseSentenceStarter = startsLowercaseSentenceStarterWord(next.word)
        val phraseSentenceStarter = startsPhraseSentenceStarter(lookaheadWords)
        val subjectSentenceStarter = startsSubjectSentenceStarter(lookaheadWords)
        val capitalizedSegmentStarter = startsCapitalizedSegmentStarter(
            prevWord = prev.word,
            nextWord = next.word,
            gap = gap,
            nearSegmentStart = nearSegmentStart,
            nearSegmentBoundary = nearSegmentBoundary,
            nearPunctuationBoundary = nearPunctuationBoundary,
            weakPunctuation = weakPunctuation,
            span = span
        )
        val prepositionSentenceStarter = startsPrepositionSentenceStarterWord(
            nextWord = next.word,
            gap = gap,
            nearSegmentBoundary = nearSegmentBoundary,
            span = span
        )
        val hasAnchor = mediumGap || weakPunctuation || nearSegmentBoundary || nearPunctuationBoundary || gapZ >= 1.2
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
        val starterAnchor = gap >= STARTER_ANCHOR_GAP_SEC || weakPunctuation || nearSegmentBoundary || nearPunctuationBoundary || gapZ >= STARTER_ANCHOR_Z
        val looseStarterAnchor = starterAnchor || nearSegmentStart
        val longEnoughForPhrase = span >= PHRASE_STARTER_MIN_SPAN_SEC
        if (dialogueStarter && (starterAnchor || gap >= 0.25 || current.size >= 10 || span >= 7.0)) {
            score += 1.3
            evidence += "dialogue_starter_boost"
        }
        if (lowercaseSentenceStarter && looseStarterAnchor) {
            score += 1.2
            evidence += "lowercase_sentence_starter"
        }
        if (prepositionSentenceStarter) {
            score += 0.9
            evidence += "preposition_starter"
        }
        if (phraseSentenceStarter) {
            score += 1.4
            evidence += "phrase_sentence_starter"
        }
        if (subjectSentenceStarter && looseStarterAnchor) {
            score += 1.1
            evidence += "subject_sentence_starter"
        }
        if (capitalizedSegmentStarter) {
            score += 1.1
            evidence += "capitalized_segment_starter"
        }
        if (subordinateStart) {
            // Damp splits for clause continuations like "by shortening ...".
            // Keep this as score shaping, not a hard block.
            score -= 0.6
            evidence += "subordinate_dampen"
        }

        val anchorCount = listOf(mediumGap, weakPunctuation, nearSegmentBoundary, nearPunctuationBoundary, gapZ >= 1.2).count { it }
        val strongAnchor = highGap || weakPunctuation || nearPunctuationBoundary || gapZ >= 2.0
        val anchoredEnough = strongAnchor || anchorCount >= 2
        val connectorStart = startsConnectorWord(next.word)
        val connectorBoostPath = connectorStart &&
            (mediumGap || gapZ >= 1.0 || nearSegmentBoundary || nearPunctuationBoundary) &&
            score >= CONNECTOR_SPLIT_THRESHOLD
        val dialogueStarterPath = dialogueStarter &&
            (starterAnchor || gap >= 0.25 || current.size >= 10 || span >= 7.0) &&
            score >= STARTER_DIRECT_MIN_SCORE
        val lowercaseStarterPath = lowercaseSentenceStarter &&
            looseStarterAnchor &&
            score >= STARTER_DIRECT_MIN_SCORE
        val prepositionStarterPath = prepositionSentenceStarter &&
            (starterAnchor || span >= PREPOSITION_STARTER_MIN_SPAN_SEC)
        val phraseStarterPath = phraseSentenceStarter && (starterAnchor || longEnoughForPhrase)
        val subjectStarterPath = subjectSentenceStarter &&
            looseStarterAnchor &&
            score >= SUBJECT_STARTER_DIRECT_MIN_SCORE
        val capitalizedStarterPath = capitalizedSegmentStarter &&
            (starterAnchor || span >= CAPITALIZED_STARTER_MIN_SPAN_SEC) &&
            score >= CAPITALIZED_STARTER_DIRECT_MIN_SCORE
        val strongStarterPath =
            phraseStarterPath ||
                dialogueStarterPath ||
                lowercaseStarterPath ||
                prepositionStarterPath ||
                subjectStarterPath ||
                capitalizedStarterPath
        if (nearChunkBoundary && !strongStarterPath && !endsSentence(prev.word)) {
            score -= 1.2
            evidence += "near_chunk_boundary_dampen"
        }
        val safeSoftSplit = (score >= SOFT_SPLIT_THRESHOLD || connectorBoostPath || strongStarterPath) &&
            (hasAnchor || strongStarterPath) &&
            (anchoredEnough || connectorBoostPath || strongStarterPath) &&
            current.size >= 3 &&
            // Prevent clause-level over-split like "he | couldn't" without stronger anchor.
            (
                weakPunctuation ||
                    highGap ||
                    nearPunctuationBoundary ||
                    (startSignal && (mediumGap || gapZ >= 1.2)) ||
                    connectorBoostPath ||
                    strongStarterPath
                )
        val shouldSplit = safeSoftSplit
        return if (shouldSplit) {
            SplitDecision(true, "score_split", score, evidence.joinToString("+"))
        } else if (softTooLong) {
            SplitDecision(true, "hard_length_soft", score, evidence.joinToString("+"))
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
        segments: List<WhisperSegment>,
        punctuationBoundaries: List<PunctuationBoundary> = emptyList()
    ): List<SentenceTimestamp> {
        if (items.isEmpty() || segments.isEmpty()) return items
        val out = reinforceInlineSegmentPunctuation(items, segments).toMutableList()
        for (i in out.indices) {
            val cur = out[i]
            if (endsSentence(cur.text)) continue
            val punct = segments.asSequence()
                .filter { abs(it.end - cur.endSec) <= 0.35 }
                .mapNotNull { extractTerminalPunctuation(it.text) }
                .firstOrNull()
                ?: out.getOrNull(i + 1)?.startSec?.let { nextStart ->
                    punctuationBoundaries.asSequence()
                        .filter { abs(it.startSec - nextStart) <= PUNCTUATION_BOUNDARY_MATCH_SEC }
                        .filter { it.startSec >= cur.endSec - 0.15 }
                        .filter { it.startSec - cur.endSec <= INTERNAL_PUNCTUATION_MAX_GAP_SEC }
                        .map { it.punctuation }
                        .firstOrNull()
                }
                ?: continue
            out[i] = cur.copy(text = appendTerminalPunctuation(cur.text, punct))
        }
        return out
    }

    private fun reinforceInlineSegmentPunctuation(
        items: List<SentenceTimestamp>,
        segments: List<WhisperSegment>
    ): List<SentenceTimestamp> {
        return items.map { item ->
            val sourceText = segments.asSequence()
                .filter { overlapsWindow(it.start, it.end, item.startSec - 0.15, item.endSec + 0.15) }
                .joinToString(" ") { it.text }
            if (sourceText.isBlank()) {
                item
            } else {
                item.copy(text = applyInlinePunctuationFromSource(item.text, sourceText))
            }
        }
    }

    private fun applyInlinePunctuationFromSource(text: String, sourceText: String): String {
        val targetTokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        if (targetTokens.isEmpty()) return text
        val sourceTokens = sourceText.split(Regex("\\s+"))
            .map { raw -> normalizeAlignmentToken(raw) to extractInlinePunctuation(raw) }
            .filter { it.first.isNotBlank() }
        var cursor = 0
        for ((sourceToken, punctuation) in sourceTokens) {
            if (punctuation == null) continue
            var matchIndex = -1
            var i = cursor
            while (i < targetTokens.size) {
                if (normalizeAlignmentToken(targetTokens[i]) == sourceToken) {
                    matchIndex = i
                    break
                }
                i += 1
            }
            if (matchIndex >= 0) {
                val weakPunctuationAtTail = punctuation in WEAK_INLINE_PUNCTUATION && matchIndex == targetTokens.lastIndex
                if (!weakPunctuationAtTail) {
                    targetTokens[matchIndex] = appendInlinePunctuation(targetTokens[matchIndex], punctuation)
                }
                cursor = matchIndex + 1
            }
        }
        return restoreCommonSourcePunctuation(normalizeText(targetTokens.joinToString(" ")), sourceText)
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
        val clipStart = clippedStart - STRICT_CLIP_EPSILON_SEC
        val clipEnd = clippedEnd + STRICT_CLIP_EPSILON_SEC
        val selected = allWords.filter { word ->
            val midpoint = (word.start + word.end) / 2.0
            overlapsWindow(word.start, word.end, sentenceStart, sentenceEnd) &&
                midpoint in clipStart..clipEnd
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

    private fun startsDialogueStarterWord(word: String): Boolean {
        val normalized = word.trim()
            .trimStart('"', '\'', '“', '”', '‘', '’', '(', '[')
            .trimEnd(',', ';', ':', ')', ']', '"', '\'')
            .lowercase()
        return normalized in DIALOGUE_STARTERS
    }

    private fun startsLowercaseSentenceStarterWord(word: String): Boolean {
        val normalized = word.trim()
            .trimStart('"', '\'', '“', '”', '‘', '’', '(', '[')
            .trimEnd(',', ';', ':', ')', ']', '"', '\'')
            .lowercase()
        return normalized in LOWERCASE_SENTENCE_STARTERS
    }

    private fun startsLowercaseSentenceStarterText(text: String): Boolean {
        val normalized = text.trimStart()
            .trimStart('"', '\'', '“', '”', '‘', '’', '(', '[')
            .split(" ")
            .firstOrNull()
            ?.trim(',', ';', ':', ')', ']', '"', '\'')
            ?.lowercase()
            ?: return false
        return normalized in LOWERCASE_SENTENCE_STARTERS
    }

    private fun startsPrepositionSentenceStarterWord(
        nextWord: String,
        gap: Double,
        nearSegmentBoundary: Boolean,
        span: Double
    ): Boolean {
        val normalized = nextWord.trim()
            .trimStart('"', '\'', '“', '”', '‘', '’', '(', '[')
            .trimEnd(',', ';', ':', ')', ']', '"', '\'')
            .lowercase()
        return normalized == "at" && (gap >= 0.25 || nearSegmentBoundary || span >= 5.0)
    }

    private fun startsPhraseSentenceStarter(lookaheadWords: List<WhisperWord>): Boolean {
        if (lookaheadWords.isEmpty()) return false
        val t0 = normalizeToken(lookaheadWords.getOrNull(0)?.word.orEmpty())
        val t1 = normalizeToken(lookaheadWords.getOrNull(1)?.word.orEmpty())
        val t2 = normalizeToken(lookaheadWords.getOrNull(2)?.word.orEmpty())
        if (t0 == "according" && t1 == "to") return true
        if (t0 == "right" && t1 == "i" && t2 == "mean") return true
        if (t0 == "i" && t1 == "mean") return true
        if (t0 == "this" && t1 == "abrupt") return true
        if (t0 == "these" && t1 == "abrupt") return true
        if (t0 == "the" && t1 == "international") return true
        if (t0 == "to" && t1 == "make" && t2 == "matters") return true
        if (t0 == "previously") return true
        if (t0 == "so" && t1 == "if") return true
        if (t0 == "so" && (t1 == "i'm" || t1 == "im" || t1 == "i")) return true
        if (t0 == "when") return true
        return false
    }

    private fun startsSubjectSentenceStarter(lookaheadWords: List<WhisperWord>): Boolean {
        if (lookaheadWords.size < 2) return false
        val t0 = normalizeToken(lookaheadWords[0].word)
        val t1 = normalizeToken(lookaheadWords[1].word)
        if (t0.isBlank() || t1.isBlank()) return false
        if (t0 in CONTINUATION_STARTERS) return false
        if (t0 in ARTICLE_WORDS) return false
        return t1 in SUBJECT_SECOND_WORDS
    }

    private fun startsCapitalizedSegmentStarter(
        prevWord: String,
        nextWord: String,
        gap: Double,
        nearSegmentStart: Boolean,
        nearSegmentBoundary: Boolean,
        nearPunctuationBoundary: Boolean,
        weakPunctuation: Boolean,
        span: Double
    ): Boolean {
        if (!startsStrongSentenceWord(nextWord)) return false
        val prev = normalizeToken(prevWord)
        val next = normalizeToken(nextWord)
        if (prev.isBlank() || next.isBlank()) return false
        if (prev in CAPITALIZED_STARTER_BLOCKING_PREV_WORDS) return false
        if (next in CAPITALIZED_STARTER_WEAK_WORDS) return false
        if (prev in DIALOGUE_STARTERS && gap <= 0.20 && !nearSegmentStart && !weakPunctuation) return false
        val hasAcousticAnchor = nearSegmentStart ||
            gap >= CAPITALIZED_STARTER_GAP_SEC ||
            nearSegmentBoundary ||
            nearPunctuationBoundary ||
            weakPunctuation
        val longEnough = span >= CAPITALIZED_STARTER_MIN_SPAN_SEC
        return hasAcousticAnchor && (nearSegmentStart || nearPunctuationBoundary || weakPunctuation || gap >= 0.35 || longEnough)
    }

    private fun normalizeToken(word: String): String {
        return word.trim()
            .trimStart('"', '\'', '“', '”', '‘', '’', '(', '[')
            .trimEnd(',', ';', ':', ')', ']', '"', '\'')
            .lowercase()
    }

    private fun endsWeakBoundary(word: String): Boolean {
        val trimmed = word.trimEnd()
        return trimmed.endsWith(",") || trimmed.endsWith(";") || trimmed.endsWith(":")
    }

    private fun shouldProtectBoundary(prevWord: String, nextWord: String): Boolean {
        val prev = normalizeToken(prevWord)
        val next = normalizeToken(nextWord)
        if (prev.isBlank() || next.isBlank()) return false
        if (prev in INCOMPLETE_TAIL_WORDS) return true
        if (prev == "south" && next == "korea") return true
        if (prev == "google" && next == "deepmind") return true
        if (prev == "alpha" && next == "go") return true
        if (prev == "seung" && next == "hyun") return true
        if (prev == "lee" && next == "sedol") return true
        if (prev == "yoon" && next == "koo") return true
        return false
    }

    private fun shouldAlwaysProtectBoundary(prevWord: String, nextWord: String): Boolean {
        val prev = normalizeToken(prevWord)
        val next = normalizeToken(nextWord)
        if (prev.isBlank() || next.isBlank()) return false
        if (prev.endsWith("'s") || prev.endsWith("’s")) return true
        if (prev in CURRENCY_LIST_WORDS && next in CURRENCY_LIST_CONTINUATIONS) return true
        if (prev == "s" && next in ENUMERATION_AFTER_US_WORDS) return true
        if (prev == "u" && next == "s") return true
        return false
    }

    private fun buildSegmentBoundaries(segments: List<WhisperSegment>): List<Double> {
        if (segments.isEmpty()) return emptyList()
        return segments.asSequence()
            .filter { endsSentence(it.text) }
            .map { it.end }
            .toList()
    }

    private fun buildPunctuationBoundaries(
        segments: List<WhisperSegment>,
        words: List<WhisperWord>
    ): List<PunctuationBoundary> {
        if (segments.isEmpty() || words.isEmpty()) return emptyList()
        val out = mutableListOf<PunctuationBoundary>()
        for (segment in segments) {
            val tokens = segment.text.trim()
                .split(Regex("\\s+"))
                .map { raw -> normalizeAlignmentToken(raw) to extractTokenTerminalPunctuation(raw) }
                .filter { it.first.isNotBlank() }
            extractTerminalPunctuation(segment.text)?.let { punct ->
                val lastToken = tokens.lastOrNull()?.first.orEmpty()
                words.asSequence()
                    .filter { it.start >= segment.end - WORD_TIME_EPSILON_SEC }
                    .filter { it.start <= segment.end + SEGMENT_TERMINAL_PUNCTUATION_LOOKAHEAD_SEC }
                    .firstOrNull { normalizeAlignmentToken(it.word) != lastToken }
                    ?.let { nextWord ->
                        out += PunctuationBoundary(nextWord.start, punct)
                    }
            }
            if (tokens.size <= 1) continue

            val segmentWords = words.filter {
                overlapsWindow(
                    it.start,
                    it.end,
                    segment.start - SEGMENT_WORD_ALIGN_PAD_SEC,
                    segment.end + SEGMENT_WORD_ALIGN_PAD_SEC
                )
            }
            if (segmentWords.isEmpty()) continue

            var cursor = 0
            var pendingPunctuation: String? = null
            for ((token, punctuationAfter) in tokens) {
                var matchIndex = -1
                while (cursor < segmentWords.size) {
                    if (normalizeAlignmentToken(segmentWords[cursor].word) == token) {
                        matchIndex = cursor
                        break
                    }
                    cursor += 1
                }
                if (matchIndex >= 0) {
                    pendingPunctuation?.let { punct ->
                        out += PunctuationBoundary(segmentWords[matchIndex].start, punct)
                    }
                    cursor = matchIndex + 1
                }
                pendingPunctuation = punctuationAfter
            }
        }
        return out.distinctBy { "%.2f:${it.punctuation}".format(it.startSec) }
    }

    private fun extractTerminalPunctuation(text: String): String? {
        val trimmed = text.trimEnd()
        if (trimmed.isBlank()) return null
        val punctIndex = trimmed.indexOfLast { it == '.' || it == '?' || it == '!' }
        if (punctIndex < 0) return null
        val suffix = trimmed.substring(punctIndex)
        val trailing = suffix.drop(1)
        if (trailing.any { it !in TERMINAL_PUNCTUATION_CLOSERS }) return null
        return suffix
    }

    private fun extractTokenTerminalPunctuation(text: String): String? {
        val trimmed = text.trimEnd()
        if (trimmed.isBlank()) return null
        val punctIndex = trimmed.indexOfLast { it == '.' || it == '?' || it == '!' }
        if (punctIndex < 0) return null
        val suffix = trimmed.substring(punctIndex)
        val trailing = suffix.drop(1)
        if (trailing.any { it !in TERMINAL_PUNCTUATION_CLOSERS }) return null
        val compact = trimmed.filter { !it.isWhitespace() }
        if (Regex("""([A-Za-z]\.){2,}""").matches(compact)) return null
        if (Regex("""[A-Za-z]\.""").matches(compact)) return null
        return suffix
    }

    private fun extractInlinePunctuation(text: String): String? {
        val trimmed = text.trimEnd()
        if (trimmed.isBlank()) return null
        val last = trimmed.last()
        if (last == ',' || last == ';' || last == ':') return last.toString()
        return extractTokenTerminalPunctuation(trimmed)
    }

    private fun normalizeAlignmentToken(word: String): String {
        return word.trim()
            .trimStart('"', '\'', '(', '[')
            .trimEnd(',', ';', ':', '.', '!', '?', ')', ']', '"', '\'')
            .lowercase()
    }

    private fun appendTerminalPunctuation(text: String, punctuation: String): String {
        val normalized = normalizeText(text)
        if (normalized.isBlank() || punctuation.isBlank()) return normalized
        val stripped = normalized.trimEnd(*TERMINAL_PUNCTUATION_CLOSERS.toCharArray())
        if (endsSentence(stripped)) return normalized
        return normalizeText(stripped + punctuation)
    }

    private fun appendInlinePunctuation(text: String, punctuation: String): String {
        if (text.isBlank() || punctuation.isBlank()) return text
        val stripped = text.trimEnd(',', ';', ':', '.', '!', '?')
        if (stripped.isBlank()) return text
        val currentLast = text.trimEnd().lastOrNull()
        if (currentLast == ',' || currentLast == ';' || currentLast == ':' || currentLast == '.' || currentLast == '?' || currentLast == '!') {
            return text
        }
        return stripped + punctuation
    }

    private fun restoreCommonSourcePunctuation(text: String, sourceText: String): String {
        var out = text
        if (Regex("""\bU\.S\.?\b""", RegexOption.IGNORE_CASE).containsMatchIn(sourceText)) {
            out = out.replace(Regex("""\bU S\b"""), "U.S.")
        }
        Regex("""\b\d+\.\d+\b""").findAll(sourceText).forEach { match ->
            val decimal = match.value
            val parts = decimal.split(".")
            if (parts.size == 2) {
                out = out.replace(Regex("""\b${Regex.escape(parts[0])}\s+${Regex.escape(parts[1])}\b"""), decimal)
            }
        }
        return out
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
        stats: GapStats,
        allowLowQuality: Boolean
    ): Int? {
        if (words.size < 6) return null
        var bestIndex: Int? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in 1 until words.lastIndex) {
            if (i < MIN_SIDE_WORDS || (words.size - (i + 1)) < MIN_SIDE_WORDS) continue
            val prev = words[i]
            val next = words[i + 1]
            if (shouldAlwaysProtectBoundary(prev.word, next.word)) continue
            if (shouldProtectBoundary(prev.word, next.word)) continue
            val leftSpan = (prev.end - words.first().start).coerceAtLeast(0.0)
            val rightSpan = (words.last().end - next.start).coerceAtLeast(0.0)
            if (leftSpan < MIN_SIDE_SPAN_SEC || rightSpan < MIN_SIDE_SPAN_SEC) continue
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
            val nextToken = normalizeToken(next.word)
            val hasStrongAnchor = gap >= 0.55 || nearSegmentBoundary || gapZ >= 1.4 || endsWeakBoundary(prev.word)
            if (nextToken in NOUN_PHRASE_CONTINUATIONS && !(startsStrongSentenceWord(next.word) && hasStrongAnchor)) {
                score -= 1.2
            }
            val hasSemanticCue = endsSentence(prev.word) ||
                endsWeakBoundary(prev.word) ||
                nearSegmentBoundary ||
                startsPhraseSentenceStarter(words.subList(i + 1, min(i + 5, words.size))) ||
                startsDialogueStarterWord(next.word) ||
                startsLowercaseSentenceStarterWord(next.word) ||
                startsPrepositionSentenceStarterWord(next.word, gap, nearSegmentBoundary, words.last().end - words.first().start)
            if (!allowLowQuality && !hasSemanticCue) continue
            if (score > bestScore) {
                bestScore = score
                bestIndex = i
            }
        }
        if (!allowLowQuality && bestScore < INTERNAL_CUT_MIN_SCORE) return null
        return bestIndex
    }

    private fun findStarterPreferredCutIndex(
        words: List<WhisperWord>,
        boundaries: List<Double>
    ): Int? {
        if (words.size < 6) return null
        var bestIndex: Int? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in 1 until words.lastIndex) {
            if (i < MIN_SIDE_WORDS || (words.size - (i + 1)) < MIN_SIDE_WORDS) continue
            val prev = words[i]
            val next = words[i + 1]
            if (shouldAlwaysProtectBoundary(prev.word, next.word)) continue
            if (shouldProtectBoundary(prev.word, next.word)) continue
            val gap = (next.start - prev.end).coerceAtLeast(0.0)
            val span = words.last().end - words.first().start
            val nearSegmentBoundary = boundaries.any { it in (prev.end - 0.12)..(next.start + 0.12) }
            val phraseStarter = startsPhraseSentenceStarter(words.subList(i + 1, min(i + 5, words.size)))
            val lowercaseStarter = startsLowercaseSentenceStarterWord(next.word)
            val dialogueStarter = startsDialogueStarterWord(next.word)
            val prepositionStarter = startsPrepositionSentenceStarterWord(next.word, gap, nearSegmentBoundary, span)
            if (!phraseStarter && !lowercaseStarter && !dialogueStarter && !prepositionStarter) continue
            var score = 0.0
            if (phraseStarter) score += 2.4
            if (dialogueStarter) score += 1.6
            if (lowercaseStarter) score += 1.2
            if (prepositionStarter) score += 1.1
            score += (gap * 1.5).coerceAtMost(1.6)
            if (nearSegmentBoundary) score += 0.5
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

    private fun postProcessWordSentences(items: List<SentenceTimestamp>): List<SentenceTimestamp> {
        if (items.size <= 1) return items
        return mergeAdjacentWhile(items) { cur, next ->
            val gap = (next.startSec - cur.endSec).coerceAtLeast(0.0)
            val curDur = cur.endSec - cur.startSec
            val combinedDur = next.endSec - cur.startSec
            val shortTailJoin = curDur < 1.0 &&
                wordCount(cur.text) <= 2 &&
                gap <= 0.35 &&
                !isStandaloneUtterance(cur.text) &&
                !startsDiscourseStarterText(next.text)
            val continuationJoin = !endsSentence(cur.text) &&
                startsLowercaseContinuation(next.text) &&
                !startsLowercaseSentenceStarterText(next.text) &&
                !startsDiscourseStarterText(next.text) &&
                gap <= 0.45 &&
                combinedDur <= 10.0
            val incompleteTailJoin = !endsSentence(cur.text) &&
                gap <= 0.20 &&
                combinedDur <= 18.0 &&
                (endsWithIncompleteTail(cur.text) || protectsTextBoundary(cur.text, next.text))
            val hardCutRepairJoin = !endsSentence(cur.text) &&
                gap <= HARD_CUT_REPAIR_GAP_SEC &&
                combinedDur <= HARD_CUT_REPAIR_MAX_SPAN_SEC &&
                wordCount(cur.text) >= 4 &&
                wordCount(next.text) >= 2 &&
                !startsClearSentenceStarterText(next.text) &&
                !startsDiscourseStarterText(next.text)
            shortTailJoin || continuationJoin || incompleteTailJoin || hardCutRepairJoin
        }
    }

    private fun mergeAdjacentWhile(
        items: List<SentenceTimestamp>,
        shouldJoin: (SentenceTimestamp, SentenceTimestamp) -> Boolean
    ): List<SentenceTimestamp> {
        var current = items
        var changed: Boolean
        do {
            changed = false
            val out = mutableListOf<SentenceTimestamp>()
            var i = 0
            while (i < current.size) {
                val cur = current[i]
                val next = current.getOrNull(i + 1)
                if (next != null && shouldJoin(cur, next)) {
                    out += cur.copy(
                        endSec = next.endSec,
                        text = normalizeText("${cur.text} ${next.text}")
                    )
                    changed = true
                    i += 2
                } else {
                    out += cur
                    i += 1
                }
            }
            current = out
        } while (changed)
        return current
    }

    private fun startsLowercaseContinuation(text: String): Boolean {
        val tokens = text.trimStart()
            .trimStart('"', '\'', '“', '”', '‘', '’', '(', '[')
            .split(Regex("\\s+"))
            .map { it.trim(',', ';', ':', '.', '!', '?').lowercase() }
            .filter { it.isNotBlank() }
        val token = tokens.getOrNull(0) ?: return false
        if (token == "to" && tokens.getOrNull(1) == "make" && tokens.getOrNull(2) == "matters") return false
        return token in CONTINUATION_STARTERS
    }

    private fun endsWithIncompleteTail(text: String): Boolean {
        val token = lastToken(text) ?: return false
        return token in INCOMPLETE_TAIL_WORDS
    }

    private fun protectsTextBoundary(curText: String, nextText: String): Boolean {
        val prev = lastToken(curText) ?: return false
        val next = firstToken(nextText) ?: return false
        if (prev == "south" && next == "korea") return true
        if (prev == "google" && next == "deepmind") return true
        if (prev == "alpha" && next == "go") return true
        if (prev == "seung" && next == "hyun") return true
        if (prev == "lee" && next == "sedol") return true
        if (prev == "yoon" && next == "koo") return true
        return false
    }

    private fun firstToken(text: String): String? =
        text.trim()
            .split(Regex("\\s+"))
            .firstOrNull()
            ?.let(::normalizeToken)
            ?.takeIf { it.isNotBlank() }

    private fun lastToken(text: String): String? =
        text.trim()
            .split(Regex("\\s+"))
            .lastOrNull()
            ?.let(::normalizeToken)
            ?.takeIf { it.isNotBlank() }

    private fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    private fun isStandaloneUtterance(text: String): Boolean {
        val normalized = text.trim().trimEnd('.', '!', '?').lowercase()
        return normalized in setOf("ok", "yes", "no", "yeah", "mm", "one", "right")
    }

    private fun startsDiscourseStarterText(text: String): Boolean {
        val normalized = text.trim()
            .trimStart('"', '\'', '“', '”', '‘', '’', '(', '[')
            .trimEnd(',', ';', ':', ')', ']', '"', '\'')
            .lowercase()
        return normalized.startsWith("so ") ||
            normalized.startsWith("that's ") ||
            normalized.startsWith("thats ") ||
            normalized.startsWith("absolutely ") ||
            normalized.startsWith("but ") ||
            normalized.startsWith("however ") ||
            normalized.startsWith("and then ") ||
            normalized.startsWith("now ")
    }

    private fun startsClearSentenceStarterText(text: String): Boolean {
        val tokens = text.trim()
            .split(Regex("\\s+"))
            .map { normalizeToken(it) }
            .filter { it.isNotBlank() }
        val t0 = tokens.getOrNull(0) ?: return false
        val t1 = tokens.getOrNull(1).orEmpty()
        val t2 = tokens.getOrNull(2).orEmpty()
        if (t0 in DIALOGUE_STARTERS) return true
        if (t0 == "according" && t1 == "to") return true
        if ((t0 == "that's" || t0 == "thats") && t1 == "right") return true
        if (t0 == "absolutely") return true
        if (t0 == "right" && t1 == "i" && t2 == "mean") return true
        if (t0 == "i" && t1 == "mean") return true
        if (t0 == "this" && t1 == "abrupt") return true
        if (t0 == "these" && t1 == "abrupt") return true
        if (t0 == "the" && t1 == "international") return true
        if (t0 == "to" && t1 == "make" && t2 == "matters") return true
        if (t0 == "previously") return true
        if (t0 == "so" && t1 == "if") return true
        if (t0 == "so" && (t1 == "i'm" || t1 == "im" || t1 == "i")) return true
        if (t0 == "when") return true
        if (t0 == "at") return true
        if (t0 == "google" && t1 == "recently") return true
        if (t0 in LOWERCASE_SENTENCE_STARTERS && t1 in SUBJECT_SECOND_WORDS) return true
        return false
    }

    companion object {
        private const val TAG = "SentenceAssembler"
        private const val WORD_TIME_EPSILON_SEC = 0.001
        private const val SOFT_SPLIT_THRESHOLD = 2.8
        private const val CONNECTOR_SPLIT_THRESHOLD = 2.0
        private const val SPLIT_WINDOW_PAD_SEC = 1.8
        private const val CLIP_EPSILON_SEC = 0.10
        private const val MIN_VALID_SENTENCE_SEC = 0.05
        private const val WORD_MATCH_EPSILON_SEC = 0.15
        private const val STRICT_CLIP_EPSILON_SEC = 0.05
        private const val SHORT_CLIP_SEC = 0.8
        private const val HARD_MAX_WORDS_SOFT = 52
        private const val HARD_MAX_SPAN_SEC_SOFT = 16.0
        private const val HARD_MAX_WORDS_EMERGENCY = 72
        private const val HARD_MAX_SPAN_SEC_EMERGENCY = 22.0
        private const val HARD_CUT_REPAIR_GAP_SEC = 0.28
        private const val HARD_CUT_REPAIR_MAX_SPAN_SEC = 30.0
        private const val HARD_SILENCE_SEC = 1.05
        private const val HARD_SILENCE_CONTINUATION_SEC = 1.25
        private const val INTERNAL_CUT_MIN_SCORE = 1.9
        private const val MIN_SIDE_WORDS = 3
        private const val MIN_SIDE_SPAN_SEC = 0.8
        private const val STARTER_ANCHOR_GAP_SEC = 0.30
        private const val STARTER_ANCHOR_Z = 1.4
        private const val STARTER_DIRECT_MIN_SCORE = 2.2
        private const val SUBJECT_STARTER_DIRECT_MIN_SCORE = 2.4
        private const val CAPITALIZED_STARTER_DIRECT_MIN_SCORE = 2.35
        private const val CAPITALIZED_STARTER_GAP_SEC = 0.28
        private const val CAPITALIZED_STARTER_MIN_SPAN_SEC = 4.8
        private const val PHRASE_STARTER_MIN_SPAN_SEC = 5.0
        private const val PREPOSITION_STARTER_MIN_SPAN_SEC = 6.0
        private const val PROTECTED_BOUNDARY_GAP_SEC = 0.35
        private const val PUNCTUATION_BOUNDARY_MATCH_SEC = 0.35
        private const val INTERNAL_PUNCTUATION_MAX_GAP_SEC = 1.0
        private const val SEGMENT_WORD_ALIGN_PAD_SEC = 0.25
        private const val SEGMENT_TERMINAL_PUNCTUATION_LOOKAHEAD_SEC = 1.2
        private const val TERMINAL_PUNCTUATION_CLOSERS = "\"')]}"
        private val WEAK_INLINE_PUNCTUATION = setOf(",", ";", ":")
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
        private val DIALOGUE_STARTERS = setOf(
            "yes",
            "no",
            "yeah",
            "ok",
            "okay",
            "right"
        )
        private val LOWERCASE_SENTENCE_STARTERS = setOf(
            "this",
            "these",
            "those",
            "there",
            "he",
            "she",
            "they",
            "we",
            "it",
            "yes",
            "no",
            "yeah",
            "ok",
            "okay",
            "absolutely",
            "actually",
            "now",
            "then"
        )
        private val CONTINUATION_STARTERS = setOf(
            "to",
            "for",
            "of",
            "that",
            "which",
            "who",
            "whom",
            "whose",
            "by",
            "in",
            "on",
            "with",
            "from",
            "because",
            "while",
            "if",
            "when",
            "where",
            "after",
            "before",
            "since",
            "unless",
            "although",
            "though"
        )
        private val SUBJECT_SECOND_WORDS = setOf(
            "recently",
            "also",
            "now",
            "will",
            "is",
            "are",
            "was",
            "were",
            "has",
            "have",
            "had"
        )
        private val ENUMERATION_AFTER_US_WORDS = setOf(
            "israel",
            "iran",
            "china",
            "russia",
            "japan",
            "korea",
            "europe",
            "turkey"
        )
        private val CURRENCY_LIST_WORDS = setOf(
            "rial",
            "rials",
            "yuan",
            "won",
            "dollar",
            "dollars",
            "euro",
            "euros",
            "yen"
        )
        private val CURRENCY_LIST_CONTINUATIONS = setOf(
            "chinese",
            "u",
            "us",
            "u.s",
            "dollar",
            "dollars",
            "euro",
            "euros",
            "yen",
            "and"
        )
        private val ARTICLE_WORDS = setOf(
            "the",
            "a",
            "an"
        )
        private val CAPITALIZED_STARTER_BLOCKING_PREV_WORDS = ARTICLE_WORDS + setOf(
            "of",
            "to",
            "for",
            "from",
            "in",
            "on",
            "with",
            "by",
            "at",
            "as",
            "into",
            "about",
            "between",
            "during",
            "his",
            "her",
            "their",
            "our",
            "its",
            "this",
            "that",
            "these",
            "those"
        )
        private val CAPITALIZED_STARTER_WEAK_WORDS = setOf(
            "i",
            "mr",
            "mrs",
            "ms",
            "dr",
            "prof",
            "st"
        )
        private val INCOMPLETE_TAIL_WORDS = setOf(
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
        private val NOUN_PHRASE_CONTINUATIONS = setOf(
            "the",
            "a",
            "an",
            "this",
            "these",
            "that",
            "those",
            "his",
            "her",
            "their",
            "its",
            "our"
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

private data class PunctuationBoundary(
    val startSec: Double,
    val punctuation: String
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
