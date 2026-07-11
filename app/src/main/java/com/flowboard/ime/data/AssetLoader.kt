package com.flowboard.ime.data

import android.content.Context
import android.util.Log
import com.flowboard.ime.data.models.ClusteredWordBigram
import com.flowboard.ime.data.models.KeySlots
import com.flowboard.ime.data.models.LanguageData
import com.flowboard.ime.data.models.MasterLayoutEntry
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

class AssetLoader(private val context: Context) {

    companion object {
        private const val TAG = "AssetLoader"
        private const val DIR_TH = "th_TH"
        private const val DIR_EN = "en_US"
        private const val DIR_SHARED = "shared"
    }

    private val json = Json { ignoreUnknownKeys = true }

    // Temporary storage during load
    private lateinit var thData: MutableLanguageData
    private lateinit var enData: MutableLanguageData

    private class MutableLanguageData {
        var unigram: List<String> = emptyList()
        var masterLayout: Map<String, MasterLayoutEntry> = emptyMap()
        var bigram: Map<String, List<String>> = emptyMap()
        var trigram: Map<String, List<String>> = emptyMap()
        var trieDict: TrieNode = TrieNode()
        var wordList: List<String> = emptyList()
        var clusteredBigram: ClusteredWordBigram = ClusteredWordBigram.EMPTY
        var spaceNgram: Map<String, List<String>> = emptyMap()
        var defaultProfile: Profile? = null
        var chatProfile: Profile? = null
    }

    suspend fun loadCriticalData(repo: FlowboardRepository) = coroutineScope {
        Log.d(TAG, "Phase A: Loading critical data...")
        val startTime = System.currentTimeMillis()
        thData = MutableLanguageData()
        enData = MutableLanguageData()

        // Shared Data
        val charMapJob = async(Dispatchers.IO) { loadStringMap("$DIR_SHARED/char_map.json") }
        val thaiCharMapJob = async(Dispatchers.IO) { loadStringMap("$DIR_SHARED/thai_char_map.json") }
        val patternPenaltyJob = async(Dispatchers.IO) { loadStringListMap("$DIR_SHARED/pattern_penalty.json") }
        
        // Symbols (Shared UI)
        val symbolPage1Job = async(Dispatchers.IO) { loadSymbolPage("$DIR_TH/symbol_page_1.json") }
        val symbolPage2Job = async(Dispatchers.IO) { loadSymbolPage("$DIR_TH/symbol_page_2.json") }

        // TH Critical
        val thUnigramJob = async(Dispatchers.IO) { loadStringList("$DIR_TH/unigram.json") }
        val thMasterJob = async(Dispatchers.IO) { loadMasterLayoutV2("$DIR_TH/master_layout.json") }
        val thDefProfJob = async(Dispatchers.IO) { loadProfile("$DIR_TH/profile_default.json") }
        val thChatProfJob = async(Dispatchers.IO) { loadProfile("$DIR_TH/profile_chat.json") }

        // EN Critical
        val enUnigramJob = async(Dispatchers.IO) { loadStringList("$DIR_EN/unigram.json") }
        val enMasterJob = async(Dispatchers.IO) { loadMasterLayoutV2("$DIR_EN/master_layout.json") }
        val enChatProfJob = async(Dispatchers.IO) { loadProfile("$DIR_EN/profile_chat.json") } // No default profile for EN

        // Await shared
        repo.charMap = charMapJob.await()
        repo.thaiCharMap = thaiCharMapJob.await()
        repo.patternPenalty = patternPenaltyJob.await()
        repo.symbolPage1 = symbolPage1Job.await()
        repo.symbolPage2 = symbolPage2Job.await()

        // Build char reverse map
        val reverseMap = HashMap<String, String>()
        for ((id, char) in repo.charMap) {
            reverseMap[char] = id
        }
        repo.charReverseMap = reverseMap

        // Await TH
        thData.unigram = thUnigramJob.await()
        thData.masterLayout = thMasterJob.await()
        thData.defaultProfile = thDefProfJob.await()
        thData.chatProfile = thChatProfJob.await()

        // Await EN
        enData.unigram = enUnigramJob.await()
        enData.masterLayout = enMasterJob.await()
        enData.chatProfile = enChatProfJob.await()

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Phase A complete in ${elapsed}ms")
    }

    suspend fun loadNormalData(repo: FlowboardRepository) = coroutineScope {
        Log.d(TAG, "Phase B: Loading normal data...")
        val startTime = System.currentTimeMillis()

        // TH Normal
        val thBigramJob = async(Dispatchers.IO) { loadStringListMap("$DIR_TH/bigram.json") }
        val thSpaceJob = async(Dispatchers.IO) { loadStringListMap("$DIR_TH/space_ngram.json") }
        val thTrieJob = async(Dispatchers.IO) { loadCompressedTrie("$DIR_TH/trie_dict_compressed.json") }
        val thWordListJob = async(Dispatchers.IO) { loadStringList("$DIR_TH/word_list.json") }

        // EN Normal
        val enBigramJob = async(Dispatchers.IO) { loadStringListMap("$DIR_EN/bigram.json") }
        val enSpaceJob = async(Dispatchers.IO) { loadStringListMap("$DIR_EN/space_ngram.json") }
        val enTrieJob = async(Dispatchers.IO) { loadCompressedTrie("$DIR_EN/trie_dict_compressed.json") }
        val enWordListJob = async(Dispatchers.IO) { loadStringList("$DIR_EN/word_list.json") }

        thData.bigram = thBigramJob.await()
        thData.spaceNgram = thSpaceJob.await()
        thData.trieDict = thTrieJob.await()
        thData.wordList = thWordListJob.await()

        enData.bigram = enBigramJob.await()
        enData.spaceNgram = enSpaceJob.await()
        enData.trieDict = enTrieJob.await()
        enData.wordList = enWordListJob.await()

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Phase B complete in ${elapsed}ms")
    }

    suspend fun loadDeferredData(repo: FlowboardRepository) = coroutineScope {
        Log.d(TAG, "Phase C: Loading deferred data...")
        val startTime = System.currentTimeMillis()

        // TH Deferred
        val thTrigramJob = async(Dispatchers.IO) { loadStringListMap("$DIR_TH/trigram.json") }
        val thCwbJob = async(Dispatchers.IO) { loadClusteredWordBigram("$DIR_TH/clustered_word_bigram.json") }

        // EN Deferred
        val enTrigramJob = async(Dispatchers.IO) { loadStringListMap("$DIR_EN/trigram.json") }
        val enCwbJob = async(Dispatchers.IO) { loadClusteredWordBigram("$DIR_EN/clustered_word_bigram.json") }

        thData.trigram = thTrigramJob.await()
        thData.clusteredBigram = thCwbJob.await()

        enData.trigram = enTrigramJob.await()
        enData.clusteredBigram = enCwbJob.await()

        // Finalize LanguageData and register
        fun buildWordReverseMap(wordList: List<String>): Map<String, Int> {
            val map = HashMap<String, Int>(wordList.size)
            wordList.forEachIndexed { index, word -> if (word.isNotEmpty()) map[word] = index }
            return map
        }

        repo.languageRegistry["TH"] = LanguageData(
            lang = "TH",
            layoutStrategy = "TH",
            unigram = thData.unigram,
            bigram = thData.bigram,
            trigram = thData.trigram,
            masterLayout = thData.masterLayout,
            trieDict = thData.trieDict,
            wordList = thData.wordList,
            wordReverseMap = buildWordReverseMap(thData.wordList),
            clusteredBigram = thData.clusteredBigram,
            spaceNgram = thData.spaceNgram,
            defaultProfile = thData.defaultProfile,
            chatProfile = thData.chatProfile
        )

        repo.languageRegistry["EN"] = LanguageData(
            lang = "EN",
            layoutStrategy = "EN",
            unigram = enData.unigram,
            bigram = enData.bigram,
            trigram = enData.trigram,
            masterLayout = enData.masterLayout,
            trieDict = enData.trieDict,
            wordList = enData.wordList,
            wordReverseMap = buildWordReverseMap(enData.wordList),
            clusteredBigram = enData.clusteredBigram,
            spaceNgram = enData.spaceNgram,
            defaultProfile = enData.defaultProfile,
            chatProfile = enData.chatProfile
        )

        // Set default language
        repo.setLanguage("TH")

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Phase C complete in ${elapsed}ms")
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

    private fun loadStringMap(path: String): Map<String, String> {
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

    private fun loadMasterLayoutV2(path: String): Map<String, MasterLayoutEntry> {
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

    private fun loadProfile(path: String): Profile {
        return try {
            val text = readAssetText(path)
            json.decodeFromString<Profile>(text)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load $path: ${e.message}")
            Profile.DEFAULT
        }
    }

    private fun loadCompressedTrie(path: String): TrieNode {
        return try {
            val text = readAssetText(path)
            val obj = json.parseToJsonElement(text).jsonObject
            parseTrieObject(obj)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load $path: ${e.message}")
            TrieNode()
        }
    }

    private fun parseTrieObject(obj: JsonObject): TrieNode {
        val node = TrieNode()
        for ((key, value) in obj) {
            if (key == "_w") { // Updated to use "_w" for compressed trie
                node.isEndOfWord = true
                node.frequency = (value as? JsonPrimitive)?.int ?: 0
            } else {
                val childNode = when (value) {
                    is JsonObject -> parseTrieObject(value)
                    else -> TrieNode()
                }
                node.children[key] = childNode // Using String key
            }
        }
        return node
    }

    private fun loadClusteredWordBigram(path: String): ClusteredWordBigram {
        return try {
            val text = readAssetText(path)
            val root = json.parseToJsonElement(text).jsonObject
            
            // Parse groups
            val groupsObj = root["groups"]?.jsonObject ?: return ClusteredWordBigram.EMPTY
            val groups = HashMap<String, List<Int>>(groupsObj.size)
            for ((k, v) in groupsObj) {
                groups[k] = v.jsonArray.map { it.jsonPrimitive.int }
            }
            
            // Parse bigram
            val bigramObj = root["bigram"]?.jsonObject ?: return ClusteredWordBigram.EMPTY
            val bigram = HashMap<String, WordBigramEntry>(bigramObj.size)
            for ((k, v) in bigramObj) {
                if (v is kotlinx.serialization.json.JsonArray) {
                    bigram[k] = WordBigramEntry.DirectList(v.map { it.jsonPrimitive.int })
                } else if (v is JsonObject) {
                    val g = v["g"]?.jsonPrimitive?.content ?: ""
                    val extra = v["+"]?.jsonPrimitive?.int
                    bigram[k] = WordBigramEntry.GroupRef(g, extra)
                }
            }
            
            ClusteredWordBigram(groups, bigram)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load $path: ${e.message}")
            ClusteredWordBigram.EMPTY
        }
    }

    private fun readAssetText(path: String): String {
        return context.assets.open(path).use { stream: InputStream ->
            stream.bufferedReader().readText()
        }
    }
}
