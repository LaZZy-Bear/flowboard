package com.flowboard.ime.testing

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.SentenceTopicClusters
import com.flowboard.ime.data.models.ClusteredWordBigram
import com.flowboard.ime.data.models.MasterLayoutEntry
import com.flowboard.ime.data.models.PersonalProfile
import com.flowboard.ime.data.models.Profile
import com.flowboard.ime.data.models.TrieNode
import com.flowboard.ime.data.models.WordBigramEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Test data factory for loading English-only P22 data from the asset filesystem.
 * Used in unit tests — no Android context required.
 */
object TestDataFactory {

    private val json = Json { ignoreUnknownKeys = true }

    fun loadRepo(repo: FlowboardRepository) {
        repo.reset()

        var baseDir = File("app/src/main/assets")
        if (!baseDir.exists()) {
            baseDir = File("src/main/assets")
        }
        if (!baseDir.exists()) {
            throw IllegalStateException("Assets directory not found! Check working directory.")
        }

        val enDir = File(baseDir, "en")

        // Shared
        repo.symbolPage1 = emptyMap()
        repo.symbolPage2 = emptyMap()

        // English data
        repo.unigram = loadStringList(File(enDir, "unigram.json"))
        repo.unigramStart = loadStringList(File(enDir, "unigram_start.json"))
        repo.masterLayout = loadMasterLayout(File(enDir, "master_layout.json"))
        repo.bigram = loadStringListMap(File(enDir, "bigram.json"))
        repo.trigram = loadStringListMap(File(enDir, "trigram.json"))
        repo.trieDict = loadCompressedTrie(File(enDir, "trie_dict_compressed.json"))
        repo.trieDictOOV = loadCompressedTrieOrNull(File(enDir, "trie_dict_oov.json"))

        val wordList = loadStringList(File(enDir, "word_list.json"))
        repo.wordList = wordList
        val reverseMap = HashMap<String, Int>(wordList.size)
        wordList.forEachIndexed { index, word -> if (word.isNotEmpty()) reverseMap[word] = index }
        repo.wordReverseMap = reverseMap

        repo.clusteredBigram = loadClusteredWordBigram(File(enDir, "clustered_word_bigram.json"))
        repo.clusteredTrigram = loadClusteredWordBigram(File(enDir, "clustered_word_trigram_en.json"))
        repo.sentenceTopicClusters = loadSentenceTopicClusters(File(enDir, "sentence_topic_clusters.json"))
        repo.personalProfile = PersonalProfile.EMPTY

        repo.activeProfile = Profile.DEFAULT
        repo.bonusDict = repo.activeProfile.bonusDict

        repo.markReady()
        repo.markFullyLoaded()
    }

    private fun loadStringList(file: File): List<String> {
        if (!file.exists()) return emptyList()
        val text = file.readText()
        return json.decodeFromString<List<String?>>(text).map { it ?: "" }
    }

    private fun loadStringListMap(file: File): Map<String, List<String>> {
        if (!file.exists()) return emptyMap()
        val text = file.readText()
        val obj = json.parseToJsonElement(text).jsonObject
        val result = HashMap<String, List<String>>(obj.size)
        for ((key, value) in obj) {
            result[key] = value.jsonArray.map { it.jsonPrimitive.content }
        }
        return result
    }

    private fun loadMasterLayout(file: File): Map<String, MasterLayoutEntry> {
        if (!file.exists()) return emptyMap()
        val text = file.readText()
        return json.decodeFromString<Map<String, MasterLayoutEntry>>(text)
    }

    private fun loadCompressedTrie(file: File): TrieNode {
        if (!file.exists()) return TrieNode()
        val text = file.readText()
        val obj = json.parseToJsonElement(text).jsonObject
        return parseTrieObject(obj)
    }

    private fun loadCompressedTrieOrNull(file: File): TrieNode? {
        if (!file.exists()) return null
        val text = file.readText()
        val obj = json.parseToJsonElement(text).jsonObject
        return parseTrieObject(obj)
    }

    private fun parseTrieObject(obj: JsonObject): TrieNode {
        val node = TrieNode()
        for ((key, value) in obj) {
            when (key) {
                "_w" -> {
                    node.isEndOfWord = true
                    node.frequency = (value as? JsonPrimitive)?.int ?: 0
                }
                "_f" -> node.frequency = (value as? JsonPrimitive)?.int ?: 0
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

    private fun loadClusteredWordBigram(file: File): ClusteredWordBigram {
        if (!file.exists()) return ClusteredWordBigram.EMPTY
        val text = file.readText()
        val root = json.parseToJsonElement(text).jsonObject

        val groupsObj = root["groups"]?.jsonObject ?: return ClusteredWordBigram.EMPTY
        val groups = HashMap<String, List<Int>>(groupsObj.size)
        for ((k, v) in groupsObj) {
            groups[k] = v.jsonArray.map { it.jsonPrimitive.int }
        }

        val bigramObj = (root["bigram"] ?: root["trigram"])?.jsonObject
            ?: return ClusteredWordBigram.EMPTY
        val bigram = HashMap<String, WordBigramEntry>(bigramObj.size)
        for ((k, v) in bigramObj) {
            if (v is JsonArray) {
                bigram[k] = WordBigramEntry.DirectList(v.map { it.jsonPrimitive.int })
            } else if (v is JsonObject) {
                val g = v["g"]?.jsonPrimitive?.content ?: ""
                val plusElement = v["+"]
                val extras = when (plusElement) {
                    is JsonArray -> plusElement.map { it.jsonPrimitive.int }
                    is JsonPrimitive -> listOf(plusElement.int)
                    else -> emptyList()
                }
                bigram[k] = WordBigramEntry.GroupRef(g, extras)
            }
        }

        return ClusteredWordBigram(groups, bigram)
    }

    private fun loadSentenceTopicClusters(file: File): SentenceTopicClusters {
        if (!file.exists()) return SentenceTopicClusters.EMPTY
        val text = file.readText()
        val root = json.parseToJsonElement(text).jsonObject

        val type = root["type"]?.jsonPrimitive?.content ?: ""

        val wordMapObj = root["wordMap"]?.jsonObject
        val wordMap: Map<String, Int>? = wordMapObj?.let { obj ->
            val map = HashMap<String, Int>(obj.size)
            for ((k, v) in obj) {
                map[k] = v.jsonPrimitive.int
            }
            map
        }

        val clustersObj = root["clusters"]?.jsonObject ?: return SentenceTopicClusters.EMPTY
        val clusters = HashMap<String, List<Int>>(clustersObj.size)
        for ((k, v) in clustersObj) {
            clusters[k] = v.jsonArray.map { it.jsonPrimitive.int }
        }

        return SentenceTopicClusters(type = type, wordMap = wordMap, clusters = clusters)
    }
}
