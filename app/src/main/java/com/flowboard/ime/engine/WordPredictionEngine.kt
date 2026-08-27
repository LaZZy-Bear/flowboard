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

        private val CLEAN_WORD_REGEX = Regex("""[^a-z0-9'._@+-]""")
        private val EMAIL_TAIL_REGEX = Regex("""[a-z0-9._%+-]+@[a-z0-9.-]*$""")
        private val WORD_TOKEN_REGEX = Regex("[a-z0-9]+(?:['.-][a-z0-9]+)*")
    }

    private fun isDelimiterChar(c: Char): Boolean {
        return c.isWhitespace() || (!c.isLetterOrDigit() && c != '\'' && c != '@' && c != '.' && c != '-')
    }

    /**
     * Extracts the active typing prefix from [fullText] (text before cursor).
     * If the cursor is right after a word boundary delimiter (e.g. space, punctuation),
     * this returns "" (Next-Word mode).
     * If the cursor is in the middle of a word (e.g. "hel"), this returns the prefix ("hel").
     */
    fun getActivePrefix(fullText: String): String {
        val trimmed = fullText.trimEnd { it == '\t' || it == '\r' }
        if (trimmed.isEmpty()) return ""

        val engineText = trimmed.lowercase()
        val len = engineText.length
        val lastChar = engineText.last()

        val emailMatch = EMAIL_TAIL_REGEX.find(engineText)
        val isEmailTail = emailMatch != null && emailMatch.range.last == len - 1

        val isTrailingWordConnector = (lastChar == '-' || lastChar == '\'') &&
                len >= 2 && engineText[len - 2].isLetterOrDigit()

        val isWordBoundaryDelimiter = !isTrailingWordConnector && isDelimiterChar(lastChar)

        return if (isEmailTail) {
            emailMatch.value
        } else if (isWordBoundaryDelimiter) {
            ""
        } else {
            val allMatches = WORD_TOKEN_REGEX.findAll(engineText).toList()
            if (allMatches.isNotEmpty() && (allMatches.last().range.last == len - 1 || (isTrailingWordConnector && allMatches.last().range.last == len - 2))) {
                if (isTrailingWordConnector) engineText.substring(allMatches.last().range.first, len) else allMatches.last().value
            } else {
                ""
            }
        }
    }

    /**
     * Generate up to [maxCount] word suggestions based on the full text before cursor.
     */
    fun getPredictions(fullText: String, maxCount: Int = 3): List<String> {
        val trimmed = fullText.trimEnd { it == '\t' || it == '\r' }
        if (trimmed.isEmpty()) {
            return emptyList()
        }

        val engineText = trimmed.lowercase()
        val activePrefix = getActivePrefix(fullText)
        val contextWords: List<String>

        val emailMatch = EMAIL_TAIL_REGEX.find(engineText)
        val isEmailTail = emailMatch != null && emailMatch.range.last == engineText.length - 1

        if (isEmailTail) {
            val textBeforeEmail = engineText.substring(0, emailMatch.range.first)
            val wordsBefore = WORD_TOKEN_REGEX.findAll(textBeforeEmail).map { it.value }.toList()
            contextWords = wordsBefore
        } else if (activePrefix.isEmpty()) {
            val allWords = WORD_TOKEN_REGEX.findAll(engineText).map { it.value }.toList()
            contextWords = allWords
        } else {
            val allMatches = WORD_TOKEN_REGEX.findAll(engineText).toList()
            if (allMatches.isNotEmpty()) {
                val wordsBefore = allMatches.dropLast(1).map { it.value }
                contextWords = wordsBefore
            } else {
                contextWords = emptyList()
            }
        }

        val cleanContext = contextWords.map { cleanWord(it) }.filter { it.isNotEmpty() }
        val prefix = cleanPrefix(activePrefix)

        val results: List<String> = if (prefix.isEmpty()) {
            // ──────────────────────────────────────────
            // Mode A: Next Word Prediction (after space)
            // ──────────────────────────────────────────
            predictNextWords(cleanContext, maxCount)
        } else {
            // ──────────────────────────────────────────
            // Mode B: Prefix Autocomplete (while typing)
            // ──────────────────────────────────────────
            val baseList = autocompletePrefix(cleanContext, prefix, maxCount).toMutableList()
            if (repo.isPersonalizationEnabled) {
                val matchingEmails = repo.personalProfile.learnedOOV.filter { email ->
                    email.contains('@') && email.lowercase().startsWith(prefix) && email.length > prefix.length
                }.sortedByDescending { repo.personalProfile.wordFreq[it.lowercase()] ?: 1 }

                if (prefix.contains('@') || prefix.length >= 3 || baseList.isEmpty()) {
                    for (email in matchingEmails.reversed()) {
                        baseList.remove(email)
                        baseList.add(0, email)
                    }
                } else {
                    for (email in matchingEmails) {
                        if (!baseList.contains(email) && baseList.size < maxCount) {
                            baseList.add(email)
                        }
                    }
                }
            }
            baseList
        }

        if (results.isEmpty()) return emptyList()

        val rawActivePrefix = if (activePrefix.isNotEmpty() && activePrefix.length <= trimmed.length) {
            trimmed.substring(trimmed.length - activePrefix.length)
        } else {
            activePrefix
        }

        // Apply casing
        val isAllCaps = rawActivePrefix.length > 1 && rawActivePrefix.all { it.isUpperCase() }
        val isFirstUpper = rawActivePrefix.isNotEmpty() && rawActivePrefix[0].isUpperCase()

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
        val w1 = contextWords.last()

        // 0. Personal Bigram/Trigram matches (highest personal relevance, sorted by frequency/dominance)
        if (repo.isPersonalizationEnabled && repo.personalizationPairsEnabled) {
            val profile = repo.personalProfile
            if (contextWords.size >= 2) {
                val pw1 = contextWords[contextWords.size - 2]
                val pw2 = contextWords[contextWords.size - 1]
                val triKey = "${pw1}_${pw2}"
                profile.trigram[triKey]?.entries
                    ?.sortedByDescending { it.value }
                    ?.map { it.key }
                    ?.let { allCandidates.addAll(it) }
            }
            profile.bigram[w1]?.entries
                ?.sortedByDescending { it.value }
                ?.map { it.key }
                ?.let { allCandidates.addAll(it) }
        }

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

        // 1. Learned OOV Trie matching prefix (if personalize is enabled) - highest priority
        if (repo.isPersonalizationEnabled && repo.trieDictOOV != null) {
            var oovNode: TrieNode? = repo.trieDictOOV
            for (ch in prefix) {
                oovNode = oovNode?.get(ch.toString())
                if (oovNode == null) break
            }
            if (oovNode != null) {
                fun dfsOOV(n: TrieNode, word: String, depth: Int) {
                    if (allResults.size >= 400 || depth > 12) return
                    if (n.isEndOfWord) {
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

        // 2. Traverse Main Trie to find all completions
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

        // 3.5 Personal Bigram & Trigram Context Matches
        if (repo.isPersonalizationEnabled && repo.personalizationPairsEnabled) {
            val pProfile = repo.personalProfile
            if (contextWords.isNotEmpty()) {
                val prev = contextWords.last().lowercase()
                val personalNext = pProfile.bigram[prev]
                if (personalNext != null) {
                    for ((w, _) in personalNext) {
                        if (w.startsWith(prefix)) {
                            bigramMatches.add(w)
                        }
                    }
                }
            }
            if (contextWords.size >= 2) {
                val w1 = contextWords[contextWords.size - 2].lowercase()
                val w2 = contextWords[contextWords.size - 1].lowercase()
                val triKey = "${w1}_${w2}"
                val personalNext = pProfile.trigram[triKey]
                if (personalNext != null) {
                    for ((w, _) in personalNext) {
                        if (w.startsWith(prefix)) {
                            trigramMatches.add(w)
                        }
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

            val isLearnedWord = repo.isPersonalizationEnabled && (
                repo.personalProfile.learnedOOV.contains(word) ||
                repo.personalProfile.wordFreq.containsKey(word)
            )

            var score = if (isLearnedWord) 10000.0 else getWordBaseScore(word, rawIndex)
            val isObscureAbbr = word.length <= 3 && rawIndex > 1500 && !word.contains('\'') && !isLearnedWord

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

    private fun cleanPrefix(prefix: String): String {
        return prefix.lowercase().replace(CLEAN_WORD_REGEX, "").trimStart('.', '-', '\'')
    }

    private fun cleanWord(word: String): String {
        return word.lowercase()
            .replace(CLEAN_WORD_REGEX, "")
            .trim('.', '-', '\'')
    }

    private fun applyCasing(word: String, isAllCaps: Boolean, isFirstUpper: Boolean): String {
        return when {
            isAllCaps -> word.uppercase()
            isFirstUpper -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }
    }
}
