package com.flowboard.ime.data

import com.flowboard.ime.data.models.MasterKey
import com.flowboard.ime.data.models.Profile
import com.flowboard.ime.data.models.TrieNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton repository that holds all loaded data in RAM.
 * Acts as the single source of truth for the Scoring Engine, Layout Manager,
 * and all other components.
 *
 * Data is loaded progressively in 3 phases:
 * - [isReady] becomes true after critical data (Phase A) is loaded
 * - [isFullyLoaded] becomes true after all data (Phase C) is loaded
 *
 * The keyboard can render and function as soon as [isReady] is true,
 * using Unigram-only scoring as a fallback until more data arrives.
 */
object FlowboardRepository {

    // ══════════════════════════════════════════
    // Phase A: Critical Data (loaded before keyboard renders)
    // ══════════════════════════════════════════

    /** Ranked list of Thai characters by frequency (most common first) */
    var unigram: List<String> = emptyList()

    /** Thai character → category tag mapping (e.g., "ก" → "C", "เ" → "Vp") */
    var charMap: Map<String, String> = emptyMap()

    /** Key assignments: key_1..key_9 → MasterKey(main, alts) */
    var masterLayout: Map<String, MasterKey> = emptyMap()

    /** Context tag pair → list of penalized tags (e.g., "C-Vf" → ["Vt", "Vb"]) */
    var patternPenalty: Map<String, List<String>> = emptyMap()

    // ══════════════════════════════════════════
    // Phase B: Normal Data (enhances predictions)
    // ══════════════════════════════════════════

    /** Character bigram: last_char → ranked list of likely next chars */
    var bigram: Map<String, List<String>> = emptyMap()

    /** Character trigram: last_2_chars → ranked list of likely next chars */
    var trigram: Map<String, List<String>> = emptyMap()

    /** Space n-gram: "char " → ranked list of likely next chars after space */
    var spaceNgram: Map<String, List<String>> = emptyMap()

    /** Word ID list: index → word string */
    var wordIdMap: List<String> = emptyList()

    /** Reverse mapping: word string → ID string */
    var reverseWordMap: Map<String, String> = emptyMap()

    /** Root of the dictionary trie (trie_dict.json) */
    var trieDictRoot: TrieNode? = null

    // ══════════════════════════════════════════
    // Phase C: Deferred Data (large files, loaded last)
    // ══════════════════════════════════════════

    /**
     * Hybrid word trie for word-level predictions.
     * Structure: contextId → wordId → nextWordId → frequency
     * Special key "_base" contains base word bigram data.
     */
    var hybridWordTrie: Map<String, Map<String, Map<String, Int>>> = emptyMap()

    // ══════════════════════════════════════════
    // Active Profile
    // ══════════════════════════════════════════

    /** Currently active typing profile (Default or Chat) */
    var activeProfile: Profile = Profile.DEFAULT

    /** Character bonus scores from the active profile */
    var bonusDict: Map<String, Double> = emptyMap()

    // ══════════════════════════════════════════
    // Loading State
    // ══════════════════════════════════════════

    private val _isReady = MutableStateFlow(false)
    /** True when critical data (Phase A) is loaded and keyboard can render */
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isFullyLoaded = MutableStateFlow(false)
    /** True when all data (Phases A, B, C) are loaded */
    val isFullyLoaded: StateFlow<Boolean> = _isFullyLoaded.asStateFlow()

    /**
     * Mark Phase A (critical data) as complete.
     * The keyboard view will start rendering after this.
     */
    fun markReady() {
        _isReady.value = true
    }

    /**
     * Mark all phases as complete.
     */
    fun markFullyLoaded() {
        _isFullyLoaded.value = true
    }

    /**
     * Reset all data. Used for testing or when switching languages.
     */
    fun reset() {
        unigram = emptyList()
        charMap = emptyMap()
        masterLayout = emptyMap()
        patternPenalty = emptyMap()
        bigram = emptyMap()
        trigram = emptyMap()
        spaceNgram = emptyMap()
        wordIdMap = emptyList()
        reverseWordMap = emptyMap()
        trieDictRoot = null
        hybridWordTrie = emptyMap()
        activeProfile = Profile.DEFAULT
        bonusDict = emptyMap()
        _isReady.value = false
        _isFullyLoaded.value = false
    }
}
