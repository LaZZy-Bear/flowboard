package com.flowboard.ime.data.models

/**
 * Personal typing profile loaded from my_personal_profile.json.
 * Contains user-specific word pairs, trigrams, word frequencies, and learned OOV words.
 *
 * Used by the PersonalizationEngine as an additive layer on top of the base scoring engine.
 * Zero baseline degradation guarantee: personalization scores are always additive.
 *
 * @property bigram     Map of word → next-word → count (personal word pairs)
 * @property trigram    Map of "w1_w2" → next-word → count (personal word trigrams)
 * @property wordFreq   Map of word → frequency (personal word usage counts)
 * @property learnedOOV List of user-typed OOV words that appear 3+ times
 */
data class PersonalProfile(
    val bigram: Map<String, Map<String, Int>> = emptyMap(),
    val trigram: Map<String, Map<String, Int>> = emptyMap(),
    val wordFreq: Map<String, Int> = emptyMap(),
    val learnedOOV: List<String> = emptyList()
) {
    companion object {
        val EMPTY = PersonalProfile()
    }

    val isEmpty: Boolean
        get() = bigram.isEmpty() && wordFreq.isEmpty() && learnedOOV.isEmpty()
}
