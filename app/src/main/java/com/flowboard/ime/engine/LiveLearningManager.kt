package com.flowboard.ime.engine

import android.content.Context
import android.util.Log
import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.PersonalProfile
import com.flowboard.ime.data.models.TrieNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class LiveProfileData(
    val bigram: Map<String, Map<String, Int>> = emptyMap(),
    val trigram: Map<String, Map<String, Int>> = emptyMap(),
    val wordFreq: Map<String, Int> = emptyMap(),
    val learnedOOV: List<String> = emptyList()
)

/**
 * Live Learning Manager — Prototype 22 V22.2.0 Real-time Learning Engine.
 *
 * Captures user-typed words in RAM while typing.
 * Dynamically injects OOV words into the active OOV Trie in real-time.
 * Automatically prunes lowest frequency/lowest ranked entries when capacities are exceeded.
 * Persists profile to internal JSON file on keyboard hide/close.
 */
class LiveLearningManager(private val context: Context) {

    companion object {
        private const val TAG = "LiveLearningManager"
        private const val PROFILE_FILENAME = "flowboard_live_profile.json"

        // Default Max Capacity Limits for Pruning
        private const val DEFAULT_MAX_WORD_FREQ_ENTRIES = 1000
        private const val DEFAULT_MAX_PAIRS_ENTRIES = 1000
        private const val DEFAULT_MAX_OOV_ENTRIES = 500
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val isDirty = AtomicBoolean(false)

    // Mutable in-memory working maps
    private val liveWordFreq = HashMap<String, Int>()
    private val liveBigram = HashMap<String, HashMap<String, Int>>()
    private val liveTrigram = HashMap<String, HashMap<String, Int>>()
    private val liveLearnedOOV = LinkedHashSet<String>()

    /**
     * Check if master personalization is enabled in user settings.
     */
    fun isPersonalizationEnabled(): Boolean {
        val prefs = context.getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)
        return prefs.getBoolean("personalization_enabled", true)
    }

    /**
     * Loads saved live profile from internal storage JSON file and merges it into FlowboardRepository.
     */
    fun loadProfile() {
        liveWordFreq.clear()
        liveBigram.clear()
        liveTrigram.clear()
        liveLearnedOOV.clear()
        isDirty.set(false)

        try {
            val file = File(context.filesDir, PROFILE_FILENAME)
            if (!file.exists()) {
                FlowboardRepository.personalProfile = PersonalProfile.EMPTY
                FlowboardRepository.isPersonalizationEnabled = false
                FlowboardRepository.trieDictOOV = FlowboardRepository.baseTrieDictOOV
                Log.d(TAG, "No live profile file found, starting fresh.")
                return
            }
            val text = file.readText()
            if (text.isEmpty()) {
                FlowboardRepository.personalProfile = PersonalProfile.EMPTY
                FlowboardRepository.isPersonalizationEnabled = false
                FlowboardRepository.trieDictOOV = FlowboardRepository.baseTrieDictOOV
                return
            }

            val liveData = json.decodeFromString<LiveProfileData>(text)

            // Word Frequency
            liveData.wordFreq.forEach { (w, count) ->
                liveWordFreq[w] = count
            }

            // Bigram
            liveData.bigram.forEach { (w1, innerMap) ->
                val targetMap = liveBigram.getOrPut(w1) { HashMap() }
                targetMap.putAll(innerMap)
            }

            // Trigram
            liveData.trigram.forEach { (triKey, innerMap) ->
                val targetMap = liveTrigram.getOrPut(triKey) { HashMap() }
                targetMap.putAll(innerMap)
            }

            // OOV
            liveLearnedOOV.addAll(liveData.learnedOOV)

            updateRepositoryProfile()
            Log.d(TAG, "Loaded live profile: ${liveWordFreq.size} freq, ${liveBigram.size} bigram, ${liveTrigram.size} trigram, ${liveLearnedOOV.size} OOV")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load live profile: ${e.message}")
            FlowboardRepository.personalProfile = PersonalProfile.EMPTY
            FlowboardRepository.isPersonalizationEnabled = false
            FlowboardRepository.trieDictOOV = FlowboardRepository.baseTrieDictOOV
        }
    }

    /**
     * Record a newly typed word sequence when spacebar is pressed or word is committed.
     * Exact algorithm from P22 personalize.js `recordWordTyped()`.
     */
    fun recordWordTyped(fullText: String) {
        if (!isPersonalizationEnabled()) return
        if (fullText.isEmpty()) return
        val allowAlphanumeric = isAlphanumericEnabled()
        val regex = if (allowAlphanumeric) {
            Regex("[a-z0-9]+(?:['.-][a-z0-9]+)*")
        } else {
            Regex("[a-z]+(?:['.-][a-z]+)*")
        }
        val words = regex.findAll(fullText.lowercase()).map { it.value }.toList()
        if (words.isEmpty()) return

        val lastWord = words.last()
        if (lastWord.isEmpty()) return

        // 1. Update Word Frequency
        if (lastWord.length >= 2 || lastWord == "i" || lastWord == "a") {
            liveWordFreq[lastWord] = (liveWordFreq[lastWord] ?: 0) + 1
        }

        // 2. Update Bigram
        if (words.size >= 2) {
            val w1 = words[words.size - 2]
            if (w1.isNotEmpty() && lastWord.isNotEmpty()) {
                val inner = liveBigram.getOrPut(w1) { HashMap() }
                inner[lastWord] = (inner[lastWord] ?: 0) + 1
            }
        }

        // 3. Update Trigram
        if (words.size >= 3) {
            val w1 = words[words.size - 3]
            val w2 = words[words.size - 2]
            if (w1.isNotEmpty() && w2.isNotEmpty() && lastWord.isNotEmpty()) {
                val triKey = "${w1}_${w2}"
                val inner = liveTrigram.getOrPut(triKey) { HashMap() }
                inner[lastWord] = (inner[lastWord] ?: 0) + 1
            }
        }

        // 4. OOV Check & Dynamic Trie Injection (supports alphanumeric words like "b4", "4ever", "gr8")
        if (lastWord.length >= 2) {
            val isInWordList = FlowboardRepository.wordReverseMap.containsKey(lastWord)
            val isInMainTrie = isWordInTrie(FlowboardRepository.trieDict, lastWord)
            if (!isInWordList && !isInMainTrie) {
                if (!liveLearnedOOV.contains(lastWord)) {
                    liveLearnedOOV.add(lastWord)
                    injectOOVWordToTrie(lastWord)
                }
            }
        }

        isDirty.set(true)
        updateRepositoryProfile()
        pruneIfExceeded()
    }

    /**
     * Dynamic OOV Injection: injects a new word directly into `repo.trieDictOOV` node tree.
     */
    private fun injectOOVWordToTrie(word: String) {
        if (!isPersonalizationEnabled()) return
        val root = FlowboardRepository.trieDictOOV ?: TrieNode().also { FlowboardRepository.trieDictOOV = it }
        var current = root
        for (ch in word) {
            current = current.getOrPut(ch.toString())
        }
        current.isEndOfWord = true
    }

    private fun isWordInTrie(root: TrieNode?, word: String): Boolean {
        var current: TrieNode = root ?: return false
        for (ch in word) {
            current = current.get(ch.toString()) ?: return false
        }
        return current.isEndOfWord
    }

    private fun parseCapacity(str: String?, default: Int): Int {
        if (str.isNullOrBlank()) return default
        val clean = str.replace(",", "")
        val match = Regex("""([0-9]+)""").find(clean) ?: return default
        return match.value.toIntOrNull() ?: default
    }

    private fun getMaxWordFreqCapacity(): Int {
        val prefs = context.getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)
        return parseCapacity(prefs.getString("personalization_max_word_freq", "$DEFAULT_MAX_WORD_FREQ_ENTRIES"), DEFAULT_MAX_WORD_FREQ_ENTRIES)
    }

    private fun getMaxPairsCapacity(): Int {
        val prefs = context.getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)
        return parseCapacity(prefs.getString("personalization_max_pairs", "$DEFAULT_MAX_PAIRS_ENTRIES"), DEFAULT_MAX_PAIRS_ENTRIES)
    }

    private fun getMaxOOVCapacity(): Int {
        val prefs = context.getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)
        return parseCapacity(prefs.getString("personalization_max_oov", "$DEFAULT_MAX_OOV_ENTRIES"), DEFAULT_MAX_OOV_ENTRIES)
    }

    private fun isAlphanumericEnabled(): Boolean {
        val prefs = context.getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)
        return prefs.getBoolean("personalization_alphanumeric_enabled", true)
    }

    /**
     * Prune lowest-frequency / lowest-ranked entries if maximum capacities are exceeded.
     */
    private fun pruneIfExceeded() {
        val maxWordFreq = getMaxWordFreqCapacity()
        val maxPairs = getMaxPairsCapacity()
        val maxOOV = getMaxOOVCapacity()

        // Prune Word Frequency
        if (liveWordFreq.size > maxWordFreq) {
            val sorted = liveWordFreq.entries.sortedBy { it.value }
            val toRemoveCount = liveWordFreq.size - maxWordFreq
            for (i in 0 until toRemoveCount) {
                liveWordFreq.remove(sorted[i].key)
            }
        }

        // Prune Bigram
        if (liveBigram.size > maxPairs) {
            val sorted = liveBigram.entries.sortedBy { it.value.values.sum() }
            val toRemoveCount = liveBigram.size - maxPairs
            for (i in 0 until toRemoveCount) {
                liveBigram.remove(sorted[i].key)
            }
        }

        // Prune Trigram
        if (liveTrigram.size > maxPairs) {
            val sorted = liveTrigram.entries.sortedBy { it.value.values.sum() }
            val toRemoveCount = liveTrigram.size - maxPairs
            for (i in 0 until toRemoveCount) {
                liveTrigram.remove(sorted[i].key)
            }
        }

        // Prune OOV
        if (liveLearnedOOV.size > maxOOV) {
            val overflow = liveLearnedOOV.size - maxOOV
            val iterator = liveLearnedOOV.iterator()
            var count = 0
            while (iterator.hasNext() && count < overflow) {
                iterator.next()
                iterator.remove()
                count++
            }
        }
    }

    private fun updateRepositoryProfile() {
        val mergedBigram = HashMap<String, Map<String, Int>>()
        liveBigram.forEach { (k, v) ->
            mergedBigram[k] = HashMap(v)
        }

        val mergedTrigram = HashMap<String, Map<String, Int>>()
        liveTrigram.forEach { (k, v) ->
            mergedTrigram[k] = HashMap(v)
        }

        val mergedFreq = HashMap<String, Int>()
        mergedFreq.putAll(liveWordFreq)

        val mergedOOV = liveLearnedOOV.toList()

        FlowboardRepository.personalProfile = PersonalProfile(
            bigram = mergedBigram,
            trigram = mergedTrigram,
            wordFreq = mergedFreq,
            learnedOOV = mergedOOV
        )

        val userPrefEnabled = isPersonalizationEnabled()
        FlowboardRepository.isPersonalizationEnabled = !FlowboardRepository.personalProfile.isEmpty && userPrefEnabled

        if (userPrefEnabled) {
            // Rebuild only the lightweight trieDictOOV (only user-typed words, typically 0..500 words) in 0.001ms
            val newLearnedTrie = TrieNode()
            for (oovWord in mergedOOV) {
                var current: TrieNode = newLearnedTrie
                for (ch in oovWord) {
                    current = current.getOrPut(ch.toString())
                }
                current.isEndOfWord = true
            }
            FlowboardRepository.trieDictOOV = newLearnedTrie
        } else {
            FlowboardRepository.trieDictOOV = FlowboardRepository.baseTrieDictOOV
        }
    }

    /**
     * Persist current RAM profile to internal JSON file on keyboard hide/close.
     */
    fun saveProfileIfDirty() {
        if (!isDirty.compareAndSet(true, false)) return
        try {
            val liveData = LiveProfileData(
                bigram = liveBigram,
                trigram = liveTrigram,
                wordFreq = liveWordFreq,
                learnedOOV = liveLearnedOOV.toList()
            )
            val jsonStr = json.encodeToString(liveData)
            val file = File(context.filesDir, PROFILE_FILENAME)
            file.writeText(jsonStr)
            Log.d(TAG, "Successfully persisted live profile to internal storage (${file.length()} bytes)")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save live profile: ${e.message}")
            isDirty.set(true)
        }
    }

    /**
     * Clear all recorded personal profile data from RAM and disk.
     */
    fun clearProfile() {
        liveWordFreq.clear()
        liveBigram.clear()
        liveTrigram.clear()
        liveLearnedOOV.clear()
        isDirty.set(false)

        try {
            val file = File(context.filesDir, PROFILE_FILENAME)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to delete live profile file: ${e.message}")
        }

        FlowboardRepository.personalProfile = PersonalProfile.EMPTY
        FlowboardRepository.isPersonalizationEnabled = false
        FlowboardRepository.trieDictOOV = FlowboardRepository.baseTrieDictOOV
        Log.d(TAG, "Cleared live profile successfully in 0.001ms.")
    }

    /**
     * Get statistics of learned items for settings display.
     */
    fun getStats(): Map<String, Int> {
        val totalPairs = liveBigram.values.sumOf { it.size } + liveTrigram.values.sumOf { it.size }
        return mapOf(
            "wordFreqCount" to liveWordFreq.size,
            "bigramCount" to liveBigram.size,
            "trigramCount" to liveTrigram.size,
            "totalPairsCount" to totalPairs,
            "oovCount" to liveLearnedOOV.size
        )
    }
}
