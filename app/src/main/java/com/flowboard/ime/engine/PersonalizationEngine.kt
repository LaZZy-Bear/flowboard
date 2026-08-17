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
 * Learned OOV injection is handled by AssetLoader at startup (not at runtime here).
 */
class PersonalizationEngine(private val repo: FlowboardRepository) {

    companion object {
        // Pair bonus levels (count threshold → score bonus)
        private const val PAIR_HIGH = 1.5
        private const val PAIR_MID = 0.8
        private const val PAIR_LOW = 0.3

        // Frequency bonus levels
        private const val FREQ_MAX = 2.0
        private const val FREQ_HIGH = 1.2
        private const val FREQ_MID = 0.5
        private const val FREQ_LOW = 0.2

        // Uncertainty gap: if top-2 gap < this, apply frequent-words boost
        private const val UNCERTAINTY_GAP = 5.0
    }

    /**
     * Apply personalization bonuses to [finalScores].
     * Only active in word-start states (1, 7, 8).
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

        // System 2: Uncertain gap — boost frequent personal words in word-start states
        val isWordStart = state == 1 || state == 7 || state == 8
        if (isWordStart && repo.personalizationFreqEnabled) {
            applyFrequentWordsIfUncertain(finalScores, profile, prefix, prefixLen, multiplier)
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
            it.lowercase().replace(Regex("[^a-z0-9']"), "")
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
                        // Digits do not receive score bonuses on keyboard layout
                        if (targetChar.isNotEmpty() && targetChar[0] in 'a'..'z') {
                            val bonus = countToPairBonus(count) * multiplier
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
                        // Digits do not receive score bonuses on keyboard layout
                        if (targetChar.isNotEmpty() && targetChar[0] in 'a'..'z') {
                            val bonus = countToPairBonus(count) * multiplier
                            if (bonus > 0.0) {
                                finalScores[targetChar] = (finalScores[targetChar] ?: 0.0) + bonus
                            }
                        }
                    }
                }
            }
        }
    }

    private fun countToPairBonus(count: Int): Double = when {
        count >= 16 -> PAIR_HIGH
        count >= 6  -> PAIR_MID
        count >= 3  -> PAIR_LOW
        count >= 1  -> PAIR_LOW * 0.7
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
        if (gap >= UNCERTAINTY_GAP) return  // Engine is confident → no boost needed

        // Boost frequent personal words (top 30 by frequency)
        val topWords = profile.wordFreq.entries
            .sortedByDescending { it.value }
            .take(30)

        for ((word, count) in topWords) {
            if (word.length <= prefixLen) continue
            if (prefixLen > 0 && !word.lowercase().startsWith(prefix)) continue
            val targetChar = word[prefixLen].lowercase()
            // Digits do not receive score bonuses on keyboard layout
            if (targetChar.isNotEmpty() && targetChar[0] in 'a'..'z') {
                val bonus = countToFreqBonus(count) * multiplier
                if (bonus > 0.0) {
                    finalScores[targetChar] = (finalScores[targetChar] ?: 0.0) + bonus
                }
            }
        }
    }

    private fun countToFreqBonus(count: Int): Double = when {
        count >= 30 -> FREQ_MAX
        count >= 15 -> FREQ_HIGH
        count >= 6  -> FREQ_MID
        count >= 3  -> FREQ_LOW
        count >= 1  -> FREQ_LOW * 0.7
        else        -> 0.0
    }
}
