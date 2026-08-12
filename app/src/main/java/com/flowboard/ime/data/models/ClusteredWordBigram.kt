package com.flowboard.ime.data.models

/**
 * Represents the clustered word bigram data (clustered_word_bigram.json).
 * Used to predict the next word based on the current word context.
 */
data class ClusteredWordBigram(
    val groups: Map<String, List<Int>>,
    val bigram: Map<String, WordBigramEntry>
) {
    companion object {
        val EMPTY = ClusteredWordBigram(emptyMap(), emptyMap())
    }
}

sealed class WordBigramEntry {
    data class DirectList(val ids: List<Int>) : WordBigramEntry()
    data class GroupRef(val group: String, val extras: List<Int> = emptyList()) : WordBigramEntry() {
        val extra: Int? get() = extras.firstOrNull()
    }
}
