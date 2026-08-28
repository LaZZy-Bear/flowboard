package com.flowboard.ime.data.models

import kotlinx.serialization.Serializable

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
@Serializable
data class EngineWeights(
    var U: Int = 0,
    var B: Int = 0,
    var T: Int = 0,
    var D: Int = 0,
    var WB: Int = 0,
    var WT: Int = 0,
    var STC: Int = 0,
    val status: String? = null
) {
    /**
     * Create a mutable copy of these weights.
     */
    fun mutableCopy(): EngineWeights = copy()
}
