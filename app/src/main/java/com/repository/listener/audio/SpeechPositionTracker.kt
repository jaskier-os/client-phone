package com.repository.listener.audio

import com.repository.listener.util.LogCollector
import kotlin.math.min

/**
 * Tracks speaker position in teleprompter text by matching
 * recognition output against the target text.
 *
 * Position advances monotonically (never scrolls back).
 *
 * Two-tier matching:
 * 1. Fast path: if any NEW word in the recognition matches the very next expected word,
 *    advance immediately. Safe because grammar constrains the vocabulary.
 * 2. Broad path: find a consecutive run of 2+ matching words within a search window.
 *    Handles bigger jumps when the user gets ahead.
 */
class SpeechPositionTracker {

    companion object {
        private const val TAG = "SpeechPosTracker"
        private const val SEARCH_WINDOW = 30
        private const val TAIL_WORDS = 8
        private const val MIN_CONSECUTIVE = 2
        // For single-word fast path, only look this many words ahead
        private const val SINGLE_MATCH_WINDOW = 3
    }

    data class WordInfo(
        val word: String,           // original word (with punctuation)
        val normalized: String,     // lowercase, stripped punctuation
        val startOffset: Int,       // char offset in original text
        val endOffset: Int          // char offset end (exclusive)
    )

    private var words: List<WordInfo> = emptyList()
    var currentIndex: Int = -1
        private set

    private var lastRecognized = emptyList<String>()

    fun setText(text: String) {
        words = parseWords(text)
        currentIndex = -1
        lastRecognized = emptyList()
        LogCollector.i(TAG, "setText: ${words.size} words parsed")
    }

    /**
     * Feed recognized text. Returns new word index if position advanced, null otherwise.
     */
    fun feedRecognition(text: String): Int? {
        if (words.isEmpty() || text.isBlank()) return null

        val recognized = tokenize(text)
        if (recognized.isEmpty()) return null

        val delta = extractDelta(lastRecognized, recognized)
        lastRecognized = recognized

        val searchStart = (currentIndex + 1).coerceAtLeast(0)
        if (searchStart >= words.size) return null

        // --- Fast path: single-word match for next few words ---
        // Only use delta words (newly appeared) to avoid re-matching old words.
        // Safe with grammar constraints since vocabulary is small.
        if (delta.isNotEmpty()) {
            val singleEnd = min(searchStart + SINGLE_MATCH_WINDOW, words.size)
            for (targetIdx in searchStart until singleEnd) {
                for (w in delta) {
                    if (fuzzyMatch(w, words[targetIdx].normalized)) {
                        currentIndex = targetIdx
                        return currentIndex
                    }
                }
            }
        }

        // --- Broad path: 2+ consecutive matches within search window ---
        val tail = if (recognized.size > TAIL_WORDS) {
            recognized.subList(recognized.size - TAIL_WORDS, recognized.size)
        } else recognized

        val searchEnd = min(searchStart + SEARCH_WINDOW, words.size)

        var bestRunEnd = -1
        var bestRunLen = 0

        for (tailOffset in tail.indices) {
            val remaining = tail.size - tailOffset
            if (remaining < MIN_CONSECUTIVE) break
            for (targetStart in searchStart until searchEnd) {
                var runLen = 0
                for (k in 0 until remaining) {
                    val targetIdx = targetStart + k
                    if (targetIdx >= words.size) break
                    if (fuzzyMatch(tail[tailOffset + k], words[targetIdx].normalized)) {
                        runLen++
                    } else {
                        break
                    }
                }
                if (runLen >= MIN_CONSECUTIVE && runLen > bestRunLen) {
                    bestRunLen = runLen
                    bestRunEnd = targetStart + runLen - 1
                }
            }
        }

        if (bestRunEnd > currentIndex) {
            currentIndex = bestRunEnd
            return currentIndex
        }

        return null
    }

    fun getWordCount(): Int = words.size

    fun getWords(): List<WordInfo> = words

    /**
     * Extract newly added words by comparing old and new recognized word lists.
     * If the new list extends the old one, returns the suffix. Otherwise returns
     * all new words (recognizer revised the partial).
     */
    private fun extractDelta(old: List<String>, new: List<String>): List<String> {
        if (old.isEmpty()) return new
        if (new.size <= old.size) {
            // Shortened or replaced -- treat all as new
            return new
        }
        // Check if new starts with old (common case: partial grows by appending)
        var prefixMatch = true
        for (i in old.indices) {
            if (i >= new.size || old[i] != new[i]) {
                prefixMatch = false
                break
            }
        }
        return if (prefixMatch) {
            new.subList(old.size, new.size)
        } else {
            // Revised earlier words -- return the tail that's new
            new.subList(maxOf(0, new.size - TAIL_WORDS), new.size)
        }
    }

    private fun parseWords(text: String): List<WordInfo> {
        val result = mutableListOf<WordInfo>()
        val pattern = Regex("\\S+")
        for (match in pattern.findAll(text)) {
            result.add(
                WordInfo(
                    word = match.value,
                    normalized = normalize(match.value),
                    startOffset = match.range.first,
                    endOffset = match.range.last + 1
                )
            )
        }
        return result
    }

    private fun tokenize(text: String): List<String> {
        return text.trim().split(Regex("\\s+"))
            .map { normalize(it) }
            .filter { it.isNotEmpty() && it != "unk" }  // filter [unk] tokens
    }

    private fun normalize(word: String): String {
        return word.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
    }

    private fun fuzzyMatch(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true

        // Short words (1-2 chars) require exact match
        if (a.length <= 2 || b.length <= 2) return false

        val maxLen = maxOf(a.length, b.length)
        val threshold = (maxLen * 0.35f).toInt()
        val distance = levenshtein(a, b)
        return distance <= threshold
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length

        if (kotlin.math.abs(m - n) > maxOf(m, n) / 2) return maxOf(m, n)

        val prev = IntArray(n + 1) { it }
        val curr = IntArray(n + 1)

        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,
                    curr[j - 1] + 1,
                    prev[j - 1] + cost
                )
            }
            System.arraycopy(curr, 0, prev, 0, n + 1)
        }
        return prev[n]
    }
}
