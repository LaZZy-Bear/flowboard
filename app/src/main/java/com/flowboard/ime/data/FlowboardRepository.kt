package com.flowboard.ime.data

import com.flowboard.ime.data.models.ClusteredWordBigram
import com.flowboard.ime.data.models.KeySlots
import com.flowboard.ime.data.models.LanguageData
import com.flowboard.ime.data.models.MasterLayoutEntry
import com.flowboard.ime.data.models.Profile
import com.flowboard.ime.data.models.TrieNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton repository that holds all loaded data in RAM.
 * Acts as the single source of truth for the Scoring Engine, Layout Manager,
 * and all other components.
 */
object FlowboardRepository {

    // ══════════════════════════════════════════
    // Language Registry
    // ══════════════════════════════════════════
    val languageRegistry: MutableMap<String, LanguageData> = mutableMapOf()
    var activeLang: String = "TH"
    var layoutStrategy: String = "TH"

    // ══════════════════════════════════════════
    // Shared Data (Language-Independent)
    // ══════════════════════════════════════════
    var charMap: Map<String, String> = emptyMap()
    var charReverseMap: Map<String, String> = emptyMap()
    var thaiCharMap: Map<String, String> = emptyMap()
    var patternPenalty: Map<String, List<String>> = emptyMap()
    
    // Symbols (Shared)
    var symbolPage1: Map<String, KeySlots> = emptyMap()
    var symbolPage2: Map<String, KeySlots> = emptyMap()

    // ══════════════════════════════════════════
    // Active Language Pointers (Data for current language)
    // ══════════════════════════════════════════
    var unigram: List<String> = emptyList()
    var masterLayout: Map<String, MasterLayoutEntry> = emptyMap()
    var bigram: Map<String, List<String>> = emptyMap()
    var trigram: Map<String, List<String>> = emptyMap()
    var trieDict: TrieNode? = null
    var wordList: List<String> = emptyList()
    var wordReverseMap: Map<String, Int> = emptyMap()
    var clusteredBigram: ClusteredWordBigram = ClusteredWordBigram.EMPTY
    var spaceNgram: Map<String, List<String>> = emptyMap()

    // ══════════════════════════════════════════
    // Active Profile
    // ══════════════════════════════════════════
    var activeProfile: Profile = Profile.DEFAULT
    var bonusDict: Map<String, Double> = emptyMap()

    // ══════════════════════════════════════════
    // Loading State
    // ══════════════════════════════════════════
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isFullyLoaded = MutableStateFlow(false)
    val isFullyLoaded: StateFlow<Boolean> = _isFullyLoaded.asStateFlow()

    fun markReady() {
        _isReady.value = true
    }

    fun markFullyLoaded() {
        _isFullyLoaded.value = true
    }

    /**
     * Switch the active language pointers.
     */
    fun setLanguage(lang: String) {
        val data = languageRegistry[lang] ?: return
        activeLang = lang
        layoutStrategy = data.layoutStrategy
        unigram = data.unigram
        masterLayout = data.masterLayout
        bigram = data.bigram
        trigram = data.trigram
        trieDict = data.trieDict
        wordList = data.wordList
        wordReverseMap = data.wordReverseMap
        clusteredBigram = data.clusteredBigram
        spaceNgram = data.spaceNgram
        
        activeProfile = data.defaultProfile ?: Profile.DEFAULT
        bonusDict = activeProfile.bonusDict
    }

    fun reset() {
        languageRegistry.clear()
        activeLang = "TH"
        layoutStrategy = "TH"
        
        charMap = emptyMap()
        charReverseMap = emptyMap()
        thaiCharMap = emptyMap()
        patternPenalty = emptyMap()
        symbolPage1 = emptyMap()
        symbolPage2 = emptyMap()
        
        unigram = emptyList()
        masterLayout = emptyMap()
        bigram = emptyMap()
        trigram = emptyMap()
        trieDict = null
        wordList = emptyList()
        wordReverseMap = emptyMap()
        clusteredBigram = ClusteredWordBigram.EMPTY
        spaceNgram = emptyMap()
        
        activeProfile = Profile.DEFAULT
        bonusDict = emptyMap()
        
        _isReady.value = false
        _isFullyLoaded.value = false
    }
}

