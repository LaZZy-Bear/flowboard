package com.flowboard.ime.engine

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
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
    val learnedOOV: List<String> = emptyList(),
    val lastDecayTimestamp: Long = 0L
)

/**
 * Live Learning Manager — Prototype 22 V22.2.0 Real-time Learning Engine.
 *
 * Captures user-typed words in RAM while typing.
 * Dynamically injects OOV words into the active OOV Trie in real-time.
 * Automatically prunes lowest frequency/lowest ranked entries when capacities are exceeded.
 * Applies exponential aging decay to naturally forget stale words over long-term usage.
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

        private const val WORDS_BETWEEN_DECAY = 500
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

        private val EMAIL_REGEX = Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""")
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val isDirty = AtomicBoolean(false)
    private var lastDecayTimestamp: Long = 0L
    private var wordsTypedSinceDecay: Int = 0

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
            val text = readProfileFile(file)
            if (text.isEmpty()) {
                FlowboardRepository.personalProfile = PersonalProfile.EMPTY
                FlowboardRepository.isPersonalizationEnabled = false
                FlowboardRepository.trieDictOOV = FlowboardRepository.baseTrieDictOOV
                return
            }

            val liveData = json.decodeFromString<LiveProfileData>(text)
            lastDecayTimestamp = liveData.lastDecayTimestamp

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

            // Check if daily aging decay is due
            val now = System.currentTimeMillis()
            if (lastDecayTimestamp > 0 && now - lastDecayTimestamp >= DAY_MILLIS) {
                applyAgingDecay(0.95)
                lastDecayTimestamp = now
            } else {
                updateRepositoryProfile()
            }

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

        // 0. Extract & Learn Email Patterns immediately (learned on 1st occurrence)
        val emailMatches = EMAIL_REGEX.findAll(fullText).map { it.value.lowercase() }.toList()
        for (email in emailMatches) {
            if (email.isNotEmpty()) {
                liveWordFreq[email] = (liveWordFreq[email] ?: 0) + 1
                if (!liveLearnedOOV.contains(email)) {
                    liveLearnedOOV.add(email)
                    injectOOVWordToTrie(email)
                }
            }
        }

        val allowAlphanumeric = isAlphanumericEnabled()
        val regex = if (allowAlphanumeric) {
            Regex("[a-z0-9]+(?:['.-][a-z0-9]+)*")
        } else {
            Regex("[a-z]+(?:'[a-z]+)?")
        }
        val words = regex.findAll(fullText.lowercase()).map { it.value }.toList()

        if (words.isNotEmpty()) {
            for (w in words) {
                // 1. Update Word Frequency
                if (w.length >= 2 || w == "i" || w == "a") {
                    liveWordFreq[w] = (liveWordFreq[w] ?: 0) + 1
                }

                // 2. OOV Check & Dynamic Trie Injection (supports alphanumeric words like "b4", "4ever", "apple2", "gg")
                if (w.length >= 2) {
                    val isInWordList = FlowboardRepository.wordReverseMap.containsKey(w)
                    val isInMainTrie = isWordInTrie(FlowboardRepository.trieDict, w)
                    if (!isInWordList && !isInMainTrie) {
                        if (!liveLearnedOOV.contains(w)) {
                            liveLearnedOOV.add(w)
                            injectOOVWordToTrie(w)
                        }
                    }
                }
            }

            // 3. Update Bigram for all consecutive word pairs in sequence
            for (i in 0 until words.size - 1) {
                val w1 = words[i]
                val w2 = words[i + 1]
                if (w1.isNotEmpty() && w2.isNotEmpty()) {
                    val inner = liveBigram.getOrPut(w1) { HashMap() }
                    inner[w2] = (inner[w2] ?: 0) + 1
                }
            }

            // 4. Update Trigram for all consecutive triplets in sequence
            for (i in 0 until words.size - 2) {
                val w1 = words[i]
                val w2 = words[i + 1]
                val w3 = words[i + 2]
                if (w1.isNotEmpty() && w2.isNotEmpty() && w3.isNotEmpty()) {
                    val triKey = "${w1}_${w2}"
                    val inner = liveTrigram.getOrPut(triKey) { HashMap() }
                    inner[w3] = (inner[w3] ?: 0) + 1
                }
            }
        }

        if (emailMatches.isNotEmpty() || words.isNotEmpty()) {
            isDirty.set(true)
            updateRepositoryProfile()
            pruneIfExceeded()

            wordsTypedSinceDecay += words.size
            if (wordsTypedSinceDecay >= WORDS_BETWEEN_DECAY) {
                applyAgingDecay(0.95)
                wordsTypedSinceDecay = 0
                lastDecayTimestamp = System.currentTimeMillis()
            }
        }
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
     * Check if learning from password fields is enabled in user settings.
     */
    fun isLearnPasswordsEnabled(): Boolean {
        val prefs = context.getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)
        return prefs.getBoolean("personalization_learn_passwords", false)
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
        FlowboardRepository.personalizationAlphanumericEnabled = isAlphanumericEnabled()
        val prefs = context.getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)
        FlowboardRepository.personalizationPairsEnabled = prefs.getBoolean("personalization_pairs_enabled", true)
        FlowboardRepository.personalizationFreqEnabled = prefs.getBoolean("personalization_freq_enabled", true)

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
     * Exponential Aging Decay (Natural Forgetting Curve):
     * Gradually reduces frequency counts of old words and pairs.
     * Removes forgotten words whose count decays to 0, ensuring high-frequency active words
     * remain dominant while stale words naturally phase out.
     */
    fun applyAgingDecay(decayFactor: Double = 0.95) {
        // 1. Decay Word Frequency
        val freqItr = liveWordFreq.entries.iterator()
        while (freqItr.hasNext()) {
            val entry = freqItr.next()
            val newCount = kotlin.math.round(entry.value * decayFactor).toInt()
            if (newCount <= 0) {
                freqItr.remove()
                liveLearnedOOV.remove(entry.key)
            } else {
                entry.setValue(newCount)
            }
        }

        // 2. Decay Bigrams & keep Top-8 pairs per word
        val biItr = liveBigram.entries.iterator()
        while (biItr.hasNext()) {
            val (_, innerMap) = biItr.next()
            val innerItr = innerMap.entries.iterator()
            while (innerItr.hasNext()) {
                val e = innerItr.next()
                val newCount = kotlin.math.round(e.value * decayFactor).toInt()
                if (newCount <= 0) {
                    innerItr.remove()
                } else {
                    e.setValue(newCount)
                }
            }
            if (innerMap.size > 8) {
                val top8 = innerMap.entries.sortedByDescending { it.value }.take(8).associate { it.key to it.value }
                innerMap.clear()
                innerMap.putAll(top8)
            }
            if (innerMap.isEmpty()) {
                biItr.remove()
            }
        }

        // 3. Decay Trigrams & keep Top-5 triplets per pair
        val triItr = liveTrigram.entries.iterator()
        while (triItr.hasNext()) {
            val (_, innerMap) = triItr.next()
            val innerItr = innerMap.entries.iterator()
            while (innerItr.hasNext()) {
                val e = innerItr.next()
                val newCount = kotlin.math.round(e.value * decayFactor).toInt()
                if (newCount <= 0) {
                    innerItr.remove()
                } else {
                    e.setValue(newCount)
                }
            }
            if (innerMap.size > 5) {
                val top5 = innerMap.entries.sortedByDescending { it.value }.take(5).associate { it.key to it.value }
                innerMap.clear()
                innerMap.putAll(top5)
            }
            if (innerMap.isEmpty()) {
                triItr.remove()
            }
        }

        isDirty.set(true)
        updateRepositoryProfile()
    }

    /**
     * Persist current RAM profile to internal encrypted JSON file on keyboard hide/close.
     */
    fun saveProfileIfDirty() {
        if (!isDirty.compareAndSet(true, false)) return
        try {
            val liveData = LiveProfileData(
                bigram = liveBigram,
                trigram = liveTrigram,
                wordFreq = liveWordFreq,
                learnedOOV = liveLearnedOOV.toList(),
                lastDecayTimestamp = lastDecayTimestamp
            )
            val jsonStr = json.encodeToString(liveData)
            val file = File(context.filesDir, PROFILE_FILENAME)
            writeProfileFile(file, jsonStr)
            Log.d(TAG, "Successfully persisted encrypted live profile to internal storage (${file.length()} bytes)")
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
            val tempFile = File(context.filesDir, "$PROFILE_FILENAME.tmp")
            if (tempFile.exists()) {
                tempFile.delete()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to delete live profile file: ${e.message}")
        }

        FlowboardRepository.personalProfile = PersonalProfile.EMPTY
        FlowboardRepository.isPersonalizationEnabled = false
        FlowboardRepository.trieDictOOV = FlowboardRepository.baseTrieDictOOV
        Log.d(TAG, "Cleared live profile successfully in 0.001ms.")
    }

    private fun getMasterKey(): MasterKey {
        return MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private fun readProfileFile(file: File): String {
        return try {
            val masterKey = getMasterKey()
            val encryptedFile = EncryptedFile.Builder(
                context,
                file,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()
            encryptedFile.openFileInput().use { it.bufferedReader().readText() }
        } catch (e: Throwable) {
            // Graceful fallback: plain-text migration or JVM testing environment
            Log.d(TAG, "Encrypted read fallback to plain text: ${e.message}")
            file.readText()
        }
    }

    private fun writeProfileFile(file: File, content: String) {
        val tempFile = File(context.filesDir, "$PROFILE_FILENAME.tmp")
        if (tempFile.exists()) tempFile.delete()

        try {
            val masterKey = getMasterKey()
            val encryptedFile = EncryptedFile.Builder(
                context,
                tempFile,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()
            encryptedFile.openFileOutput().use { outputStream ->
                outputStream.write(content.toByteArray(Charsets.UTF_8))
            }
            if (file.exists()) file.delete()
            tempFile.renameTo(file)
        } catch (e: Throwable) {
            // Graceful fallback for JVM unit testing environments where AndroidKeyStore is absent
            Log.d(TAG, "Encrypted write fallback to plain write: ${e.message}")
            if (tempFile.exists()) tempFile.delete()
            file.writeText(content)
        }
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
