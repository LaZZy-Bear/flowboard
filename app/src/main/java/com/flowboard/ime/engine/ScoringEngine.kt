package com.flowboard.ime.engine

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.ClusteredWordBigram
import com.flowboard.ime.data.models.EngineWeights
import com.flowboard.ime.data.models.TrieNode
import com.flowboard.ime.data.models.WordBigramEntry

/**
 * Scoring Engine — Prototype 22 (English Core)
 *
 * Ported from js/scoring.js. Computes per-character probability scores using a
 * 7-layer weighted N-gram system with 6 context states:
 *
 * States:
 *   1 — Start / empty input
 *   2 — Prefix length == 1
 *   3 — Prefix length == 2
 *   4 — Prefix length >= 3
 *   7 — Standard Spacebar (after content words)
 *   8 — Connector Spacebar (after articles, prepositions, aux verbs, conjunctions)
 *
 * Sub-Engines:
 *   U   = Unigram / Start Unigram (States 1, 7, 8 use unigramStart)
 *   B   = Bigram (previous 1 character)
 *   T   = Trigram (previous 2 characters)
 *   D   = Dictionary / Trie (prefix matching, with OOV fallback)
 *   WB  = Word Bigram (next word first-char from 1 previous word)
 *   WT  = Word Trigram (next word first-char from 2 previous words)
 *   STC = Sentence Topic Clusters (domain co-occurrence, after connector words)
 */
class ScoringEngine(private val repo: FlowboardRepository) {

    companion object {
        /**
         * ENGINE_WEIGHTS — directly from P22 scoring.js ENGINE_WEIGHTS constant.
         */
        private val STATE_WEIGHTS = mapOf(
            1 to EngineWeights(U = 36, B = 39, T = 50, D = 29, WB = 51,  WT = 56,  STC = 11),
            2 to EngineWeights(U = 0,  B = 24, T = 93, D = 8,  WB = 100, WT = 100, STC = 69),
            3 to EngineWeights(U = 0,  B = 20, T = 59, D = 4,  WB = 80,  WT = 96,  STC = 26),
            4 to EngineWeights(U = 3,  B = 2,  T = 3,  D = 50, WB = 90,  WT = 93,  STC = 41),
            7 to EngineWeights(U = 1,  B = 17, T = 7,  D = 31, WB = 30,  WT = 100, STC = 100),
            8 to EngineWeights(U = 6,  B = 0,  T = 60, D = 44, WB = 5,   WT = 95,  STC = 0)
        )

        /**
         * CONNECTORS_SET — words that trigger State 8 (Connector Spacebar).
         * After these words, the STC engine provides domain-aware predictions.
         */
        private val CONNECTORS_SET = setOf(
            // Articles & Determiners
            "the", "a", "an", "this", "that", "these", "those",
            "my", "your", "his", "her", "its", "our", "their",
            // Prepositions
            "to", "in", "of", "by", "for", "on", "at", "with", "from",
            "into", "about", "over", "after", "before", "under", "through", "out",
            // Auxiliary & Linking Verbs
            "is", "was", "are", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "can", "could", "will", "would", "should",
            // Conjunctions
            "and", "or", "but", "so", "as", "if", "than"
        )

        /**
         * Characters blocked from doubling (sticky key) unless explicitly learned/recorded in personalization.
         */
        val RESTRICTED_DOUBLE_CHARS = setOf("i", "v", "j", "q", "x", "u")
    }

    private var cachedTriePrefix: String = ""
    private var cachedMainNode: TrieNode? = null
    private var cachedOovNode: TrieNode? = null

    var engineStatus: String = "State 1 (Start)"
        private set

    fun resetTrieCache() {
        cachedTriePrefix = ""
        cachedMainNode = null
        cachedOovNode = null
    }

    // ═══════════════════════════════════════
    // Main Entry Point
    // ═══════════════════════════════════════

    fun calculateScores(text: String): Map<String, Double> {
        val engineText = text.lowercase()
        val len = engineText.length
        val last1 = if (len >= 1) engineText.substring(len - 1) else ""
        val last2 = if (len >= 2) engineText.substring(len - 2) else ""
        val isSpace = last1 == " "

        // Parse active prefix and word history
        val parts = engineText.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val activePrefix: String
        val activeWordsArray: List<String>
        if (isSpace || engineText.isEmpty()) {
            activePrefix = ""
            activeWordsArray = parts
        } else {
            activePrefix = parts.lastOrNull() ?: ""
            activeWordsArray = if (parts.isNotEmpty()) parts.dropLast(1) else emptyList()
        }

        // Determine last word before space (for connector detection)
        val lastWordBeforeSpace = activeWordsArray.lastOrNull()
            ?.lowercase()
            ?.replace(Regex("[^a-z']"), "") ?: ""

        // Determine state
        val state = when {
            len == 0 -> 1
            isSpace && CONNECTORS_SET.contains(lastWordBeforeSpace) -> 8
            isSpace -> 7
            activePrefix.length == 1 -> 2
            activePrefix.length == 2 -> 3
            else -> 4
        }

        val fallback = EngineWeights(U = 100, B = 0, T = 0, D = 0, WB = 0, WT = 0, STC = 0)
        val W = (STATE_WEIGHTS[state] ?: fallback).mutableCopy()
        engineStatus = STATE_WEIGHTS[state]?.let {
            "State $state (${it.U}U ${it.B}B ${it.T}T ${it.D}D ${it.WB}WB ${it.WT}WT ${it.STC}STC)"
        } ?: "Fallback"

        // ── Sub-engine score computation ──
        val isWordStart = (state == 1 || state == 7 || state == 8)
        val sU = if (isWordStart) getStartUnigramScores() else getUnigramScores()
        val sB = getBigramScores(last1)
        val sT = getTrigramScores(last2)
        val sD = getDictScores(activePrefix)
        val sWB = getWordBigramScores(activeWordsArray, activePrefix)
        val sWT = getWordTrigramScores(activeWordsArray, activePrefix)
        val sSTC = if (state == 8 || activePrefix.isNotEmpty()) getSTCScores(activeWordsArray, activePrefix) else emptyMap()

        // ── OOV Decay: if prefix yields no dict hits, transfer D weight to T ──
        val hasDictScores = sD.isNotEmpty()
        if (activePrefix.isNotEmpty() && !hasDictScores) {
            W.T += W.D
            W.D = 0
            engineStatus += " [OOV Decay ⚠️]"
        }

        // ── Weight gating: zero out weights for empty sources ──
        val curWU = if (sU.isNotEmpty()) W.U else 0
        val curWB = if (sB.isNotEmpty()) W.B else 0
        val curWT_ngram = if (sT.isNotEmpty()) W.T else 0
        val curWD = if (hasDictScores) W.D else 0
        val curWWB = if (sWB.isNotEmpty()) W.WB else 0
        val curWWT = if (sWT.isNotEmpty()) W.WT else 0
        val curWSTC = if (state == 8 && sSTC.isNotEmpty()) W.STC else 0

        var sumW = curWU + curWB + curWT_ngram + curWD + curWWB + curWWT + curWSTC

        // ── Zero weight fallback: use plain unigram ──
        val effectiveSU: Map<String, Double>
        val effectiveCurWU: Int
        if (sumW == 0) {
            effectiveSU = getUnigramScores()
            effectiveCurWU = 100
            sumW = 100
        } else {
            effectiveSU = sU
            effectiveCurWU = curWU
        }

        // ── Weighted score fusion ──
        val finalScores = HashMap<String, Double>()

        fun mergeScores(source: Map<String, Double>, weightRatio: Double) {
            if (weightRatio == 0.0) return
            for ((c, score) in source) {
                finalScores[c] = (finalScores[c] ?: 0.0) + (score * weightRatio)
            }
        }

        mergeScores(effectiveSU, effectiveCurWU.toDouble() / sumW)
        mergeScores(sB, curWB.toDouble() / sumW)
        mergeScores(sT, curWT_ngram.toDouble() / sumW)
        mergeScores(sD, curWD.toDouble() / sumW)
        mergeScores(sWB, curWWB.toDouble() / sumW)
        mergeScores(sWT, curWWT.toDouble() / sumW)
        mergeScores(sSTC, curWSTC.toDouble() / sumW)

        // ── Post-processing ──
        val rules = repo.activeProfile.rules
        if (rules.allowEcho && len > 0) {
            applyEchoBooster(finalScores, engineText)
        }

        applyBonusDict(finalScores)
        applyUnigramTiebreaker(finalScores)

        // ── Personalization (additive layer, after base scores) ──
        if (repo.isPersonalizationEnabled) {
            personalizationEngine.applyPersonalization(finalScores, activeWordsArray, activePrefix, state)
        }

        return finalScores
    }

    // ═══════════════════════════════════════
    // Sub-Engine Score Calculators
    // ═══════════════════════════════════════

    /** Standard character frequency (used mid-word). */
    private fun getUnigramScores(): Map<String, Double> {
        val unigram = repo.unigram
        if (unigram.isEmpty()) return emptyMap()
        val raw = HashMap<String, Double>(unigram.size)
        unigram.forEachIndexed { index, c -> raw[c] = (unigram.size - index).toDouble() }
        return normalizeScores(raw)
    }

    /** Sentence-starting character frequency (used in States 1, 7, 8). */
    private fun getStartUnigramScores(): Map<String, Double> {
        val unigramStart = repo.unigramStart.ifEmpty { repo.unigram }
        if (unigramStart.isEmpty()) return emptyMap()
        val raw = HashMap<String, Double>(unigramStart.size)
        unigramStart.forEachIndexed { index, c -> raw[c] = (unigramStart.size - index).toDouble() }
        return normalizeScores(raw)
    }

    private fun getBigramScores(last1: String): Map<String, Double> {
        if (last1.isEmpty()) return emptyMap()
        return parseNGramList(repo.bigram[last1])
    }

    private fun getTrigramScores(last2: String): Map<String, Double> {
        if (last2.length != 2) return emptyMap()
        return parseNGramList(repo.trigram[last2])
    }

    /**
     * Dictionary/Trie score with Depth Proximity Factor, Word Popularity Ranking,
     * Top-2 Branch Synergy, and OOV trie fallback/merging.
     * Ported from P22 V22.3.0 getDictScores() and evaluateBranch() in scoring.js.
     */
    private fun getDictScores(prefix: String): Map<String, Double> {
        if (prefix.isEmpty()) {
            cachedTriePrefix = ""
            cachedMainNode = repo.trieDict
            cachedOovNode = repo.trieDictOOV
            return emptyMap()
        }

        val mainNode: TrieNode?
        val oovNode: TrieNode?

        // Incremental cache: extend by one character if possible
        when {
            (cachedMainNode != null || cachedOovNode != null) &&
                    prefix.length == cachedTriePrefix.length + 1 &&
                    prefix.startsWith(cachedTriePrefix) -> {
                val lastChar = prefix.last().toString()
                mainNode = cachedMainNode?.get(lastChar)
                oovNode = cachedOovNode?.get(lastChar)
            }
            (cachedMainNode != null || cachedOovNode != null) && prefix == cachedTriePrefix -> {
                mainNode = cachedMainNode
                oovNode = cachedOovNode
            }
            else -> {
                // Full traversal in main trie
                var currentMain = repo.trieDict
                for (c in prefix) {
                    currentMain = currentMain?.get(c.toString())
                    if (currentMain == null) break
                }
                mainNode = currentMain

                // Full traversal in OOV trie
                var currentOov = repo.trieDictOOV
                for (c in prefix) {
                    currentOov = currentOov?.get(c.toString())
                    if (currentOov == null) break
                }
                oovNode = currentOov
            }
        }

        cachedTriePrefix = prefix
        cachedMainNode = mainNode
        cachedOovNode = oovNode

        if (mainNode == null && oovNode == null) return emptyMap()

        // 🧠 Trie Branch Evaluation: Quick completion (low depth) + Word popularity (low index in word_list)
        val totalWords = if (repo.wordList.isNotEmpty()) repo.wordList.size else 20000

        val raw = HashMap<String, Double>()

        // 1. Evaluate Main Dictionary Branches
        if (mainNode != null) {
            for ((nextKey, childNode) in mainNode.children) {
                if (nextKey != "_w" && nextKey != "_f") {
                    val branchScore = evaluateBranch(childNode, totalWords, isOOV = false)
                    if (branchScore > 0.0) {
                        raw[nextKey] = branchScore
                    }
                }
            }
        }

        // 2. Evaluate Secondary / Learned OOV Branches (combines with or boosts main dictionary)
        if (oovNode != null) {
            for ((nextKey, childNode) in oovNode.children) {
                if (nextKey != "_w" && nextKey != "_f") {
                    val branchScore = evaluateBranch(childNode, totalWords, isOOV = true)
                    if (branchScore > 0.0) {
                        val existing = raw[nextKey] ?: 0.0
                        if (branchScore > existing) {
                            raw[nextKey] = branchScore
                        }
                    }
                }
            }
        }

        return normalizeScores(raw)
    }

    private fun evaluateBranch(
        branchRoot: TrieNode,
        totalWords: Int,
        isOOV: Boolean,
        decay: Double = 0.80,
        maxDepth: Int = 6
    ): Double {
        var top1 = 0.0
        var top2 = 0.0

        fun traverse(curr: TrieNode?, depth: Int) {
            if (curr == null || depth > maxDepth) return
            if (curr.isEndOfWord) {
                // Lower index in wordList = higher popularity (1.0 -> 0.0)
                var pop = 1.0 - (curr.frequency.toDouble() / totalWords)
                if (isOOV) pop *= 0.5 // Penalty for OOV words
                val depthFactor = Math.pow(decay, (depth - 1).toDouble()) // Depth 1 = 1.0, Depth 2 = 0.80, ...
                val s = pop * depthFactor * 100.0
                if (s > top1) {
                    top2 = top1
                    top1 = s
                } else if (s > top2) {
                    top2 = s
                }
            }
            for ((k, child) in curr.children) {
                if (k != "_w" && k != "_f") {
                    traverse(child, depth + 1)
                }
            }
        }

        traverse(branchRoot, 1)
        return top1 + (top2 * 0.15)
    }

    /**
     * Word Bigram: predict next word's character based on current activePrefix.
     * Uses clusteredBigram (1-word history). Multi-position prefix tracking ported from P22 V22.2.0.
     */
    private fun getWordBigramScores(wordsArray: List<String>, activePrefix: String): Map<String, Double> {
        if (wordsArray.isEmpty()) return emptyMap()
        val cwb = repo.clusteredBigram
        if (cwb.bigram.isEmpty()) return emptyMap()

        val lastWord = wordsArray.last()
        val wordId = repo.wordReverseMap[lastWord] ?: return emptyMap()
        val nodeData = cwb.bigram[wordId.toString()] ?: return emptyMap()

        return resolveClusteredWordIds(nodeData, cwb, activePrefix)
    }

    /**
     * Word Trigram: predict next word's character based on current activePrefix.
     * Uses clusteredTrigram (2-word history). Multi-position prefix tracking ported from P22 V22.2.0.
     */
    private fun getWordTrigramScores(wordsArray: List<String>, activePrefix: String): Map<String, Double> {
        if (wordsArray.size < 2) return emptyMap()
        val cwt = repo.clusteredTrigram
        if (cwt.bigram.isEmpty()) return emptyMap()

        val w1 = wordsArray[wordsArray.size - 2]
        val w2 = wordsArray[wordsArray.size - 1]
        val w1Id = repo.wordReverseMap[w1] ?: return emptyMap()
        val w2Id = repo.wordReverseMap[w2] ?: return emptyMap()

        val key = "${w1Id}_${w2Id}"
        val nodeData = cwt.bigram[key] ?: return emptyMap()

        return resolveClusteredWordIds(nodeData, cwt, activePrefix)
    }

    /**
     * Sentence Topic Cluster: domain co-occurrence scores, active in State 8 and typing states.
     * Multi-position prefix tracking ported from P22 getSTCScores() in scoring.js.
     */
    private fun getSTCScores(activeWordsArray: List<String>, activePrefix: String): Map<String, Double> {
        val raw = HashMap<String, Double>()
        val stc = repo.sentenceTopicClusters
        if (activeWordsArray.size < 2 || stc.isEmpty) return raw

        val lastWordBeforeSpace = activeWordsArray.last().lowercase().replace(Regex("[^a-z']"), "")
        if (!CONNECTORS_SET.contains(lastWordBeforeSpace)) return raw

        val clusters = stc.clusters
        val wordMap = stc.wordMap
        val isDetailed = (stc.type == "detailed_top9" || wordMap == null)
        val isDeepDeterminer = (lastWordBeforeSpace == "the" || lastWordBeforeSpace == "a" || lastWordBeforeSpace == "an")
        val maxLookbackDepth = if (isDeepDeterminer) 4 else 2

        val prefix = activePrefix.lowercase()
        val prefixLen = prefix.length
        var checkedCount = 0

        // Walk backwards through previous words, skipping connectors
        for (i in activeWordsArray.size - 2 downTo 0) {
            val prevWord = activeWordsArray[i].lowercase().replace(Regex("[^a-z']"), "")
            if (prevWord.isEmpty() || CONNECTORS_SET.contains(prevWord)) continue

            val wId = repo.wordReverseMap[prevWord] ?: continue

            val clusterWordIds: List<Int>? = if (isDetailed) {
                clusters[wId.toString()]
            } else {
                val clusterId = wordMap[wId.toString()] ?: continue
                clusters[clusterId.toString()]
            }

            if (clusterWordIds != null) {
                val depthFactor = 1.0 - (checkedCount * 0.2)
                clusterWordIds.forEachIndexed { rankIdx, relId ->
                    val relWord = repo.wordList.getOrNull(relId) ?: return@forEachIndexed
                    if (relWord.isNotEmpty() && (prefixLen == 0 || relWord.startsWith(prefix))) {
                        if (relWord.length > prefixLen) {
                            val targetChar = relWord[prefixLen].toString()
                            val pts = (9 - rankIdx) * depthFactor
                            raw[targetChar] = (raw[targetChar] ?: 0.0) + pts
                        }
                    }
                }
                checkedCount++
                if (checkedCount >= maxLookbackDepth) break
            }
        }

        return normalizeScores(raw)
    }

    // ═══════════════════════════════════════
    // Post-Processing
    // ═══════════════════════════════════════

    private fun applyEchoBooster(scores: HashMap<String, Double>, text: String) {
        val rules = repo.activeProfile.rules
        var realLastChar = ""
        var repeatCount = 0
        var hasSpaceInBetween = false

        for (i in text.length - 1 downTo 0) {
            val c = text[i]
            if (c == ' ') {
                if (realLastChar.isNotEmpty()) hasSpaceInBetween = true
                continue
            }
            val cs = c.toString()
            when {
                realLastChar.isEmpty() -> {
                    realLastChar = cs
                    repeatCount = 1
                }
                cs == realLastChar -> repeatCount++
                else -> break
            }
        }

        if (realLastChar.isNotEmpty()) {
            // ══════════════════════════════════════════
            // CRITICAL FIX: Echo boost should ONLY apply when doubling
            // the char is actually valid in the trie dictionary.
            // Without this gate, the +3.0 echo buff inflates the score
            // of the last-typed char (e.g. 'i' after typing "ai") even
            // when no word supports doubling (e.g. "aii" doesn't exist).
            // This causes the char to wrongly win the TAP slot via
            // Lazy TAP ratio, making the layout appear "stuck".
            // Deleting fixes it because lastAction becomes null → no echo.
            // ══════════════════════════════════════════
            if (!isDoubleCharValid(text, realLastChar)) {
                return
            }

            val isDragging = repeatCount >= 2 || (hasSpaceInBetween && repeatCount >= 1)
            val echoBuff: Double = when {
                rules.echoHardcapChars.contains(realLastChar) -> rules.echoHardcapBuff
                isDragging -> 999.0
                else -> rules.echoBaseBuff
            }
            scores[realLastChar] = (scores[realLastChar] ?: 0.0) + echoBuff
        }
    }

    private fun applyBonusDict(scores: HashMap<String, Double>) {
        for ((char, bonus) in repo.bonusDict) {
            scores[char] = (scores[char] ?: 0.0) + bonus
        }
    }

    private fun applyUnigramTiebreaker(scores: HashMap<String, Double>) {
        val unigram = repo.unigram
        for (i in unigram.indices) {
            val c = unigram[i]
            scores[c] = (scores[c] ?: 0.0) + ((unigram.size - i) * 0.001)
        }
    }

    // ═══════════════════════════════════════
    // Sticky Key Validation
    // ═══════════════════════════════════════

    /**
     * Returns true if typing [charToTest] again (doubling) is a valid Trie path.
     *
     * Rules (ported from P22 isDoubleCharValid):
     * - No sticky on empty prefix
     * - No sticky on first character of a word (activePrefix.length == 1)
     * - No triple repetition (aab → 'a' is not valid)
     * - Must have a valid branch in main trie OR OOV trie
     */
    fun isDoubleCharValid(text: String, charToTest: String): Boolean {
        if (text.isEmpty() || charToTest.isEmpty()) return false

        val safeChar = charToTest.lowercase()

        // ⚡ Fast Exit 1: ถ้าเป็นตัวอักษรต้องห้ามเบิ้ล (i, v, j, q, x, u) และไม่มีข้อมูลใน Personalization -> return false ทันที (O(1))
        if (RESTRICTED_DOUBLE_CHARS.contains(safeChar)) {
            val hasPersonal = repo.personalProfile.learnedOOV.isNotEmpty() || repo.personalProfile.wordFreq.isNotEmpty()
            if (!hasPersonal) return false
        }

        val engineText = text.lowercase()
        if (engineText.endsWith(" ") || engineText.isEmpty()) return false

        // ⚡ Fast Exit 2: ตัดคำเฉพาะคำสุดท้ายด้วย lastIndexOf แทน regex split เพื่อประสิทธิภาพสูงสุด
        val lastSpace = engineText.lastIndexOf(' ')
        val activePrefix = if (lastSpace == -1) engineText else engineText.substring(lastSpace + 1)

        val prefixLen = activePrefix.length
        if (prefixLen <= 1) return false

        // No triple repetition
        val prev = activePrefix[prefixLen - 2].toString()
        val curr = activePrefix[prefixLen - 1].toString()
        if (prev == safeChar && curr == safeChar) return false

        // Sticky key / doubling ONLY applies if activePrefix already ends with the typed character
        if (!activePrefix.endsWith(safeChar)) return false

        val testPrefix = activePrefix + safeChar

        // Check if character is restricted from being doubled (i, v, j, q, x, u)
        if (RESTRICTED_DOUBLE_CHARS.contains(safeChar)) {
            val inPersonalFreq = repo.personalProfile.wordFreq.keys.any { it.lowercase().startsWith(testPrefix) }
            val inLearnedOOV = repo.personalProfile.learnedOOV.any { it.lowercase().startsWith(testPrefix) }
            val isAllowedByPersonalization = inPersonalFreq || inLearnedOOV
            if (!isAllowedByPersonalization) {
                return false
            }
        }

        fun checkTrie(root: TrieNode?): Boolean {
            if (root == null) return false
            var node = root
            for (c in testPrefix) {
                node = node?.get(c.toString())
                if (node == null) return false
            }
            return true
        }

        return checkTrie(repo.trieDict) || checkTrie(repo.trieDictOOV)
    }

    // ═══════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════

    private val personalizationEngine by lazy { PersonalizationEngine(repo) }

    private fun resolveClusteredWordIds(
        nodeData: WordBigramEntry,
        cwb: ClusteredWordBigram,
        activePrefix: String
    ): Map<String, Double> {
        val nextWordIDs: List<Int> = when (nodeData) {
            is WordBigramEntry.DirectList -> nodeData.ids
            is WordBigramEntry.GroupRef -> {
                val groupIds = cwb.groups[nodeData.group] ?: emptyList()
                if (nodeData.extras.isNotEmpty()) {
                    groupIds.toMutableList().also { it.addAll(nodeData.extras) }
                } else groupIds
            }
        }

        val prefix = activePrefix.lowercase()
        val prefixLen = prefix.length
        val raw = HashMap<String, Double>()

        for (i in nextWordIDs.indices) {
            val nextWord = repo.wordList.getOrNull(nextWordIDs[i]) ?: continue
            if (nextWord.isNotEmpty() && (prefixLen == 0 || nextWord.startsWith(prefix))) {
                if (nextWord.length > prefixLen) {
                    val targetChar = nextWord[prefixLen].toString()
                    val score = ((nextWordIDs.size - i) * 10).toDouble()
                    if ((raw[targetChar] ?: 0.0) < score) {
                        raw[targetChar] = score
                    }
                }
            }
        }
        return normalizeScores(raw)
    }

    private fun parseNGramList(list: Any?): Map<String, Double> {
        if (list == null) return emptyMap()
        val raw = HashMap<String, Double>()
        when (list) {
            is List<*> -> {
                val strList = list.filterIsInstance<String>()
                strList.forEachIndexed { index, c -> raw[c] = (strList.size - index).toDouble() }
            }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = list as Map<String, Number>
                for ((k, v) in map) raw[k] = v.toDouble()
            }
        }
        return normalizeScores(raw)
    }

    private fun normalizeScores(raw: Map<String, Double>): Map<String, Double> {
        if (raw.isEmpty()) return emptyMap()
        var max = 0.0
        for (v in raw.values) { if (v > max) max = v }
        if (max == 0.0) return emptyMap()
        val result = HashMap<String, Double>(raw.size)
        for ((k, v) in raw) result[k] = (v / max) * 100.0
        return result
    }
}
