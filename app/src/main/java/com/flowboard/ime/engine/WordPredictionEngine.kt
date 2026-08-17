package com.flowboard.ime.engine

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.TrieNode
import com.flowboard.ime.data.models.WordBigramEntry

/**
 * Word Prediction Engine — Generates next-word predictions and prefix completions.
 *
 * Combines 7 ranking tiers:
 * 1. Personalization Trigram (live learned 2-word context)
 * 2. Personalization Bigram (live learned 1-word context)
 * 3. Personal Learned OOV Trie & Frequent Words (live vocabulary)
 * 4. Clustered Word Trigram (P22 2-word history database)
 * 5. Clustered Word Bigram (P22 1-word history database)
 * 6. Sentence Topic Clusters (STC domain vocabulary after connectors)
 * 7. Main Trie Dictionary DFS (prefix search with frequency & length penalty)
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

        private val DEFAULT_STARTERS = listOf("I", "The", "You")
    }

    /**
     * Generate up to [maxCount] word suggestions based on the full text before cursor.
     */
    fun getPredictions(fullText: String, maxCount: Int = 3): List<String> {
        val trimmed = fullText.trimEnd { it == '\t' || it == '\r' }
        val isSpace = trimmed.isNotEmpty() && trimmed.last() == ' '
        val isStartOfText = trimmed.isEmpty()

        // Extract tokens
        val rawTokens = trimmed.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        val activePrefix: String
        val contextWords: List<String>

        if (isSpace || isStartOfText) {
            activePrefix = ""
            contextWords = rawTokens
        } else {
            activePrefix = rawTokens.lastOrNull() ?: ""
            contextWords = if (rawTokens.isNotEmpty()) rawTokens.dropLast(1) else emptyList()
        }

        val cleanContext = contextWords.map { cleanWord(it) }.filter { it.isNotEmpty() }
        val prefix = cleanWord(activePrefix)

        val candidates = LinkedHashSet<String>()

        if (prefix.isEmpty()) {
            // ──────────────────────────────────────────
            // Mode A: Next Word Prediction (after space)
            // ──────────────────────────────────────────
            predictNextWords(cleanContext, candidates, maxCount)
        } else {
            // ──────────────────────────────────────────
            // Mode B: Prefix Autocomplete (while typing)
            // ──────────────────────────────────────────
            autocompletePrefix(cleanContext, prefix, candidates, maxCount)
        }

        if (candidates.size < maxCount && prefix.isEmpty() && cleanContext.isEmpty()) {
            candidates.addAll(DEFAULT_STARTERS)
        }

        // Apply casing
        val isAllCaps = activePrefix.length > 1 && activePrefix.all { it.isUpperCase() }
        val isFirstUpper = activePrefix.isNotEmpty() && activePrefix[0].isUpperCase()
        val isSentenceStart = isSentenceBeginning(trimmed)

        return candidates.take(maxCount).map { word ->
            applyCasing(word, isAllCaps, isFirstUpper || (prefix.isEmpty() && isSentenceStart))
        }
    }

    private fun predictNextWords(
        contextWords: List<String>,
        candidates: LinkedHashSet<String>,
        maxCount: Int
    ) {
        val profile = repo.personalProfile
        val isPersonEnabled = repo.isPersonalizationEnabled

        // 1. Personal Trigram (2-word context)
        if (isPersonEnabled && repo.personalizationPairsEnabled && contextWords.size >= 2) {
            val w1 = contextWords[contextWords.size - 2]
            val w2 = contextWords[contextWords.size - 1]
            val triKey = "${w1}_${w2}"
            val triMap = profile.trigram[triKey]
            if (triMap != null) {
                triMap.entries.sortedByDescending { it.value }.forEach { (nextWord, _) ->
                    if (nextWord.isNotEmpty()) candidates.add(nextWord)
                    if (candidates.size >= maxCount) return
                }
            }
        }

        // 2. Personal Bigram (1-word context)
        if (isPersonEnabled && repo.personalizationPairsEnabled && contextWords.isNotEmpty()) {
            val w1 = contextWords.last()
            val biMap = profile.bigram[w1]
            if (biMap != null) {
                biMap.entries.sortedByDescending { it.value }.forEach { (nextWord, _) ->
                    if (nextWord.isNotEmpty()) candidates.add(nextWord)
                    if (candidates.size >= maxCount) return
                }
            }
        }

        // 3. General Clustered Trigram (2-word context)
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
                    for (word in words) {
                        if (word.isNotEmpty()) candidates.add(word)
                        if (candidates.size >= maxCount) return
                    }
                }
            }
        }

        // 4. General Clustered Bigram (1-word context)
        if (contextWords.isNotEmpty()) {
            val w1 = contextWords.last()
            val w1Id = repo.wordReverseMap[w1]
            if (w1Id != null) {
                val nodeData = repo.clusteredBigram.bigram[w1Id.toString()]
                if (nodeData != null) {
                    val words = resolveNodeWords(nodeData, repo.clusteredBigram)
                    for (word in words) {
                        if (word.isNotEmpty()) candidates.add(word)
                        if (candidates.size >= maxCount) return
                    }
                }
            }
        }

        // 5. Sentence Topic Clusters (STC)
        if (contextWords.size >= 2) {
            val stcWords = getSTCWords(contextWords)
            for (word in stcWords) {
                if (word.isNotEmpty()) candidates.add(word)
                if (candidates.size >= maxCount) return
            }
        }

        // 6. Personal Frequent Words
        if (isPersonEnabled && repo.personalizationFreqEnabled && profile.wordFreq.isNotEmpty()) {
            profile.wordFreq.entries.sortedByDescending { it.value }.forEach { (word, _) ->
                if (word.isNotEmpty()) candidates.add(word)
                if (candidates.size >= maxCount) return
            }
        }
    }

    private fun autocompletePrefix(
        contextWords: List<String>,
        prefix: String,
        candidates: LinkedHashSet<String>,
        maxCount: Int
    ) {
        val profile = repo.personalProfile
        val isPersonEnabled = repo.isPersonalizationEnabled

        // 1. Personal Trigram & Bigram matches with prefix
        if (isPersonEnabled && repo.personalizationPairsEnabled) {
            if (contextWords.size >= 2) {
                val w1 = contextWords[contextWords.size - 2]
                val w2 = contextWords[contextWords.size - 1]
                val triKey = "${w1}_${w2}"
                profile.trigram[triKey]?.entries
                    ?.filter { it.key.startsWith(prefix) }
                    ?.sortedByDescending { it.value }
                    ?.forEach { (word, _) ->
                        candidates.add(word)
                        if (candidates.size >= maxCount) return
                    }
            }
            if (contextWords.isNotEmpty()) {
                val w1 = contextWords.last()
                profile.bigram[w1]?.entries
                    ?.filter { it.key.startsWith(prefix) }
                    ?.sortedByDescending { it.value }
                    ?.forEach { (word, _) ->
                        candidates.add(word)
                        if (candidates.size >= maxCount) return
                    }
            }
        }

        // 2. Personal Learned OOV Trie matching prefix
        if (isPersonEnabled && repo.trieDictOOV != null) {
            val oovMatches = searchTrie(repo.trieDictOOV, prefix)
            for (word in oovMatches) {
                candidates.add(word)
                if (candidates.size >= maxCount) return
            }
        }

        // 3. Personal Frequent Words matching prefix
        if (isPersonEnabled && repo.personalizationFreqEnabled && profile.wordFreq.isNotEmpty()) {
            profile.wordFreq.entries
                .filter { it.key.startsWith(prefix) }
                .sortedByDescending { it.value }
                .forEach { (word, _) ->
                    candidates.add(word)
                    if (candidates.size >= maxCount) return
                }
        }

        // 4. General Clustered Trigram / Bigram matches with prefix
        if (contextWords.size >= 2) {
            val w1 = contextWords[contextWords.size - 2]
            val w2 = contextWords[contextWords.size - 1]
            val w1Id = repo.wordReverseMap[w1]
            val w2Id = repo.wordReverseMap[w2]
            if (w1Id != null && w2Id != null) {
                val nodeData = repo.clusteredTrigram.bigram["${w1Id}_${w2Id}"]
                if (nodeData != null) {
                    val words = resolveNodeWords(nodeData, repo.clusteredTrigram).filter { it.startsWith(prefix) }
                    for (word in words) {
                        candidates.add(word)
                        if (candidates.size >= maxCount) return
                    }
                }
            }
        }

        if (contextWords.isNotEmpty()) {
            val w1 = contextWords.last()
            val w1Id = repo.wordReverseMap[w1]
            if (w1Id != null) {
                val nodeData = repo.clusteredBigram.bigram[w1Id.toString()]
                if (nodeData != null) {
                    val words = resolveNodeWords(nodeData, repo.clusteredBigram).filter { it.startsWith(prefix) }
                    for (word in words) {
                        candidates.add(word)
                        if (candidates.size >= maxCount) return
                    }
                }
            }
        }

        // 5. Main Trie Dictionary DFS search
        if (repo.trieDict != null) {
            val trieMatches = searchTrieRanked(repo.trieDict, prefix)
            for (word in trieMatches) {
                candidates.add(word)
                if (candidates.size >= maxCount) return
            }
        }
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

    private fun searchTrie(root: TrieNode?, prefix: String): List<String> {
        if (root == null || prefix.isEmpty()) return emptyList()
        var node: TrieNode = root
        for (ch in prefix) {
            node = node.get(ch.toString()) ?: return emptyList()
        }
        val results = mutableListOf<String>()
        fun dfs(curr: TrieNode, currentWord: String) {
            if (results.size >= 10) return
            if (curr.isEndOfWord) results.add(currentWord)
            for ((key, child) in curr.children) {
                dfs(child, currentWord + key)
            }
        }
        dfs(node, prefix)
        return results
    }

    private fun searchTrieRanked(root: TrieNode?, prefix: String): List<String> {
        if (root == null || prefix.isEmpty()) return emptyList()
        var node: TrieNode = root
        for (ch in prefix) {
            node = node.get(ch.toString()) ?: return emptyList()
        }

        val allResults = mutableListOf<Pair<String, Int>>()

        fun dfs(n: TrieNode, word: String, depth: Int) {
            if (allResults.size >= 20 || depth > 12) return
            if (n.isEndOfWord) {
                allResults.add(word to n.frequency)
            }
            for ((key, child) in n.children) {
                dfs(child, word + key, depth + 1)
            }
        }

        dfs(node, prefix, 0)

        return allResults.sortedBy { (word, rank) ->
            val extraChars = maxOf(0, word.length - prefix.length)
            val lenPenalty = Math.pow(1.4, extraChars.toDouble())
            (rank + 1) * lenPenalty
        }.map { it.first }
    }

    private fun cleanWord(word: String): String {
        return word.lowercase().replace(Regex("[^a-z0-9']"), "")
    }

    private fun isSentenceBeginning(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        val lastChar = trimmed.last()
        return lastChar == '.' || lastChar == '!' || lastChar == '?' || lastChar == '\n'
    }

    private fun applyCasing(word: String, isAllCaps: Boolean, isFirstUpper: Boolean): String {
        return when {
            isAllCaps -> word.uppercase()
            isFirstUpper -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }
    }
}
