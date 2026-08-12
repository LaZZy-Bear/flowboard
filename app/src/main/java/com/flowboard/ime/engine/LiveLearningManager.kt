package com.flowboard.ime.engine

import android.content.Context
import android.util.Log
import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.PersonalProfile
import com.flowboard.ime.data.models.TrieNode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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

        // Max Capacity Limits for Pruning
        private const val MAX_WORD_FREQ_ENTRIES = 1000
        private const val MAX_BIGRAM_ENTRIES = 1000
        private const val MAX_TRIGRAM_ENTRIES = 1000
        private const val MAX_OOV_ENTRIES = 500
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val isDirty = AtomicBoolean(false)

    // Mutable in-memory working maps
    private val liveWordFreq = HashMap<String, Int>()
    private val liveBigram = HashMap<String, HashMap<String, Int>>()
    private val liveTrigram = HashMap<String, HashMap<String, Int>>()
    private val liveLearnedOOV = LinkedHashSet<String>()

    /**
     * Loads saved live profile from internal storage JSON file and merges it into FlowboardRepository.
     */
    fun loadProfile() {
        try {
            val file = File(context.filesDir, PROFILE_FILENAME)
            if (!file.exists()) {
                Log.d(TAG, "No live profile file found, starting fresh.")
                return
            }
            val text = file.readText()
            if (text.isEmpty()) return

            val root = json.parseToJsonElement(text).jsonObject

            // Word Frequency
            root["wordFreq"]?.jsonObject?.forEach { (w, countEl) ->
                liveWordFreq[w] = countEl.jsonPrimitive.int
            }

            // Bigram
            root["bigram"]?.jsonObject?.forEach { (w1, innerVal) ->
                val innerMap = liveBigram.getOrPut(w1) { HashMap() }
                innerVal.jsonObject.forEach { (w2, countEl) ->
                    innerMap[w2] = countEl.jsonPrimitive.int
                }
            }

            // Trigram
            root["trigram"]?.jsonObject?.forEach { (triKey, innerVal) ->
                val innerMap = liveTrigram.getOrPut(triKey) { HashMap() }
                innerVal.jsonObject.forEach { (w3, countEl) ->
                    innerMap[w3] = countEl.jsonPrimitive.int
                }
            }

            // OOV
            root["learnedOOV"]?.jsonArray?.forEach {
                val w = it.jsonPrimitive.content
                if (w.isNotEmpty()) liveLearnedOOV.add(w)
            }

            updateRepositoryProfile()
            Log.d(TAG, "Loaded live profile: ${liveWordFreq.size} freq, ${liveBigram.size} bigram, ${liveTrigram.size} trigram, ${liveLearnedOOV.size} OOV")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load live profile: ${e.message}")
        }
    }

    /**
     * Record a newly typed word sequence when spacebar is pressed or word is committed.
     * Exact algorithm from P22 personalize.js `recordWordTyped()`.
     */
    fun recordWordTyped(fullText: String) {
        if (fullText.isEmpty()) return
        val regex = Regex("[a-z]+(?:'[a-z]+)?")
        val words = regex.findAll(fullText.lowercase()).map { it.value }.toList()
        if (words.isEmpty()) return

        val lastWord = words.last()
        if (lastWord.length < 2) return

        // 1. Update Word Frequency
        liveWordFreq[lastWord] = (liveWordFreq[lastWord] ?: 0) + 1

        // 2. Update Bigram
        if (words.size >= 2) {
            val w1 = words[words.size - 2]
            if (w1.length >= 2) {
                val inner = liveBigram.getOrPut(w1) { HashMap() }
                inner[lastWord] = (inner[lastWord] ?: 0) + 1
            }
        }

        // 3. Update Trigram
        if (words.size >= 3) {
            val w1 = words[words.size - 3]
            val w2 = words[words.size - 2]
            if (w1.length >= 2 && w2.length >= 2) {
                val triKey = "${w1}_${w2}"
                val inner = liveTrigram.getOrPut(triKey) { HashMap() }
                inner[lastWord] = (inner[lastWord] ?: 0) + 1
            }
        }

        // 4. OOV Check & Dynamic Trie Injection
        if (lastWord.length >= 3) {
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

        Log.d(TAG, "Recorded word in RAM: '$lastWord' (freq=${liveWordFreq[lastWord]}, total OOV=${liveLearnedOOV.size})")
    }

    /**
     * Dynamic OOV Injection: injects a new word directly into `repo.trieDictOOV` node tree.
     */
    private fun injectOOVWordToTrie(word: String) {
        val root = FlowboardRepository.trieDictOOV ?: TrieNode().also { FlowboardRepository.trieDictOOV = it }
        var current: TrieNode = root
        for (ch in word) {
            current = current.getOrPut(ch.toString())
        }
        current.isEndOfWord = true
    }

    private fun isWordInTrie(root: TrieNode?, word: String): Boolean {
        if (root == null || word.isEmpty()) return false
        var current: TrieNode? = root
        for (ch in word) {
            current = current?.get(ch.toString()) ?: return false
        }
        return current?.isEndOfWord == true
    }

    /**
     * Prune lowest-frequency / lowest-ranked entries if maximum capacities are exceeded.
     */
    private fun pruneIfExceeded() {
        // Prune Word Frequency
        if (liveWordFreq.size > MAX_WORD_FREQ_ENTRIES) {
            val sorted = liveWordFreq.entries.sortedBy { it.value }
            val toRemoveCount = liveWordFreq.size - MAX_WORD_FREQ_ENTRIES
            for (i in 0 until toRemoveCount) {
                liveWordFreq.remove(sorted[i].key)
            }
        }

        // Prune Bigram
        if (liveBigram.size > MAX_BIGRAM_ENTRIES) {
            val sorted = liveBigram.entries.sortedBy { it.value.values.sum() }
            val toRemoveCount = liveBigram.size - MAX_BIGRAM_ENTRIES
            for (i in 0 until toRemoveCount) {
                liveBigram.remove(sorted[i].key)
            }
        }

        // Prune Trigram
        if (liveTrigram.size > MAX_TRIGRAM_ENTRIES) {
            val sorted = liveTrigram.entries.sortedBy { it.value.values.sum() }
            val toRemoveCount = liveTrigram.size - MAX_TRIGRAM_ENTRIES
            for (i in 0 until toRemoveCount) {
                liveTrigram.remove(sorted[i].key)
            }
        }

        // Prune OOV
        if (liveLearnedOOV.size > MAX_OOV_ENTRIES) {
            val overflow = liveLearnedOOV.size - MAX_OOV_ENTRIES
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
        FlowboardRepository.personalProfile.bigram.forEach { (k, v) -> mergedBigram[k] = v }
        liveBigram.forEach { (k, v) ->
            val existing = mergedBigram[k]?.toMutableMap() ?: HashMap()
            existing.putAll(v)
            mergedBigram[k] = existing
        }

        val mergedTrigram = HashMap<String, Map<String, Int>>()
        FlowboardRepository.personalProfile.trigram.forEach { (k, v) -> mergedTrigram[k] = v }
        liveTrigram.forEach { (k, v) ->
            val existing = mergedTrigram[k]?.toMutableMap() ?: HashMap()
            existing.putAll(v)
            mergedTrigram[k] = existing
        }

        val mergedFreq = HashMap<String, Int>()
        FlowboardRepository.personalProfile.wordFreq.forEach { (k, v) -> mergedFreq[k] = v }
        mergedFreq.putAll(liveWordFreq)

        val mergedOOV = (FlowboardRepository.personalProfile.learnedOOV + liveLearnedOOV).distinct()

        FlowboardRepository.personalProfile = PersonalProfile(
            bigram = mergedBigram,
            trigram = mergedTrigram,
            wordFreq = mergedFreq,
            learnedOOV = mergedOOV
        )
        FlowboardRepository.isPersonalizationEnabled = true

        // Ensure all OOV words are injected into trieDictOOV
        for (oovWord in mergedOOV) {
            injectOOVWordToTrie(oovWord)
        }
    }

    /**
     * Persist current RAM profile to internal JSON file on keyboard hide/close.
     */
    fun saveProfileIfDirty() {
        if (!isDirty.compareAndSet(true, false)) return
        try {
            val profileMap = mapOf(
                "bigram" to liveBigram,
                "trigram" to liveTrigram,
                "wordFreq" to liveWordFreq,
                "learnedOOV" to liveLearnedOOV.toList()
            )
            val jsonStr = json.encodeToString(profileMap)
            val file = File(context.filesDir, PROFILE_FILENAME)
            file.writeText(jsonStr)
            Log.d(TAG, "Successfully persisted live profile to internal storage (${file.length()} bytes)")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save live profile: ${e.message}")
            isDirty.set(true)
        }
    }
}
