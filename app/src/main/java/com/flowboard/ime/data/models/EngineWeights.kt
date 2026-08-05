package com.flowboard.ime.data.models

/**
 * Weight distribution for the 7 sub-engines in the P22 scoring system.
 * Each state has different weight values to prioritize different prediction strategies.
 *
 * @property U   Unigram weight (base character frequency)
 * @property B   Bigram weight (previous 1 character context)
 * @property T   Trigram weight (previous 2 characters context)
 * @property D   Dictionary/Trie weight (prefix matching)
 * @property WB  Word Bigram weight (next word prediction from 1 previous word)
 * @property WT  Word Trigram weight (next word prediction from 2 previous words)
 * @property STC Sentence Topic Cluster weight (domain co-occurrence after connectors)
 */
data class EngineWeights(
    var U: Int,
    var B: Int,
    var T: Int,
    var D: Int,
    var WB: Int,
    var WT: Int,
    var STC: Int
) {
    /**
     * Create a mutable copy of these weights.
     */
    fun mutableCopy(): EngineWeights = copy()
}
