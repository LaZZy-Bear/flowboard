package com.flowboard.ime.data

import com.flowboard.ime.data.models.ClusteredWordBigram
import com.flowboard.ime.data.models.KeySlots
import com.flowboard.ime.data.models.MasterLayoutEntry
import com.flowboard.ime.data.models.PersonalProfile
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
 * English-only (Prototype 22 engine). Multi-language support removed.
 */
object FlowboardRepository {

    // ══════════════════════════════════════════
    // English Language Data
    // ══════════════════════════════════════════
    @Volatile var unigram: List<String> = emptyList()
    @Volatile var unigramStart: List<String> = emptyList()       // Sentence-starting char frequencies (State 1)
    @Volatile var masterLayout: Map<String, MasterLayoutEntry> = emptyMap()
    @Volatile var defaultMasterLayout: Map<String, MasterLayoutEntry> = emptyMap()
    @Volatile var customStateWeights: Map<Int, com.flowboard.ime.data.models.EngineWeights>? = null
    @Volatile var lazyTapRatio: Double = 1.15
    @Volatile var partnerTapRatio: Double = 1.35
    @Volatile var bigram: Map<String, List<String>> = emptyMap()
    @Volatile var trigram: Map<String, List<String>> = emptyMap()
    @Volatile var trieDict: TrieNode? = null
    @Volatile var trieDictOOV: TrieNode? = null                  // Secondary OOV fallback trie
    @Volatile var baseTrieDictOOV: TrieNode? = null              // Immutable base for OOV trie (for resetting)
    @Volatile var wordList: List<String> = emptyList()
    @Volatile var wordReverseMap: Map<String, Int> = emptyMap()
    @Volatile var clusteredBigram: ClusteredWordBigram = ClusteredWordBigram.EMPTY
    @Volatile var clusteredTrigram: ClusteredWordBigram = ClusteredWordBigram.EMPTY  // Word Trigram (2-word history)
    @Volatile var sentenceTopicClusters: SentenceTopicClusters = SentenceTopicClusters.EMPTY

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun reloadAdvancedTuning(context: android.content.Context) {
        val prefs = context.getSharedPreferences("flowboard_settings", android.content.Context.MODE_PRIVATE)
        lazyTapRatio = prefs.getFloat("lazy_tap_ratio", 1.15f).toDouble()
        partnerTapRatio = prefs.getFloat("partner_tap_ratio", 1.35f).toDouble()

        if (defaultMasterLayout.isEmpty()) {
            try {
                defaultMasterLayout = AssetLoader(context).loadMasterLayout()
            } catch (e: Exception) {
                android.util.Log.e("FlowboardRepo", "Failed to load default master layout from assets", e)
            }
        }

        // Master Layout Override
        val customLayoutJson = prefs.getString("custom_master_layout_json", null)?.trim()
        if (!customLayoutJson.isNullOrBlank() && customLayoutJson != "{}") {
            try {
                val parsed = json.decodeFromString<Map<String, MasterLayoutEntry>>(customLayoutJson)
                if (parsed.isNotEmpty()) {
                    val sanitized = mutableMapOf<String, MasterLayoutEntry>()
                    for ((rawChar, entry) in parsed) {
                        val codePoints = rawChar.trim().codePoints().toArray()
                        val singleChar = if (codePoints.isNotEmpty()) String(codePoints, 0, 1) else rawChar.trim().take(1)
                        if (singleChar.isNotEmpty() && !sanitized.containsKey(singleChar)) {
                            sanitized[singleChar] = entry
                        }
                    }
                    if (sanitized.isNotEmpty()) {
                        masterLayout = sanitized
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("FlowboardRepo", "Error decoding custom master layout", e)
            }
        } else if (defaultMasterLayout.isNotEmpty()) {
            masterLayout = defaultMasterLayout
        }

        // Custom State Weights Override
        val customWeightsJson = prefs.getString("custom_state_weights_json", null)?.trim()
        if (!customWeightsJson.isNullOrBlank() && customWeightsJson != "{}") {
            try {
                val parsed = json.decodeFromString<Map<String, com.flowboard.ime.data.models.EngineWeights>>(customWeightsJson)
                val intKeyMap = parsed.mapNotNull { entry ->
                    val k = entry.key.toIntOrNull()
                    if (k != null) k to entry.value else null
                }.toMap()
                if (intKeyMap.isNotEmpty()) {
                    customStateWeights = intKeyMap
                }
            } catch (e: Exception) {
                android.util.Log.e("FlowboardRepo", "Error decoding custom state weights", e)
            }
        } else {
            customStateWeights = null
        }
    }

    // ══════════════════════════════════════════
    // Shared Data
    // ══════════════════════════════════════════
    @Volatile var symbolPage1: Map<String, KeySlots> = emptyMap()
    @Volatile var symbolPage2: Map<String, KeySlots> = emptyMap()

    // ══════════════════════════════════════════
    // Personalization
    // ══════════════════════════════════════════
    @Volatile var personalProfile: PersonalProfile = PersonalProfile.EMPTY
    @Volatile var isPersonalizationEnabled: Boolean = false
    @Volatile var personalizationBoostMultiplier: Double = 1.0
    @Volatile var personalizationPairsEnabled: Boolean = true
    @Volatile var personalizationFreqEnabled: Boolean = true
    @Volatile var personalizationAlphanumericEnabled: Boolean = true
    @Volatile var personalizationLearnPasswordsEnabled: Boolean = false
    @Volatile var personalizationOOVMultiplier: Double = 1.3
    @Volatile var personalizationFirstTypeBonus: Double = 30.0
    @Volatile var personalizationUncertaintyGap: Double = 15.0

    // ══════════════════════════════════════════
    // Active Profile
    // ══════════════════════════════════════════
    @Volatile var activeProfile: Profile = Profile.DEFAULT
    @Volatile var bonusDict: Map<String, Double> = emptyMap()

    // ══════════════════════════════════════════
    // Sticky Key State
    // ══════════════════════════════════════════
    var lastActionKeyId: String? = null
    var lastActionSlot: String? = null
    var lastActionChar: String? = null
    var stickyChar: String? = null

    // ══════════════════════════════════════════
    // Loading State
    // ══════════════════════════════════════════
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isFullyLoaded = MutableStateFlow(false)
    @Suppress("unused")
    val isFullyLoaded: StateFlow<Boolean> = _isFullyLoaded.asStateFlow()

    fun markReady() {
        _isReady.value = true
    }

    fun markFullyLoaded() {
        _isFullyLoaded.value = true
    }

    fun reset() {
        unigram = emptyList()
        unigramStart = emptyList()
        masterLayout = emptyMap()
        bigram = emptyMap()
        trigram = emptyMap()
        trieDict = null
        trieDictOOV = null
        baseTrieDictOOV = null
        wordList = emptyList()
        wordReverseMap = emptyMap()
        clusteredBigram = ClusteredWordBigram.EMPTY
        clusteredTrigram = ClusteredWordBigram.EMPTY
        sentenceTopicClusters = SentenceTopicClusters.EMPTY

        symbolPage1 = emptyMap()
        symbolPage2 = emptyMap()

        personalProfile = PersonalProfile.EMPTY
        isPersonalizationEnabled = false

        activeProfile = Profile.DEFAULT
        bonusDict = emptyMap()

        lastActionKeyId = null
        lastActionSlot = null
        lastActionChar = null
        stickyChar = null

        _isReady.value = false
        _isFullyLoaded.value = false
    }
}

/**
 * Sentence topic cluster data loaded from sentence_topic_clusters.json.
 * Used by the STC sub-engine (State 8 - Connector Spacebar).
 *
 * @property type     Format type ("detailed_top9" or cluster-mapped)
 * @property wordMap  Optional: word-ID → cluster-ID mapping (for compact format)
 * @property clusters Map of word-ID (or cluster-ID) → list of related word IDs (top-9)
 */
data class SentenceTopicClusters(
    val type: String = "",
    val wordMap: Map<String, Int>? = null,
    val clusters: Map<String, List<Int>> = emptyMap()
) {
    companion object {
        val EMPTY = SentenceTopicClusters()
    }

    val isEmpty: Boolean get() = clusters.isEmpty()
}
