package com.flowboard.ime.data

import android.content.Context
import android.util.Log
import com.flowboard.ime.data.models.ClusteredWordBigram
import com.flowboard.ime.data.models.KeySlots
import com.flowboard.ime.data.models.MasterLayoutEntry
import com.flowboard.ime.data.models.PersonalProfile
import com.flowboard.ime.data.models.Profile
import com.flowboard.ime.data.models.TrieNode
import com.flowboard.ime.data.models.WordBigramEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream

/**
 * Loads all Flowboard data assets from the Android asset filesystem.
 *
 * English-only (Prototype 22). Three-phase loading:
 * - Phase A (Critical): unigram, master_layout, char_map, symbol pages → markReady()
 * - Phase B (Normal):   bigram, trigram, trie_dict, word_list, clustered_bigram, unigram_start, profile_chat
 * - Phase C (Deferred): trie_dict_oov, clustered_trigram, sentence_topic_clusters, my_personal_profile → markFullyLoaded()
 */
@Suppress("UNUSED_PARAMETER")
class AssetLoader(private val context: Context) {

    companion object {
        private const val TAG = "AssetLoader"
        private const val DIR_EN = "en"
        private const val DIR_SHARED = "shared"
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadCriticalData(repo: FlowboardRepository) = coroutineScope {
        Log.d(TAG, "Phase A: Loading critical data...")
        val startTime = System.currentTimeMillis()

        // Shared
        val charMapJob = async(Dispatchers.IO) { loadStringMap() }
        val symbolPage1Job = async(Dispatchers.IO) { loadSymbolPage("$DIR_SHARED/symbol_page_1.json") }
        val symbolPage2Job = async(Dispatchers.IO) { loadSymbolPage("$DIR_SHARED/symbol_page_2.json") }

        // EN Critical
        val unigramJob = async(Dispatchers.IO) { loadStringList("$DIR_EN/unigram.json") }
        val masterJob = async(Dispatchers.IO) { loadMasterLayout() }

        repo.charMap = charMapJob.await()
        repo.symbolPage1 = symbolPage1Job.await()
        repo.symbolPage2 = symbolPage2Job.await()
        repo.unigram = unigramJob.await()
        repo.masterLayout = masterJob.await()

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Phase A complete in ${elapsed}ms")
    }

    suspend fun loadNormalData(repo: FlowboardRepository) = coroutineScope {
        Log.d(TAG, "Phase B: Loading normal data...")
        val startTime = System.currentTimeMillis()

        val bigramJob = async(Dispatchers.IO) { loadStringListMap("$DIR_EN/bigram.json") }
        val trigramJob = async(Dispatchers.IO) { loadStringListMap("$DIR_EN/trigram.json") }
        val trieJob = async(Dispatchers.IO) { loadCompressedTrie() }
        val wordListJob = async(Dispatchers.IO) { loadStringList("$DIR_EN/word_list.json") }
        val cwbJob = async(Dispatchers.IO) { loadClusteredWordBigram("$DIR_EN/clustered_word_bigram.json") }
        val unigramStartJob = async(Dispatchers.IO) { loadStringList("$DIR_EN/unigram_start.json") }

        repo.bigram = bigramJob.await()
        repo.trigram = trigramJob.await()
        repo.trieDict = trieJob.await()

        val wordList = wordListJob.await()
        repo.wordList = wordList
        val reverseMap = HashMap<String, Int>(wordList.size)
        wordList.forEachIndexed { index, word -> if (word.isNotEmpty()) reverseMap[word] = index }
        repo.wordReverseMap = reverseMap

        repo.clusteredBigram = cwbJob.await()
        repo.unigramStart = unigramStartJob.await().ifEmpty { repo.unigram }

        // Set Profile.DEFAULT as active profile (allow_echo = false)
        repo.activeProfile = Profile.DEFAULT
        repo.bonusDict = repo.activeProfile.bonusDict

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Phase B complete in ${elapsed}ms")
    }

    suspend fun loadDeferredData(repo: FlowboardRepository) = coroutineScope {
        Log.d(TAG, "Phase C: Loading deferred data...")
        val startTime = System.currentTimeMillis()

        val trieDictOOVJob = async(Dispatchers.IO) { loadCompressedTrieOrNull() }
        val clusteredTrigramJob = async(Dispatchers.IO) { loadClusteredWordBigram("$DIR_EN/clustered_word_trigram_en.json") }
        val stcJob = async(Dispatchers.IO) { loadSentenceTopicClusters() }
        val personalProfileJob = async(Dispatchers.IO) { loadPersonalProfile() }

        val oovTrie = trieDictOOVJob.await()
        repo.trieDictOOV = oovTrie
        repo.baseTrieDictOOV = oovTrie  // Keep immutable base for personalization OOV injection

        repo.clusteredTrigram = clusteredTrigramJob.await()
        repo.sentenceTopicClusters = stcJob.await()

        val assetPersonalProfile = personalProfileJob.await()
        if (repo.personalProfile.isEmpty) {
            repo.personalProfile = assetPersonalProfile
        } else {
            val mergedBigram = HashMap<String, Map<String, Int>>()
            assetPersonalProfile.bigram.forEach { (k, v) -> mergedBigram[k] = v }
            repo.personalProfile.bigram.forEach { (k, v) ->
                val existing = mergedBigram[k]?.toMutableMap() ?: HashMap()
                existing.putAll(v)
                mergedBigram[k] = existing
            }
            val mergedTrigram = HashMap<String, Map<String, Int>>()
            assetPersonalProfile.trigram.forEach { (k, v) -> mergedTrigram[k] = v }
            repo.personalProfile.trigram.forEach { (k, v) ->
                val existing = mergedTrigram[k]?.toMutableMap() ?: HashMap()
                existing.putAll(v)
                mergedTrigram[k] = existing
            }
            val mergedFreq = HashMap<String, Int>()
            mergedFreq.putAll(assetPersonalProfile.wordFreq)
            mergedFreq.putAll(repo.personalProfile.wordFreq)
            val mergedOOV = (assetPersonalProfile.learnedOOV + repo.personalProfile.learnedOOV).distinct()

            repo.personalProfile = PersonalProfile(
                bigram = mergedBigram,
                trigram = mergedTrigram,
                wordFreq = mergedFreq,
                learnedOOV = mergedOOV
            )
        }

        updatePersonalizationState(context, repo)
        repo.markFullyLoaded()

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Phase C complete in ${elapsed}ms")
    }

    // ════════════════════════════════════════════
    // Personalization Helpers
    // ════════════════════════════════════════════

    fun updatePersonalizationState(ctx: Context, repo: FlowboardRepository) {
        val prefs = ctx.getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)
        val userPrefEnabled = prefs.getBoolean("personalization_enabled", true)
        repo.personalizationPairsEnabled = prefs.getBoolean("personalization_pairs_enabled", true)
        repo.personalizationFreqEnabled = prefs.getBoolean("personalization_freq_enabled", true)
        repo.personalizationBoostMultiplier = prefs.getString("personalization_boost_multiplier", "1.0")?.toDoubleOrNull() ?: 1.0

        if (!repo.personalProfile.isEmpty && userPrefEnabled) {
            repo.isPersonalizationEnabled = true
            injectLearnedOOVWords(repo)
            Log.d(TAG, "Personalization enabled: bigram=${repo.personalProfile.bigram.size}, freq=${repo.personalProfile.wordFreq.size}, oov=${repo.personalProfile.learnedOOV.size}, mult=${repo.personalizationBoostMultiplier}")
        } else {
            repo.isPersonalizationEnabled = false
            repo.trieDictOOV = repo.baseTrieDictOOV
            Log.d(TAG, "Personalization disabled (userPref=$userPrefEnabled, isEmpty=${repo.personalProfile.isEmpty})")
        }
    }

    /**
     * Injects learned OOV words from the personal profile into the OOV trie at runtime.
     * This adds personal vocabulary on top of the base OOV trie.
     */
    private fun injectLearnedOOVWords(repo: FlowboardRepository) {
        val learnedWords = repo.personalProfile.learnedOOV
        val oovTrie = repo.baseTrieDictOOV ?: return
        if (learnedWords.isEmpty()) return

        for (word in learnedWords) {
            if (word.isEmpty()) continue
            var node: TrieNode = oovTrie
            for (ch in word) {
                node = node.getOrPut(ch.toString())
            }
            node.isEndOfWord = true
        }

        repo.trieDictOOV = oovTrie
    }

    // ════════════════════════════════════════════
    // Low-Level JSON Parsers
    // ════════════════════════════════════════════

    private fun loadStringList(path: String): List<String> {
        return try {
            val text = readAssetText(path)
            json.decodeFromString<List<String?>>(text).map { it ?: "" }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load $path: ${e.message}")
            emptyList()
        }
    }

    private fun loadStringMap(path: String = "$DIR_SHARED/char_map.json"): Map<String, String> {
        return try {
            val text = readAssetText(path)
            json.decodeFromString<Map<String, String>>(text)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load $path: ${e.message}")
            emptyMap()
        }
    }

    private fun loadStringListMap(path: String): Map<String, List<String>> {
        return try {
            val text = readAssetText(path)
            val obj = json.parseToJsonElement(text).jsonObject
            val result = HashMap<String, List<String>>(obj.size)
            for ((key, value) in obj) {
                result[key] = value.jsonArray.map { it.jsonPrimitive.content }
            }
            result
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load $path: ${e.message}")
            emptyMap()
        }
    }

    private fun loadMasterLayout(path: String = "$DIR_EN/master_layout.json"): Map<String, MasterLayoutEntry> {
        return try {
            val text = readAssetText(path)
            json.decodeFromString<Map<String, MasterLayoutEntry>>(text)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load $path: ${e.message}")
            emptyMap()
        }
    }

    private fun loadSymbolPage(path: String): Map<String, KeySlots> {
        return try {
            val text = readAssetText(path)
            val obj = json.parseToJsonElement(text).jsonObject
            val result = mutableMapOf<String, KeySlots>()
            for ((key, value) in obj) {
                val slotsObj = value.jsonObject
                result[key] = KeySlots(
                    tap = slotsObj["tap"]?.jsonPrimitive?.content ?: "",
                    up = slotsObj["up"]?.jsonPrimitive?.content ?: "",
                    left = slotsObj["left"]?.jsonPrimitive?.content ?: "",
                    right = slotsObj["right"]?.jsonPrimitive?.content ?: "",
                    down = slotsObj["down"]?.jsonPrimitive?.content ?: ""
                )
            }
            result
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load $path: ${e.message}")
            emptyMap()
        }
    }

    private fun loadCompressedTrie(path: String = "$DIR_EN/trie_dict_compressed.json"): TrieNode {
        return try {
            val text = readAssetText(path)
            val obj = json.parseToJsonElement(text).jsonObject
            parseTrieObject(obj)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load $path: ${e.message}")
            TrieNode()
        }
    }

    private fun loadCompressedTrieOrNull(path: String = "$DIR_EN/trie_dict_oov.json"): TrieNode? {
        return try {
            val text = readAssetText(path)
            val obj = json.parseToJsonElement(text).jsonObject
            parseTrieObject(obj)
        } catch (e: Throwable) {
            Log.w(TAG, "Optional trie not loaded ($path): ${e.message}")
            null
        }
    }

    private fun parseTrieObject(obj: JsonObject): TrieNode {
        val node = TrieNode()
        for ((key, value) in obj) {
            when (key) {
                "_w" -> {
                    node.isEndOfWord = true
                    node.frequency = (value as? JsonPrimitive)?.int ?: 0
                }
                "_f" -> {
                    // frequency field (some trie formats use _f)
                    node.frequency = (value as? JsonPrimitive)?.int ?: 0
                }
                else -> {
                    val childNode = when (value) {
                        is JsonObject -> parseTrieObject(value)
                        else -> TrieNode()
                    }
                    node.children[key] = childNode
                }
            }
        }
        return node
    }

    private fun loadClusteredWordBigram(path: String): ClusteredWordBigram {
        return try {
            val text = readAssetText(path)
            val root = json.parseToJsonElement(text).jsonObject

            val groupsObj = root["groups"]?.jsonObject ?: return ClusteredWordBigram.EMPTY
            val groups = HashMap<String, List<Int>>(groupsObj.size)
            for ((k, v) in groupsObj) {
                groups[k] = v.jsonArray.map { it.jsonPrimitive.int }
            }

            // Support both "bigram" and "trigram" keys for word n-gram files
            val bigramObj = (root["bigram"] ?: root["trigram"])?.jsonObject
                ?: return ClusteredWordBigram.EMPTY
            val bigram = HashMap<String, WordBigramEntry>(bigramObj.size)
            for ((k, v) in bigramObj) {
                if (v is kotlinx.serialization.json.JsonArray) {
                    bigram[k] = WordBigramEntry.DirectList(v.map { it.jsonPrimitive.int })
                } else if (v is JsonObject) {
                    val g = v["g"]?.jsonPrimitive?.content ?: ""
                    val plusElement = v["+"]
                    val extras = when (plusElement) {
                        is kotlinx.serialization.json.JsonArray -> plusElement.map { it.jsonPrimitive.int }
                        is JsonPrimitive -> listOf(plusElement.int)
                        else -> emptyList()
                    }
                    bigram[k] = WordBigramEntry.GroupRef(g, extras)
                }
            }

            ClusteredWordBigram(groups, bigram)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load $path: ${e.message}")
            ClusteredWordBigram.EMPTY
        }
    }

    private fun loadSentenceTopicClusters(path: String = "$DIR_EN/sentence_topic_clusters.json"): SentenceTopicClusters {
        return try {
            val text = readAssetText(path)
            val root = json.parseToJsonElement(text).jsonObject

            val type = root["type"]?.jsonPrimitive?.content ?: ""

            // Parse optional wordMap (compact format)
            val wordMapObj = root["wordMap"]?.jsonObject
            val wordMap = if (wordMapObj != null) {
                val map = HashMap<String, Int>(wordMapObj.size)
                for ((k, v) in wordMapObj) {
                    map[k] = v.jsonPrimitive.int
                }
                map
            } else null

            // Parse clusters
            val clustersObj = root["clusters"]?.jsonObject ?: return SentenceTopicClusters.EMPTY
            val clusters = HashMap<String, List<Int>>(clustersObj.size)
            for ((k, v) in clustersObj) {
                clusters[k] = v.jsonArray.map { it.jsonPrimitive.int }
            }

            SentenceTopicClusters(type = type, wordMap = wordMap, clusters = clusters)
        } catch (e: Throwable) {
            Log.w(TAG, "Optional STC not loaded ($path): ${e.message}")
            SentenceTopicClusters.EMPTY
        }
    }

    private fun loadPersonalProfile(path: String = "$DIR_EN/my_personal_profile.json"): PersonalProfile {
        return try {
            val text = readAssetText(path)
            val root = json.parseToJsonElement(text).jsonObject

            // Parse bigram: Map<word, Map<nextWord, count>>
            val bigramObj = root["bigram"]?.jsonObject ?: JsonObject(emptyMap())
            val bigram = HashMap<String, Map<String, Int>>(bigramObj.size)
            for ((word, innerVal) in bigramObj) {
                val innerObj = innerVal.jsonObject
                val innerMap = HashMap<String, Int>(innerObj.size)
                for ((nextWord, count) in innerObj) {
                    innerMap[nextWord] = count.jsonPrimitive.int
                }
                bigram[word] = innerMap
            }

            // Parse trigram: Map<"w1_w2", Map<nextWord, count>>
            val trigramObj = root["trigram"]?.jsonObject ?: JsonObject(emptyMap())
            val trigram = HashMap<String, Map<String, Int>>(trigramObj.size)
            for ((key, innerVal) in trigramObj) {
                val innerObj = innerVal.jsonObject
                val innerMap = HashMap<String, Int>(innerObj.size)
                for ((nextWord, count) in innerObj) {
                    innerMap[nextWord] = count.jsonPrimitive.int
                }
                trigram[key] = innerMap
            }

            // Parse wordFreq
            val wordFreqObj = root["wordFreq"]?.jsonObject ?: JsonObject(emptyMap())
            val wordFreq = HashMap<String, Int>(wordFreqObj.size)
            for ((word, count) in wordFreqObj) {
                wordFreq[word] = count.jsonPrimitive.int
            }

            // Parse learnedOOV
            val oovArr = root["learnedOOV"]?.jsonArray ?: return PersonalProfile.EMPTY
            val learnedOOV = oovArr.map { it.jsonPrimitive.content }

            PersonalProfile(bigram = bigram, trigram = trigram, wordFreq = wordFreq, learnedOOV = learnedOOV)
        } catch (e: Throwable) {
            Log.w(TAG, "Optional personal profile not loaded ($path): ${e.message}")
            PersonalProfile.EMPTY
        }
    }

    private fun readAssetText(path: String): String {
        return context.assets.open(path).use { stream: InputStream ->
            stream.bufferedReader().readText()
        }
    }
}
