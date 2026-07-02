package com.flowboard.ime.data.models

/**
 * Weight distribution for the 6 sub-engines in the scoring system.
 * Each state has different weight values to prioritize different prediction strategies.
 *
 * @property U  Unigram weight (base character frequency)
 * @property B  Bigram weight (previous 1 character context)
 * @property T  Trigram weight (previous 2 characters context)
 * @property D  Dictionary/Trie weight (prefix matching)
 * @property WB Word Bigram weight (word-level prediction)
 * @property SN Space N-gram weight (character after space)
 */
data class EngineWeights(
    var U: Int,
    var B: Int,
    var T: Int,
    var D: Int,
    var WB: Int,
    var SN: Int
) {
    /**
     * Create a mutable copy of these weights.
     */
    fun mutableCopy(): EngineWeights = copy()
}
