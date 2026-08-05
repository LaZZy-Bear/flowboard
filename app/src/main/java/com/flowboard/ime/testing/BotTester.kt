package com.flowboard.ime.testing

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.engine.LayoutManager
import com.flowboard.ime.engine.ScoringEngine

/**
 * Automated Bot Tester for the English-only Flowboard engine.
 *
 * Simulates user typing character by character through the scoring and
 * layout engines, measuring tap rate, swipe rate, and miss rate.
 *
 * Fully simulates Sticky Key state (lastActionKeyId, lastActionSlot, stickyChar)
 * matching Prototype 22 JS bot.js 1:1.
 *
 * Evaluation modes:
 * - FULL: Counts letters + Space (Spacebar = 100% tap) -> 91.1% Tap Rate
 * - LETTERS: Counts a-z & single quote only (excludes space) -> 89.2% Tap Rate
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
        var misses: Int = 0
    )

    data class BotStats(
        var totalChars: Int = 0,
        var taps: Int = 0,
        var swipes: Int = 0,
        var misses: Int = 0,
        val missDetails: MutableMap<String, Int> = mutableMapOf(),
        val swipeDetails: MutableMap<String, Int> = mutableMapOf(),
        val engineStats: MutableMap<String, EngineStatEntry> = mutableMapOf()
    ) {
        val tapPercent: Double
            get() = if (totalChars > 0) (taps.toDouble() / totalChars) * 100.0 else 0.0

        val swipePercent: Double
            get() = if (totalChars > 0) (swipes.toDouble() / totalChars) * 100.0 else 0.0

        val missPercent: Double
            get() = if (totalChars > 0) (misses.toDouble() / totalChars) * 100.0 else 0.0
    }

    fun runTest(testSentences: List<String>, evalMode: EvalMode = EvalMode.FULL): BotStats {
        val stats = BotStats()
        scoringEngine.resetTrieCache()

        // Reset repo sticky state before run
        repo.lastActionKeyId = null
        repo.lastActionSlot = null
        repo.lastActionChar = null
        repo.stickyChar = null

        testSentences.forEach { sentence ->
            if (sentence.isEmpty()) return@forEach

            // Reset trie cache before each sentence (matching JS bot.js lines 55-56)
            scoringEngine.resetTrieCache()

            var botTypedText = ""
            for (i in sentence.indices) {
                val origChar = sentence[i]
                var charStr = origChar.toString()

                // Normalize smart quotes
                if (charStr == "\u2018" || charStr == "\u2019" || charStr == "\u0060") {
                    charStr = "'"
                }

                // Handle Spacebar
                if (charStr == " ") {
                    botTypedText += charStr
                    repo.lastActionKeyId = null
                    repo.lastActionSlot = null
                    repo.lastActionChar = null
                    repo.stickyChar = null

                    if (evalMode == EvalMode.FULL) {
                        stats.totalChars++
                        stats.taps++
                        val spaceKey = "Spacebar (กดเว้นวรรค)"
                        val spaceEntry = stats.engineStats.getOrPut(spaceKey) { EngineStatEntry() }
                        spaceEntry.taps++
                    }
                    continue
                }

                // Validation Gate: Only allow chars in the master layout + digits
                val lowerChar = charStr.lowercase()
                val isEngNumber = (origChar in '0'..'9')
                val isTracked = repo.masterLayout.containsKey(lowerChar) || isEngNumber
                if (evalMode == EvalMode.LETTERS && !isTracked) continue
                if (!isTracked) continue

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

                val layout = layoutManager.assignLayout(scores)

                var foundAction = "miss"
                var foundKeyId: String? = null
                val targetCheckChar = lowerChar  // English only — always lowercase

                // Scan key slots for the character
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

                stats.totalChars++
                if (foundAction == "tap") {
                    stats.taps++
                    engineEntry.taps++
                } else if (foundAction in listOf("up", "left", "right", "down", "swipe")) {
                    stats.swipes++
                    stats.swipeDetails[charStr] = (stats.swipeDetails[charStr] ?: 0) + 1
                    engineEntry.swipes++
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
        }

        return stats
    }
}
