package com.flowboard.ime.testing

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.engine.LayoutManager
import com.flowboard.ime.engine.ScoringEngine

/**
 * Automated Bot Tester for the English-only Flowboard engine — Prototype 22 V22.2.0.
 *
 * Simulates user typing character by character through the scoring and
 * layout engines, measuring tap rate, swipe rate, and miss rate.
 *
 * Fully matches P22 JS bot.js 1:1 including:
 *   - Sticky Key simulation
 *   - OOV word extraction (words not in wordList appearing 2+ times)
 *   - Swipe word tracking & top swipe words per letter (a-z)
 *   - Engine state breakdown stats
 *   - Smart quote normalization
 *   - Evaluation modes: FULL vs LETTERS
 */
class BotTester(
    private val repo: FlowboardRepository,
    private val scoringEngine: ScoringEngine,
    private val layoutManager: LayoutManager
) {

    enum class EvalMode { FULL, LETTERS }

    data class EngineStatEntry(
        var taps: Int = 0,
        var swipes: Int = 0,
        var misses: Int = 0,
        var total: Int = 0
    )

    data class WordCountEntry(
        val word: String,
        val count: Int
    )

    data class LetterSwipeEntry(
        val totalSwipes: Int,
        val topWords: List<WordCountEntry>
    )

    data class BotStats(
        var totalChars: Int = 0,
        var taps: Int = 0,
        var swipes: Int = 0,
        var misses: Int = 0,
        val missDetails: MutableMap<String, Int> = mutableMapOf(),
        val swipeDetails: MutableMap<String, Int> = mutableMapOf(),
        val engineStats: MutableMap<String, EngineStatEntry> = mutableMapOf(),
        var oovWords: List<String> = emptyList(),
        var swipeWords: List<WordCountEntry> = emptyList(),
        var swipesByLetter: Map<String, LetterSwipeEntry> = emptyMap()
    ) {
        val tapPercent: Double
            get() = if (totalChars > 0) (taps.toDouble() / totalChars) * 100.0 else 0.0

        val swipePercent: Double
            get() = if (totalChars > 0) (swipes.toDouble() / totalChars) * 100.0 else 0.0

        val missPercent: Double
            get() = if (totalChars > 0) (misses.toDouble() / totalChars) * 100.0 else 0.0
    }

    fun runTest(
        testSentences: List<String>,
        evalMode: EvalMode = EvalMode.LETTERS,
        onProgress: ((completedSentences: Int, totalSentences: Int, stats: BotStats) -> Unit)? = null
    ): BotStats {
        val stats = BotStats()
        scoringEngine.resetTrieCache()

        // Reset repo sticky state before run
        repo.lastActionKeyId = null
        repo.lastActionSlot = null
        repo.lastActionChar = null
        repo.stickyChar = null

        val wordSet = repo.wordReverseMap.keys
        val oovCounts = HashMap<String, Int>()
        val swipeWordCounts = HashMap<String, Int>()
        val letterSwipeCounts = HashMap<String, HashMap<String, Int>>()
        for (ch in 'a'..'z') {
            letterSwipeCounts[ch.toString()] = HashMap()
        }

        val totalSentences = testSentences.size
        val wordRegex = Regex("[a-z]+(?:'[a-z]+)?")

        testSentences.forEachIndexed { sIdx, sentence ->
            if (sentence.isEmpty()) return@forEachIndexed

            val wordsInSentence = wordRegex.findAll(sentence.lowercase()).map { it.value }.toList()
            var wordIdx = 0

            // Extract OOV words from sentence
            for (w in wordsInSentence) {
                if (!wordSet.contains(w)) {
                    oovCounts[w] = (oovCounts[w] ?: 0) + 1
                }
            }

            // Reset trie cache before each sentence (matching JS bot.js lines 55-56)
            scoringEngine.resetTrieCache()

            var botTypedText = ""
            for (i in sentence.indices) {
                val origChar = sentence[i]
                var charStr = origChar.toString()

                // Normalize smart quotes to standard single quote (matches js/app.js)
                if (charStr == "\u2018" || charStr == "\u2019" || charStr == "\u0060") {
                    charStr = "'"
                }

                // Handle Spacebar
                if (charStr == " ") {
                    botTypedText += charStr
                    wordIdx++
                    repo.lastActionKeyId = null
                    repo.lastActionSlot = null
                    repo.lastActionChar = null
                    repo.stickyChar = null

                    if (evalMode == EvalMode.FULL) {
                        stats.totalChars++
                        stats.taps++
                        val spaceKey = "Spacebar (กดเว้นวรรค)"
                        val spaceEntry = stats.engineStats.getOrPut(spaceKey) { EngineStatEntry() }
                        spaceEntry.total++
                        spaceEntry.taps++
                    }
                    continue
                }

                val lowerChar = charStr.lowercase()
                val isTracked = (lowerChar.length == 1 && lowerChar[0] in 'a'..'z') || charStr == "'"

                // In LETTERS mode, skip non-letter characters
                if (evalMode == EvalMode.LETTERS && !isTracked) continue

                // Update Sticky Char status before scoring/layout (matching JS bot.js & IMEService)
                val lastChar = repo.lastActionChar
                if (lastChar != null && scoringEngine.isDoubleCharValid(botTypedText, lastChar)) {
                    repo.stickyChar = lastChar
                } else {
                    repo.stickyChar = null
                }

                // Calculate scores and assign layout
                val scores = scoringEngine.calculateScores(botTypedText)
                val currentEngine = scoringEngine.engineStatus

                val engineEntry = stats.engineStats.getOrPut(currentEngine) { EngineStatEntry() }
                val targetCheckChar = lowerChar

                // Assign layout using LayoutManager
                val layout = layoutManager.assignLayout(scores)

                var foundAction = "miss"
                var foundKeyId: String? = null

                // Scan 9 key slots for the target character
                for (k in 1..9) {
                    val keyId = "key_$k"
                    val keySlots = layout[keyId] ?: continue
                    if (keySlots.tap == targetCheckChar) {
                        foundAction = "tap"
                        foundKeyId = keyId
                        break
                    }
                    if (keySlots.up == targetCheckChar) {
                        foundAction = "up"
                        foundKeyId = keyId
                        break
                    }
                    if (keySlots.left == targetCheckChar) {
                        foundAction = "left"
                        foundKeyId = keyId
                        break
                    }
                    if (keySlots.right == targetCheckChar) {
                        foundAction = "right"
                        foundKeyId = keyId
                        break
                    }
                    if (keySlots.down == targetCheckChar) {
                        foundAction = "down"
                        foundKeyId = keyId
                        break
                    }
                }

                // Handle digits / symbols in FULL mode
                if (evalMode == EvalMode.FULL && (origChar in '0'..'9') && foundAction == "miss") {
                    foundAction = "down" // Swipe Down for numbers
                } else if (evalMode == EvalMode.FULL && !isTracked && foundAction == "miss") {
                    foundAction = "swipe" // Swipe in Alt layer
                }

                // Record stats
                stats.totalChars++
                engineEntry.total++

                if (foundAction == "tap") {
                    stats.taps++
                    engineEntry.taps++
                } else if (foundAction in listOf("up", "left", "right", "down", "swipe")) {
                    stats.swipes++
                    stats.swipeDetails[charStr] = (stats.swipeDetails[charStr] ?: 0) + 1
                    engineEntry.swipes++

                    val curWord = wordsInSentence.getOrNull(wordIdx) ?: "unknown"
                    swipeWordCounts[curWord] = (swipeWordCounts[curWord] ?: 0) + 1

                    if (targetCheckChar.length == 1 && targetCheckChar[0] in 'a'..'z') {
                        val enParts = botTypedText.lowercase().trim().split("\\s+".toRegex())
                        val currentWordPrefix = if (enParts.isNotEmpty()) enParts.last() else ""
                        val targetWord = (currentWordPrefix + targetCheckChar).replace(Regex("[^a-z]"), "")
                        if (targetWord.isNotEmpty()) {
                            val mapForChar = letterSwipeCounts[targetCheckChar]
                            if (mapForChar != null) {
                                mapForChar[targetWord] = (mapForChar[targetWord] ?: 0) + 1
                            }
                        }
                    }
                } else {
                    stats.misses++
                    stats.missDetails[charStr] = (stats.missDetails[charStr] ?: 0) + 1
                    engineEntry.misses++
                }

                // Update last action for next char (for Sticky Key)
                if (foundKeyId != null) {
                    repo.lastActionKeyId = foundKeyId
                    repo.lastActionSlot = foundAction
                    repo.lastActionChar = targetCheckChar
                } else {
                    repo.lastActionKeyId = null
                    repo.lastActionSlot = null
                    repo.lastActionChar = null
                }

                botTypedText += charStr
            }

            val yieldFrequency = if (totalSentences > 1000) 50 else 5
            if (onProgress != null && (sIdx % yieldFrequency == 0 || sIdx == totalSentences - 1)) {
                onProgress(sIdx + 1, totalSentences, stats)
            }
        }

        // Filter OOV words appearing >= 2 times
        stats.oovWords = oovCounts.filter { it.value >= 2 }
            .entries.sortedByDescending { it.value }
            .map { it.key }

        // Rank words causing swipes
        stats.swipeWords = swipeWordCounts.entries
            .sortedByDescending { it.value }
            .map { WordCountEntry(it.key, it.value) }

        // Rank swipe words by letter (a-z)
        val swipesByLetter = HashMap<String, LetterSwipeEntry>()
        for ((charStr, map) in letterSwipeCounts) {
            val sortedWords = map.entries
                .sortedByDescending { it.value }
                .map { WordCountEntry(it.key, it.value) }
            val totalSwipes = sortedWords.sumOf { it.count }
            if (totalSwipes > 0) {
                swipesByLetter[charStr] = LetterSwipeEntry(
                    totalSwipes = totalSwipes,
                    topWords = sortedWords.take(10)
                )
            }
        }
        stats.swipesByLetter = swipesByLetter

        return stats
    }
}
