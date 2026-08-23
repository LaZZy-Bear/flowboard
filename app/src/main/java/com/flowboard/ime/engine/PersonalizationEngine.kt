package com.flowboard.ime.engine

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.PersonalProfile

/**
 * Personalization Engine — Prototype 22 (Static Profile Mode)
 *
 * Ported from js/personalize.js. Applies an additive personalization bonus
 * on top of the base scoring engine's normalized scores.
 *
 * Zero baseline degradation guarantee:
 *   - All bonuses are purely additive and applied AFTER the base engine finishes.
 *   - The base tap rate cannot decrease due to personalization.
 *
 * Two systems:
 *   1. Personal Word Pairs (bigram/trigram): boosts first-char of likely next words
 *      based on user's typing history. Trigram is tried first; falls back to bigram.
 *      Bonus levels: count>=16 → +1.5 (high), >=6 → +0.8 (mid), >=3 → +0.3 (low)
 *
 *   2. Uncertain Gap Boosting: when top-2 candidates are within 5.0 points of each other,
 *      boosts frequent personal words. Prevents the engine from being indecisive
 *      on common user words.
 *      Bonus levels: count>=30 → +2.0, >=15 → +1.2, >=6 → +0.5, >=3 → +0.2
 *
 * Both systems only activate in word-start states (1, 7, 8).
 *
 * Note on Layout Rules:
 *   - Digits (0-9) MUST NEVER receive score bonuses (they remain strictly on the swipe-down slot).
 *   - Supported symbols in masterLayout ('-', '.', ''') CAN receive score bonuses and compete for the TAP slot.
 */
class PersonalizationEngine(private val repo: FlowboardRepository) {

    companion object {
        // Trigram pair bonus levels (2-word history match — high confidence)
        private const val TRI_MAX = 85.0
        private const val TRI_HIGH = 70.0
        private const val TRI_MID = 55.0
        private const val TRI_LOW = 40.0

        // Bigram pair bonus levels (1-word history match)
        private const val BI_MAX = 75.0
        private const val BI_HIGH = 60.0
        private const val BI_MID = 45.0
        private const val BI_LOW = 30.0

        // Frequency bonus levels (word frequency / OOV)
        private const val FREQ_MAX = 75.0
        private const val FREQ_HIGH = 60.0
        private const val FREQ_MID = 45.0
    }

    /**
     * Apply personalization bonuses to [finalScores].
     *
     * @param finalScores  Mutable score map from ScoringEngine (modified in-place)
     * @param activeWordsArray  Completed words typed so far (before current word)
     * @param state  Current scoring state (1, 2, 3, 4, 7, 8)
     */
    fun applyPersonalization(
        finalScores: HashMap<String, Double>,
        activeWordsArray: List<String>,
        activePrefix: String,
        state: Int
    ) {
        val profile = repo.personalProfile
        if (profile.isEmpty) return

        val prefix = activePrefix.lowercase()
        val prefixLen = prefix.length
        val multiplier = repo.personalizationBoostMultiplier

        // System 1: Personal word pair boosts (bigram/trigram) — active across all typing states
        if (repo.personalizationPairsEnabled) {
            applyPersonalWordPairs(finalScores, activeWordsArray, profile, prefix, prefixLen, multiplier)
        }

        // System 2: Frequent words & learned OOV prefix boost
        if (repo.personalizationFreqEnabled) {
            val isWordStart = state == 1 || state == 7 || state == 8
            if (isWordStart) {
                applyFrequentWordsIfUncertain(finalScores, profile, prefix, prefixLen, multiplier)
            } else if (prefixLen > 0) {
                applyPersonalPrefixBoost(finalScores, profile, prefix, prefixLen, multiplier)
            }
        }
    }

    // ═══════════════════════════════════════
    // System 1: Personal Word Pairs
    // ═══════════════════════════════════════

    private fun applyPersonalWordPairs(
        finalScores: HashMap<String, Double>,
        activeWordsArray: List<String>,
        profile: PersonalProfile,
        prefix: String,
        prefixLen: Int,
        multiplier: Double
    ) {
        if (activeWordsArray.isEmpty()) return

        val cleanWords = activeWordsArray.map {
            it.lowercase().replace(Regex("[^a-z0-9'.-]"), "").trim('.', '-', '\'')
        }.filter { it.isNotEmpty() }
        if (cleanWords.isEmpty()) return

        var found = false

        // Try trigram first (2-word history)
        if (cleanWords.size >= 2) {
            val w1 = cleanWords[cleanWords.size - 2]
            val w2 = cleanWords[cleanWords.size - 1]
            val triKey = "${w1}_${w2}"
            val triEntry = profile.trigram[triKey]
            if (triEntry != null) {
                for ((nextWord, count) in triEntry) {
                    if (nextWord.length > prefixLen && (prefixLen == 0 || nextWord.lowercase().startsWith(prefix))) {
                        val targetChar = nextWord[prefixLen].lowercase()
                        if (isScorableChar(targetChar)) {
                            val bonus = countToTrigramBonus(count) * multiplier
                            if (bonus > 0.0) {
                                finalScores[targetChar] = (finalScores[targetChar] ?: 0.0) + bonus
                                found = true
                            }
                        }
                    }
                }
            }
        }

        // Fallback to bigram (1-word history) if trigram didn't fire
        if (!found && cleanWords.isNotEmpty()) {
            val w1 = cleanWords.last()
            val biEntry = profile.bigram[w1]
            if (biEntry != null) {
                for ((nextWord, count) in biEntry) {
                    if (nextWord.length > prefixLen && (prefixLen == 0 || nextWord.lowercase().startsWith(prefix))) {
                        val targetChar = nextWord[prefixLen].lowercase()
                        if (isScorableChar(targetChar)) {
                            val bonus = countToBigramBonus(count) * multiplier
                            if (bonus > 0.0) {
                                finalScores[targetChar] = (finalScores[targetChar] ?: 0.0) + bonus
                            }
                        }
                    }
                }
            }
        }
    }

    private fun countToTrigramBonus(count: Int): Double = when {
        count >= 16 -> TRI_MAX
        count >= 6  -> TRI_HIGH
        count >= 3  -> TRI_MID
        count >= 1  -> TRI_LOW
        else        -> 0.0
    }

    private fun countToBigramBonus(count: Int): Double = when {
        count >= 16 -> BI_MAX
        count >= 6  -> BI_HIGH
        count >= 3  -> BI_MID
        count >= 1  -> BI_LOW
        else        -> 0.0
    }

    // ═══════════════════════════════════════
    // System 2: Uncertain Gap Boosting
    // ═══════════════════════════════════════

    private fun applyFrequentWordsIfUncertain(
        finalScores: HashMap<String, Double>,
        profile: PersonalProfile,
        prefix: String,
        prefixLen: Int,
        multiplier: Double
    ) {
        if (profile.wordFreq.isEmpty()) return

        // Compute gap between top 2 candidate scores
        val sorted = finalScores.values.sortedDescending()
        if (sorted.size < 2) return
        val gap = sorted[0] - sorted[1]
        val gapLimit = repo.personalizationUncertaintyGap
        if (gap >= gapLimit) return  // Engine is confident → no boost needed

        // Boost frequent personal words (top 30 by frequency)
        val topWords = profile.wordFreq.entries
            .sortedByDescending { it.value }
            .take(30)

        for ((word, count) in topWords) {
            if (word.length <= prefixLen) continue
            if (prefixLen > 0 && !word.lowercase().startsWith(prefix)) continue
            val targetChar = word[prefixLen].lowercase()
            if (isScorableChar(targetChar)) {
                val bonus = countToStartFreqBonus(count) * multiplier
                if (bonus > 0.0) {
                    finalScores[targetChar] = (finalScores[targetChar] ?: 0.0) + bonus
                }
            }
        }
    }

    /**
     * Boosts next character when typing a prefix that matches a user's learned OOV or frequent personal word.
     */
    private fun applyPersonalPrefixBoost(
        finalScores: HashMap<String, Double>,
        profile: PersonalProfile,
        prefix: String,
        prefixLen: Int,
        multiplier: Double
    ) {
        if (prefixLen == 0) return

        // 1. Check learned OOV words (direct personal vocabulary)
        val oovMultiplier = repo.personalizationOOVMultiplier
        for (word in profile.learnedOOV) {
            if (word.length > prefixLen && word.lowercase().startsWith(prefix)) {
                val targetChar = word[prefixLen].lowercase()
                if (isScorableChar(targetChar)) {
                    val count = profile.wordFreq[word.lowercase()] ?: 1
                    val bonus = countToPrefixBonus(count) * multiplier * oovMultiplier
                    if (bonus > 0.0) {
                        finalScores[targetChar] = (finalScores[targetChar] ?: 0.0) + bonus
                    }
                }
            }
        }

        // 2. Check frequent personal words
        if (profile.wordFreq.isNotEmpty()) {
            val topWords = profile.wordFreq.entries
                .sortedByDescending { it.value }
                .take(30)

            for ((word, count) in topWords) {
                if (word.length <= prefixLen) continue
                if (!word.lowercase().startsWith(prefix)) continue
                val targetChar = word[prefixLen].lowercase()
                if (isScorableChar(targetChar)) {
                    val bonus = countToPrefixBonus(count) * multiplier
                    if (bonus > 0.0) {
                        finalScores[targetChar] = (finalScores[targetChar] ?: 0.0) + bonus
                    }
                }
            }
        }
    }

    /**
     * Digits MUST NOT receive score bonuses on the keyboard layout (digits stay strictly on swipe-down).
     * Supported symbols in masterLayout (e.g. '-', '.', ''') CAN receive score bonuses to compete for the TAP slot.
     */
    private fun isScorableChar(targetChar: String): Boolean {
        if (targetChar.isEmpty()) return false
        if (targetChar[0].isDigit()) return false
        return repo.masterLayout.containsKey(targetChar)
    }

    private fun countToStartFreqBonus(count: Int): Double = when {
        count >= 15 -> 25.0
        count >= 6  -> 18.0
        count >= 3  -> 12.0
        count >= 1  -> 8.0
        else        -> 0.0
    }

    private fun countToPrefixBonus(count: Int): Double = when {
        count >= 15 -> FREQ_MAX
        count >= 6  -> FREQ_HIGH
        count >= 3  -> FREQ_MID
        count >= 1  -> repo.personalizationFirstTypeBonus
        else        -> 0.0
    }
}
