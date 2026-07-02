---
name: advanced-kotlin-json
description: Guidelines for Kotlin development and processing vocabulary/word association JSON data. Covers serialization (Kotlinx Serialization/Moshi), coroutines for background loading, flow-based data streams, data validation, and memory-efficient word suggestions.
---

# Advanced Kotlin & JSON Data Parsing Skill

This skill outlines guidelines for efficient Kotlin coding practices and processing dictionary JSON assets within Flowboard.

## 1. Vocabulary & Dictionary JSON Schema
A typical word list JSON asset should match this structure:
```json
{
  "locale": "th-TH",
  "version": 1,
  "words": [
    {"word": "สวัสดี", "freq": 999, "next": ["ครับ", "ค่ะ", "วัน"]},
    {"word": "การทำงาน", "freq": 850, "next": ["ของ", "ร่วมกับ"]}
  ]
}
```

### Kotlin Data Classes (using Kotlinx Serialization)
```kotlin
import kotlinx.serialization.Serializable

@Serializable
data class DictionaryAsset(
    val locale: String,
    val version: Int,
    val words: List<WordEntry>
)

@Serializable
data class WordEntry(
    val word: String,
    val freq: Int,
    val next: List<String> = emptyList()
)
```

## 2. Memory-Efficient Parsing & Caching
Because dictionaries can be large, reading them must not block the Main UI Thread:
*   Always parse JSON on `Dispatchers.IO`.
*   Use `InputStream` buffering.
*   For very large dictionaries, load associations lazily or use SQLite/Room database instead of holding everything in RAM.

### Asynchronous Loading Pattern
```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.InputStream

class DictionaryLoader(private val json: Json = Json { ignoreUnknownKeys = true }) {

    suspend fun loadDictionary(inputStream: InputStream): DictionaryAsset = withContext(Dispatchers.IO) {
        inputStream.use { stream ->
            val jsonString = stream.bufferedReader().use { it.readText() }
            json.decodeFromString<DictionaryAsset>(jsonString)
        }
    }
}
```

## 3. High-Performance Trie Data Structure
For autocomplete suggestions (prefix matching), implement a Trie:
```kotlin
class TrieNode {
    var isWord: Boolean = false
    var frequency: Int = 0
    val children = mutableMapOf<Char, TrieNode>()
}

class DictionaryTrie {
    private val root = TrieNode()

    fun insert(word: String, frequency: Int) {
        var current = root
        for (char in word) {
            current = current.children.getOrPut(char) { TrieNode() }
        }
        current.isWord = true
        current.frequency = frequency
    }

    fun searchPrefix(prefix: String): List<String> {
        var current = root
        for (char in prefix) {
            current = current.children[char] ?: return emptyList()
        }
        // Depth-first search to find matching words
        return findWordsFromNode(current, prefix)
    }

    private fun findWordsFromNode(node: TrieNode, currentPrefix: String): List<String> {
        val results = mutableListOf<Pair<String, Int>>()
        fun dfs(currNode: TrieNode, wordAcc: StringBuilder) {
            if (currNode.isWord) {
                results.add(Pair(wordAcc.toString(), currNode.frequency))
            }
            for ((char, child) in currNode.children) {
                wordAcc.append(char)
                dfs(child, wordAcc)
                wordAcc.deleteAt(wordAcc.length - 1)
            }
        }
        dfs(node, StringBuilder(currentPrefix))
        return results.sortedByDescending { it.second }.map { it.first }
    }
}
```
