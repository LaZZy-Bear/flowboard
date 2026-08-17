package com.flowboard.ime.engine

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.TrieNode
import com.flowboard.ime.data.models.WordBigramEntry

/**
 * Word Prediction Engine
 *
 * 1. Empty text -> Returns empty list (Do NOT suggest when nothing has been typed).
 * 2. Next Word Prediction (after space) -> Ranked by lowest index in word_list.json.
 *    Uses Clustered Trigram -> Clustered Bigram -> STC -> (fallback to Personalize ONLY if main system unknown).
 * 3. Prefix Autocomplete (while typing) -> Ranked by (wordIndex + 1) * 1.4^extraChars.
 *    Learned OOV words from Personalize are included in candidate pool so user can tap custom words.
 */
class WordPredictionEngine(private val repo: FlowboardRepository) {

    companion object {
        private val CONNECTORS_SET = setOf(
            "the", "a", "an", "this", "that", "these", "those",
            "my", "your", "his", "her", "its", "our", "their",
            "to", "in", "of", "by", "for", "on", "at", "with", "from",
            "into", "about", "over", "after", "before", "under", "through", "out",
            "is", "was", "are", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "can", "could", "will", "would", "should",
            "and", "or", "but", "so", "as", "if", "than"
        )
    }

    /**
     * Generate up to [maxCount] word suggestions based on the full text before cursor.
     */
    fun getPredictions(fullText: String, maxCount: Int = 3): List<String> {
        // Point 1: Do NOT suggest when nothing has been typed yet
        val trimmed = fullText.trimEnd { it == '\t' || it == '\r' }
        if (trimmed.isEmpty()) {
            return emptyList()
        }

        val isSpace = trimmed.endsWith(' ')
        val rawTokens = trimmed.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (rawTokens.isEmpty()) {
            return emptyList()
        }

        val activePrefix: String
        val contextWords: List<String>

        if (isSpace) {
            activePrefix = ""
            contextWords = rawTokens
        } else {
            activePrefix = rawTokens.last()
            contextWords = rawTokens.dropLast(1)
        }

        val cleanContext = contextWords.map { cleanWord(it) }.filter { it.isNotEmpty() }
        val prefix = cleanWord(activePrefix)

        val results: List<String> = if (prefix.isEmpty()) {
            // ──────────────────────────────────────────
            // Mode A: Next Word Prediction (after space)
            // ──────────────────────────────────────────
            predictNextWords(cleanContext, maxCount)
        } else {
            // ──────────────────────────────────────────
            // Mode B: Prefix Autocomplete (while typing)
            // ──────────────────────────────────────────
            autocompletePrefix(prefix, maxCount)
        }

        if (results.isEmpty()) return emptyList()

        // Apply casing
        val isAllCaps = activePrefix.length > 1 && activePrefix.all { it.isUpperCase() }
        val isFirstUpper = activePrefix.isNotEmpty() && activePrefix[0].isUpperCase()

        return results.take(maxCount).map { word ->
            applyCasing(word, isAllCaps, isFirstUpper)
        }
    }

    /**
     * Next Word Prediction:
     * 1. Query Clustered Trigram (2-word context)
     * 2. Query Clustered Bigram (1-word context)
     * 3. Query Sentence Topic Clusters (STC)
     * 4. Fallback to Personalize ONLY if main system has no predictions for this context
     * Rank candidates by lowest index in word_list.json!
     */
    private fun predictNextWords(contextWords: List<String>, maxCount: Int): List<String> {
        if (contextWords.isEmpty()) return emptyList()

        val results = LinkedHashSet<String>()

        // 1. General Clustered Trigram (2-word context)
        if (contextWords.size >= 2) {
            val w1 = contextWords[contextWords.size - 2]
            val w2 = contextWords[contextWords.size - 1]
            val w1Id = repo.wordReverseMap[w1]
            val w2Id = repo.wordReverseMap[w2]
            if (w1Id != null && w2Id != null) {
                val key = "${w1Id}_${w2Id}"
                val nodeData = repo.clusteredTrigram.bigram[key]
                if (nodeData != null) {
                    val words = resolveNodeWords(nodeData, repo.clusteredTrigram)
                        .sortedBy { repo.wordReverseMap[it] ?: Int.MAX_VALUE }
                    for (word in words) {
                        if (word.isNotEmpty()) results.add(word)
                        if (results.size >= maxCount) return results.toList()
                    }
                }
            }
        }

        // 2. General Clustered Bigram (1-word context)
        val w1 = contextWords.last()
        val w1Id = repo.wordReverseMap[w1]
        if (w1Id != null) {
            val nodeData = repo.clusteredBigram.bigram[w1Id.toString()]
            if (nodeData != null) {
                val words = resolveNodeWords(nodeData, repo.clusteredBigram)
                    .sortedBy { repo.wordReverseMap[it] ?: Int.MAX_VALUE }
                for (word in words) {
                    if (word.isNotEmpty()) results.add(word)
                    if (results.size >= maxCount) return results.toList()
                }
            }
        }

        // 3. Sentence Topic Clusters (STC)
        if (contextWords.size >= 2) {
            val stcWords = getSTCWords(contextWords)
                .sortedBy { repo.wordReverseMap[it] ?: Int.MAX_VALUE }
            for (word in stcWords) {
                if (word.isNotEmpty()) results.add(word)
                if (results.size >= maxCount) return results.toList()
            }
        }

        // 4. Fallback to Personalize ONLY if main system found nothing and personalize is enabled
        if (results.isEmpty() && repo.isPersonalizationEnabled) {
            val profile = repo.personalProfile
            if (contextWords.size >= 2) {
                val pw1 = contextWords[contextWords.size - 2]
                val pw2 = contextWords[contextWords.size - 1]
                val triKey = "${pw1}_${pw2}"
                profile.trigram[triKey]?.keys?.let { results.addAll(it) }
            }
            if (results.isEmpty()) {
                profile.bigram[w1]?.keys?.let { results.addAll(it) }
            }
        }

        return results.take(maxCount).toList()
    }

    /**
     * Prefix Autocomplete (while typing):
     * Matches all words starting with prefix from:
     * - Main Trie dictionary
     * - Learned OOV Trie (if personalize enabled)
     * Ranks them by: (wordIndex + 1) * 1.4^extraChars
     */
    private fun autocompletePrefix(prefix: String, maxCount: Int): List<String> {
        val root = repo.trieDict ?: return emptyList()
        val allResults = mutableListOf<Pair<String, Int>>()

        // 1. Traverse Main Trie to find all completions
        var node: TrieNode? = root
        for (ch in prefix) {
            node = node?.get(ch.toString())
            if (node == null) break
        }

        if (node != null) {
            fun dfs(n: TrieNode, word: String, depth: Int) {
                if (allResults.size >= 200 || depth > 12) return
                if (n.isEndOfWord) {
                    val wordIndex = n.frequency // _w in trie is word_list index
                    allResults.add(word to wordIndex)
                }
                for ((key, child) in n.children) {
                    dfs(child, word + key, depth + 1)
                }
            }
            dfs(node, prefix, 0)
        }

        // 2. Learned OOV Trie matching prefix (if personalize is enabled)
        if (repo.isPersonalizationEnabled && repo.trieDictOOV != null) {
            var oovNode: TrieNode? = repo.trieDictOOV
            for (ch in prefix) {
                oovNode = oovNode?.get(ch.toString())
                if (oovNode == null) break
            }
            if (oovNode != null) {
                fun dfsOOV(n: TrieNode, word: String, depth: Int) {
                    if (depth > 12) return
                    if (n.isEndOfWord) {
                        // For OOV words, give competitive index so exact/close matches appear
                        val oovIndex = repo.wordReverseMap[word] ?: 500
                        allResults.add(word to oovIndex)
                    }
                    for ((key, child) in n.children) {
                        dfsOOV(child, word + key, depth + 1)
                    }
                }
                dfsOOV(oovNode, prefix, 0)
            }
        }

        // 3. Point 3: Sort by exact equation (wordIndex + 1) * 1.4^extraChars
        return allResults
            .distinctBy { it.first }
            .sortedBy { (word, wordIndex) ->
                val extraChars = maxOf(0, word.length - prefix.length)
                val lenPenalty = Math.pow(1.4, extraChars.toDouble())
                (wordIndex + 1) * lenPenalty
            }
            .map { it.first }
            .take(maxCount)
    }

    private fun resolveNodeWords(
        nodeData: WordBigramEntry,
        cwb: com.flowboard.ime.data.models.ClusteredWordBigram
    ): List<String> {
        val ids: List<Int> = when (nodeData) {
            is WordBigramEntry.DirectList -> nodeData.ids
            is WordBigramEntry.GroupRef -> {
                val groupIds = cwb.groups[nodeData.group] ?: emptyList()
                if (nodeData.extras.isNotEmpty()) {
                    groupIds.toMutableList().also { it.addAll(nodeData.extras) }
                } else groupIds
            }
        }
        return ids.mapNotNull { repo.wordList.getOrNull(it) }
    }

    private fun getSTCWords(contextWords: List<String>): List<String> {
        val stc = repo.sentenceTopicClusters
        if (contextWords.size < 2 || stc.isEmpty) return emptyList()

        val lastWord = cleanWord(contextWords.last())
        if (!CONNECTORS_SET.contains(lastWord)) return emptyList()

        val clusters = stc.clusters
        val wordMap = stc.wordMap
        val isDetailed = (stc.type == "detailed_top9" || wordMap == null)

        val results = mutableListOf<String>()

        for (i in contextWords.size - 2 downTo 0) {
            val prevWord = cleanWord(contextWords[i])
            if (prevWord.isEmpty() || CONNECTORS_SET.contains(prevWord)) continue

            val wId = repo.wordReverseMap[prevWord] ?: continue
            val clusterWordIds: List<Int>? = if (isDetailed) {
                clusters[wId.toString()]
            } else {
                val clusterId = wordMap[wId.toString()] ?: continue
                clusters[clusterId.toString()]
            }

            if (clusterWordIds != null) {
                for (relId in clusterWordIds) {
                    val relWord = repo.wordList.getOrNull(relId)
                    if (!relWord.isNullOrEmpty()) results.add(relWord)
                }
                break
            }
        }
        return results
    }

    private fun cleanWord(word: String): String {
        return word.lowercase().replace(Regex("[^a-z0-9']"), "")
    }

    private fun applyCasing(word: String, isAllCaps: Boolean, isFirstUpper: Boolean): String {
        return when {
            isAllCaps -> word.uppercase()
            isFirstUpper -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }
    }
}
