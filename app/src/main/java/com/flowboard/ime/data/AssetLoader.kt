package com.flowboard.ime.data

import android.content.Context
import android.util.Log
import com.flowboard.ime.data.models.MasterKey
import com.flowboard.ime.data.models.Profile
import com.flowboard.ime.data.models.TrieNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream

/**
 * Orchestrates loading all JSON data files from the assets directory.
 *
 * Loading is organized in 3 phases:
 * - **Phase A (Critical)**: Small files needed before the keyboard can render
 *   (unigram, char_map, master_layout, pattern_penalty, default profile)
 * - **Phase B (Normal)**: Medium files that enhance prediction accuracy
 *   (bigram, space_ngram, trie_dict, word_id_map)
 * - **Phase C (Deferred)**: Large files loaded in the background
 *   (trigram, hybrid_word_trie)
 */
class AssetLoader(private val context: Context) {

    companion object {
        private const val TAG = "AssetLoader"
        private const val LOCALE_DIR = "th_TH"
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Phase A: Load critical data needed before the keyboard can be displayed.
     * This should complete in < 50ms on most devices.
     */
    suspend fun loadCriticalData(repo: FlowboardRepository) = coroutineScope {
        Log.d(TAG, "Phase A: Loading critical data...")
        val startTime = System.currentTimeMillis()

        val unigramJob = async(Dispatchers.IO) {
            loadStringList("$LOCALE_DIR/unigram.json")
        }
        val charMapJob = async(Dispatchers.IO) {
            loadStringMap("$LOCALE_DIR/thai_char_map.json")
        }
        val masterLayoutJob = async(Dispatchers.IO) {
            loadMasterLayout("$LOCALE_DIR/master_layout.json")
        }
        val patternPenaltyJob = async(Dispatchers.IO) {
            loadStringListMap("$LOCALE_DIR/pattern_penalty.json")
        }
        val profileJob = async(Dispatchers.IO) {
            loadProfile("$LOCALE_DIR/profile_default.json")
        }

        repo.unigram = unigramJob.await()
        repo.charMap = charMapJob.await()
        repo.masterLayout = masterLayoutJob.await()
        repo.patternPenalty = patternPenaltyJob.await()

        val profile = profileJob.await()
        repo.activeProfile = profile
        repo.bonusDict = profile.bonusDict

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Phase A complete in ${elapsed}ms")
    }

    /**
     * Phase B: Load normal-priority data that improves predictions.
     */
    suspend fun loadNormalData(repo: FlowboardRepository) = coroutineScope {
        Log.d(TAG, "Phase B: Loading normal data...")
        val startTime = System.currentTimeMillis()

        val bigramJob = async(Dispatchers.IO) {
            loadStringListMap("$LOCALE_DIR/bigram.json")
        }
        val spaceNgramJob = async(Dispatchers.IO) {
            loadStringListMap("$LOCALE_DIR/space_ngram.json")
        }
        val trieJob = async(Dispatchers.IO) {
            loadTrieDict("$LOCALE_DIR/trie_dict.json")
        }
        val wordIdMapJob = async(Dispatchers.IO) {
            loadStringList("$LOCALE_DIR/word_id_map.json")
        }

        repo.bigram = bigramJob.await()
        repo.spaceNgram = spaceNgramJob.await()
        repo.trieDictRoot = trieJob.await()
        repo.wordIdMap = wordIdMapJob.await()

        // Build reverse word map (word → id)
        val reverseMap = HashMap<String, String>(repo.wordIdMap.size)
        repo.wordIdMap.forEachIndexed { index, word ->
            if (word.isNotEmpty()) {
                reverseMap[word] = index.toString()
            }
        }
        repo.reverseWordMap = reverseMap

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Phase B complete in ${elapsed}ms")
    }

    /**
     * Phase C: Load large deferred data in the background.
     */
    suspend fun loadDeferredData(repo: FlowboardRepository) = coroutineScope {
        Log.d(TAG, "Phase C: Loading deferred data...")
        val startTime = System.currentTimeMillis()

        val trigramJob = async(Dispatchers.IO) {
            loadStringListMap("$LOCALE_DIR/trigram.json")
        }
        val hybridTrieJob = async(Dispatchers.IO) {
            loadHybridWordTrie("$LOCALE_DIR/hybrid_word_trie.json")
        }

        repo.trigram = trigramJob.await()
        repo.hybridWordTrie = hybridTrieJob.await()

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Phase C complete in ${elapsed}ms")
    }

    // ════════════════════════════════════════════
    // Low-Level JSON Parsers
    // ════════════════════════════════════════════

    /**
     * Load a JSON file as a List<String> (e.g., unigram.json, word_id_map.json).
     */
    private fun loadStringList(path: String): List<String> {
        return try {
            val text = readAssetText(path)
            json.decodeFromString<List<String?>>(text).map { it ?: "" }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load $path: ${e.message}")
            emptyList()
        }
    }

    /**
     * Load a JSON file as a Map<String, String> (e.g., thai_char_map.json).
     */
    private fun loadStringMap(path: String): Map<String, String> {
        return try {
            val text = readAssetText(path)
            json.decodeFromString<Map<String, String>>(text)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load $path: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Load a JSON file as Map<String, List<String>> (e.g., bigram, trigram, pattern_penalty).
     */
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

    /**
     * Load master_layout.json as Map<String, MasterKey>.
     */
    private fun loadMasterLayout(path: String): Map<String, MasterKey> {
        return try {
            val text = readAssetText(path)
            json.decodeFromString<Map<String, MasterKey>>(text)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load $path: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Load a profile JSON file.
     */
    private fun loadProfile(path: String): Profile {
        return try {
            val text = readAssetText(path)
            json.decodeFromString<Profile>(text)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load $path: ${e.message}")
            Profile.DEFAULT
        }
    }

    /**
     * Load trie_dict.json into a TrieNode tree structure.
     * The JSON is a nested object where keys are characters
     * and "_f" marks end of word.
     */
    private fun loadTrieDict(path: String): TrieNode {
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
            if (key == "_f") {
                node.isEndOfWord = true
                node.frequency = (value as? JsonPrimitive)?.int ?: 0
            } else if (key.length == 1) {
                val childNode = when (value) {
                    is JsonObject -> parseTrieObject(value)
                    else -> TrieNode()
                }
                node.children[key[0]] = childNode
            }
        }
        return node
    }

    /**
     * Load hybrid_word_trie.json as a nested map structure:
     * Map<contextId, Map<wordId, Map<nextWordId, frequency>>>
     */
    private fun loadHybridWordTrie(path: String): Map<String, Map<String, Map<String, Int>>> {
        return try {
            val text = readAssetText(path)
            json.decodeFromString<Map<String, Map<String, Map<String, Int>>>>(text)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load $path: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Read a text file from the assets directory.
     */
    private fun readAssetText(path: String): String {
        return context.assets.open(path).use { stream: InputStream ->
            stream.bufferedReader().readText()
        }
    }
}
