package com.flowboard.ime.engine

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.TrieNode
import com.flowboard.ime.data.models.WordBigramEntry

/**
 * Production-Grade Word Prediction Engine (Gboard / SwiftKey Architecture)
 *
 * 1. Empty text -> Returns empty list (Do NOT suggest when nothing has been typed).
 * 2. Next Word Prediction (after space) -> N-gram ordered with at most 1 stop connector (the, a, to, in, etc.)
 *    so content/meaningful words get the other 2 slots.
 * 3. Prefix Autocomplete (while typing) -> Context-Aware N-gram Boost + Normalized Word Popularity + Length Penalty.
 *    - Context Aware: Boosts words that make grammatical sense after previous words (e.g. "I h" -> "have", "How are y" -> "you").
 *    - Normalized Popularity: Fixes artificial contraction indices and filters out obscure abbreviations (plc, std, cd).
 *    - Personal OOV Support: User-learned words matching prefix appear with high priority.
 */
class WordPredictionEngine(private val repo: FlowboardRepository) {

    companion object {
        /**
         * Pure structural connectors / stop words (the, a, to, in, of, and, etc.)
         * When predicting next words, at most 1 of these is allowed in the 3 prediction slots.
         */
        private val STOP_CONNECTORS_SET = setOf(
            "the", "a", "an", "and", "or", "but", "to", "in", "of", "by", "for", "on", "at",
            "with", "from", "into", "about", "as", "than", "so", "if", "that", "this", "these", "those"
        )

        /**
         * Common contractions that users frequently type.
         * Other obscure contractions (e.g. shan't, mightn't) are de-prioritized.
         */
        private val COMMON_CONTRACTIONS = setOf(
            "don't", "can't", "i'm", "it's", "that's", "you're", "i'll", "we'll",
            "didn't", "won't", "i've", "they're", "you've", "he's", "she's", "let's"
        )

        private val PLURAL_INDICATORS = setOf(
            "many", "several", "these", "those", "two", "three", "four", "five", "all", "both", "few", "some"
        )

        private val SINGULAR_INDICATORS = setOf(
            "a", "an", "one", "this", "that", "each", "every", "another"
        )

        private val SPACE_REGEX = Regex("\\s+")
        private val CLEAN_WORD_REGEX = Regex("[^a-z0-9']")
    }

    /**
     * Generate up to [maxCount] word suggestions based on the full text before cursor.
     */
    fun getPredictions(fullText: String, maxCount: Int = 3): List<String> {
        val trimmed = fullText.trimEnd { it == '\t' || it == '\r' }
        if (trimmed.isEmpty()) {
            return emptyList()
        }

        val isSpace = trimmed.endsWith(' ')
        val rawTokens = trimmed.trim().split(SPACE_REGEX).filter { it.isNotEmpty() }
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
            autocompletePrefix(cleanContext, prefix, maxCount)
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
     * Gathers candidate next words in n-gram priority order (Trigram -> Bigram -> STC -> Personalize fallback).
     * Limits pure stop connectors (the, a, to, in, etc.) to at most 1 slot so content words get the rest.
     */
    private fun predictNextWords(contextWords: List<String>, maxCount: Int): List<String> {
        if (contextWords.isEmpty()) return emptyList()

        val allCandidates = LinkedHashSet<String>()

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
                    allCandidates.addAll(words)
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
                allCandidates.addAll(words)
            }
        }

        // 3. Sentence Topic Clusters (STC)
        if (contextWords.size >= 2) {
            val stcWords = getSTCWords(contextWords)
            allCandidates.addAll(stcWords)
        }

        // 4. Fallback to Personalize ONLY if main system found nothing and personalize is enabled
        if (allCandidates.isEmpty() && repo.isPersonalizationEnabled) {
            val profile = repo.personalProfile
            if (contextWords.size >= 2) {
                val pw1 = contextWords[contextWords.size - 2]
                val pw2 = contextWords[contextWords.size - 1]
                val triKey = "${pw1}_${pw2}"
                profile.trigram[triKey]?.keys?.let { allCandidates.addAll(it) }
            }
            if (allCandidates.isEmpty()) {
                profile.bigram[w1]?.keys?.let { allCandidates.addAll(it) }
            }
        }

        if (allCandidates.isEmpty()) return emptyList()

        val selected = mutableListOf<String>()
        var connectorCount = 0

        // Filter candidates preserving n-gram database order, capping stop connectors to 1 max
        for (word in allCandidates) {
            if (word.isEmpty()) continue
            val isStopConnector = STOP_CONNECTORS_SET.contains(word)
            if (isStopConnector) {
                if (connectorCount < 1) {
                    selected.add(word)
                    connectorCount++
                }
            } else {
                selected.add(word)
            }
            if (selected.size >= maxCount) break
        }

        // Backfill if under maxCount
        if (selected.size < maxCount) {
            for (word in allCandidates) {
                if (word.isNotEmpty() && !selected.contains(word)) {
                    selected.add(word)
                    if (selected.size >= maxCount) break
                }
            }
        }

        return selected
    }

    /**
     * Context-Aware Prefix Autocomplete:
     * 1. Finds all completions from Trie dictionary + OOV Trie.
     * 2. Queries preceding context (Trigram / Bigram) to boost matching words.
     * 3. Normalizes English frequency (penalizing obscure contractions & weird abbreviations).
     * 4. Scores candidates: BasePopularity + ContextBoost - LengthPenalty.
     */
    private fun autocompletePrefix(contextWords: List<String>, prefix: String, maxCount: Int): List<String> {
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
                if (allResults.size >= 400 || depth > 12) return
                if (n.isEndOfWord) {
                    val wordIndex = repo.wordReverseMap[word] ?: n.frequency
                    allResults.add(word to wordIndex)
                }
                for ((key, child) in n.children) {
                    dfs(child, word + key, depth + 1)
                }
            }
            dfs(node, prefix, 0)
        }

        // 2. Learned OOV Trie matching prefix (if personalize is enabled)
        var hasPersonalOOV = false
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
                        hasPersonalOOV = true
                        // User-learned OOV words get highest priority
                        allResults.add(word to 0)
                    }
                    for ((key, child) in n.children) {
                        dfsOOV(child, word + key, depth + 1)
                    }
                }
                dfsOOV(oovNode, prefix, 0)
            }
        }

        val candidates = allResults.distinctBy { it.first }
        if (candidates.isEmpty()) return emptyList()
        if (candidates.size <= maxCount) return candidates.map { it.first }

        // 3. Find N-gram context matches starting with prefix
        val trigramMatches = mutableSetOf<String>()
        val bigramMatches = mutableSetOf<String>()

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
                    for (w in words) {
                        if (w.startsWith(prefix)) trigramMatches.add(w)
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
                    val words = resolveNodeWords(nodeData, repo.clusteredBigram)
                    for (w in words) {
                        if (w.startsWith(prefix)) bigramMatches.add(w)
                    }
                }
            }
        }

        // 4. Score each candidate
        val scoredCandidates = mutableListOf<Pair<String, Double>>()
        val lastContextWord = contextWords.lastOrNull()?.lowercase()

        for ((word, rawIndex) in candidates) {
            // Skip single letter echo words like "c", "t", "b" (except "a" and "i")
            if (word.length == 1 && word != "a" && word != "i") continue

            var score = getWordBaseScore(word, rawIndex)
            val isObscureAbbr = word.length <= 3 && rawIndex > 1500 && !word.contains('\'') && !hasPersonalOOV

            // Exact match bonus: when user typed the exact valid word (and not an obscure abbreviation), give highest priority
            if (word == prefix && !isObscureAbbr) {
                score += 2500.0
            }

            // Prefer base/root form over +s derivative if user hasn't typed 's'
            if (word.length > prefix.length && word.endsWith('s') && !prefix.endsWith('s')) {
                val root = word.substring(0, word.length - 1)
                if (repo.wordReverseMap.containsKey(root)) {
                    score -= 250.0
                }
            }

            // Context Grammar Boost (Plural vs Singular indicators)
            if (lastContextWord != null) {
                if (PLURAL_INDICATORS.contains(lastContextWord) && word.endsWith('s')) {
                    score += 3000.0
                } else if (SINGULAR_INDICATORS.contains(lastContextWord) && !word.endsWith('s')) {
                    score += 3000.0
                }
            }

            // Context Boost (N-gram)
            if (trigramMatches.contains(word)) {
                score += 12000.0
            } else if (bigramMatches.contains(word)) {
                score += 6000.0
            }

            // Length penalty: 45 pts per extra character
            val extraChars = maxOf(0, word.length - prefix.length)
            score -= extraChars * 45.0

            // Penalize obscure abbreviations (indices > 1500 for short words len <= 3 without apostrophe)
            if (isObscureAbbr) {
                score -= 5000.0
            }

            scoredCandidates.add(word to score)
        }

        return scoredCandidates
            .sortedByDescending { it.second }
            .map { it.first }
            .take(maxCount)
    }

    /**
     * Compute realistic English popularity score.
     * Normalized so that standard words (the, of, to, have, can, make) have top scores,
     * common contractions (can't, don't) have normal scores, and obscure contractions (shan't) are penalized.
     */
    private fun getWordBaseScore(word: String, rawIndex: Int): Double {
        if (word.contains('\'')) {
            return if (COMMON_CONTRACTIONS.contains(word)) {
                10000.0 - 150.0
            } else {
                -5000.0 // obscure contractions (shan't, mightn't, etc.)
            }
        }
        // In word_list.json, indices 0-49 are contractions. Normal words start at 50 ("the" = 51)
        val normIndex = maxOf(0, rawIndex - 50)
        return maxOf(0.0, 10000.0 - normIndex.toDouble())
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
        if (!STOP_CONNECTORS_SET.contains(lastWord)) return emptyList()

        val clusters = stc.clusters
        val wordMap = stc.wordMap
        val isDetailed = (stc.type == "detailed_top9" || wordMap == null)

        val results = mutableListOf<String>()

        for (i in contextWords.size - 2 downTo 0) {
            val prevWord = cleanWord(contextWords[i])
            if (prevWord.isEmpty() || STOP_CONNECTORS_SET.contains(prevWord)) continue

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
        return word.lowercase().replace(CLEAN_WORD_REGEX, "")
    }

    private fun applyCasing(word: String, isAllCaps: Boolean, isFirstUpper: Boolean): String {
        return when {
            isAllCaps -> word.uppercase()
            isFirstUpper -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }
    }
}
