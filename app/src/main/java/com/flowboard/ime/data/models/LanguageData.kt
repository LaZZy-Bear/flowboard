package com.flowboard.ime.data.models

/**
 * Container for all data specific to a single language.
 */
data class LanguageData(
    val lang: String,
    val layoutStrategy: String,
    val unigram: List<String>,
    val bigram: Map<String, List<String>>,
    val trigram: Map<String, List<String>>,
    val masterLayout: Map<String, MasterLayoutEntry>,
    val trieDict: TrieNode,
    val wordList: List<String>,
    val wordReverseMap: Map<String, Int>,
    val clusteredBigram: ClusteredWordBigram,
    val spaceNgram: Map<String, List<String>>,
    val defaultProfile: Profile?,
    val chatProfile: Profile?
)
