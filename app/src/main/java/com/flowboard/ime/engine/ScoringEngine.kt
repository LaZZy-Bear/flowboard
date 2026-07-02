package com.flowboard.ime.engine

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.EngineWeights
import com.flowboard.ime.data.models.TrieNode
import com.flowboard.ime.util.ThaiCharUtil

/**
 * The core scoring engine that calculates character prediction scores
 * based on the current typing context. Uses a 6-state machine with
 * weighted combination of 6 sub-engines:
 *
 * - Unigram (U):     Base character frequency
 * - Bigram (B):      Previous 1-character context
 * - Trigram (T):     Previous 2-character context
 * - Dictionary (D):  Trie prefix matching
 * - Word Bigram (WB): Word-level prediction
 * - Space N-gram (SN): Post-space character prediction
 *
 * States:
 * - State 1: Start of text (len=0) → Pure Unigram
 * - State 2: 1-char prefix → Dictionary dominant
 * - State 3: 2-char prefix → Trigram dominant
 * - State 4: 3+ char prefix → Dictionary dominant
 * - State 5: Completed word (prefix empty) → Trigram dominant
 * - State 7: After space → Balanced distribution
 */
class ScoringEngine(private val repo: FlowboardRepository) {

    // ── Engine State Weights (matching prototype 11 exactly) ──
    companion object {
        private val STATE_WEIGHTS = mapOf(
            1 to EngineWeights(U = 100, B = 0, T = 0, D = 0, WB = 0, SN = 0),
            2 to EngineWeights(U = 0, B = 32, T = 6, D = 95, WB = 0, SN = 0),
            3 to EngineWeights(U = 7, B = 13, T = 78, D = 31, WB = 0, SN = 15),
            4 to EngineWeights(U = 12, B = 10, T = 23, D = 90, WB = 0, SN = 1),
            5 to EngineWeights(U = 34, B = 8, T = 82, D = 13, WB = 7, SN = 1),
            7 to EngineWeights(U = 21, B = 22, T = 17, D = 21, WB = 14, SN = 16)
        )
    }

    // ── Cached Trie Pointer (for O(1) incremental trie walking) ──
    private var cachedTriePrefix: String = ""
    private var cachedTrieNode: TrieNode? = null

    /** Current engine status string for debug display */
    var engineStatus: String = "State 1 (U100)"
        private set

    /**
     * Reset the cached trie pointer. Call when text is cleared or backspace is pressed.
     */
    fun resetTrieCache() {
        cachedTriePrefix = ""
        cachedTrieNode = null
    }

    /**
     * Main entry point: calculate prediction scores for all Thai characters
     * given the current typed text.
     *
     * @param text The complete text typed so far
     * @return Map of character → score (higher = more likely)
     */
    fun calculateScores(text: String): Map<String, Double> {
        val len = text.length
        val last1 = if (len >= 1) text.substring(len - 1) else ""
        val last2 = if (len >= 2) text.substring(len - 2) else ""
        val isSpace = last1 == " "
        val lastCharBeforeSpace = if (len >= 2) text.substring(len - 2, len - 1) else ""

        // Split text by spaces to find current word chunk
        val parts = text.split(" ")
        val lastChunk = parts.last()
        val previousChunk = if (parts.size > 1) parts[parts.size - 2] else ""

        // Tokenize the current chunk using greedy dictionary matching
        var activePrefix = ""
        var activeWordsArray: List<String> = emptyList()

        if (lastChunk.isNotEmpty()) {
            val result = tokenizeGreedy(lastChunk)
            activePrefix = result.prefix
            activeWordsArray = result.words
        } else if (isSpace && previousChunk.isNotEmpty()) {
            val result = tokenizeGreedy(previousChunk)
            activeWordsArray = result.words
        }

        // ── Determine State ──
        val state = when {
            len == 0 -> 1
            isSpace -> 7
            activePrefix.isEmpty() -> 5
            activePrefix.length == 1 -> 2
            activePrefix.length == 2 -> 3
            else -> 4  // activePrefix.length >= 3
        }

        val W = (STATE_WEIGHTS[state] ?: STATE_WEIGHTS[1]!!).mutableCopy()
        engineStatus = STATE_WEIGHTS[state]?.let {
            "State $state (U${it.U}, B${it.B}, T${it.T}, D${it.D}, WB${it.WB}, SN${it.SN})"
        } ?: "Unknown State"

        // ── Calculate Sub-Engine Scores ──
        val sU = getUnigramScores()
        val sB = getBigramScores(last1)
        val sT = getTrigramScores(last2)
        val sD = getDictScores(activePrefix, sT, sB, sU)
        val sWB = getWordBigramScores(activeWordsArray)
        val sSN = getSpaceNgramScores(lastCharBeforeSpace)

        // ── OOV Decay: if prefix exists but no dict matches, transfer D weight to T ──
        val hasDictScores = sD.isNotEmpty()
        if (activePrefix.isNotEmpty() && !hasDictScores) {
            W.T += W.D
            W.D = 0
            engineStatus += " [OOV Decay ⚠️]"
        }

        // ── Calculate effective weights (zero out if sub-engine has no data) ──
        val curWU = if (sU.isNotEmpty()) W.U else 0
        val curWB = if (sB.isNotEmpty()) W.B else 0
        val curWT = if (sT.isNotEmpty()) W.T else 0
        val curWD = if (hasDictScores) W.D else 0
        val curWWB = if (sWB.isNotEmpty()) W.WB else 0
        val curWSN = if (sSN.isNotEmpty()) W.SN else 0

        var sumW = curWU + curWB + curWT + curWD + curWWB + curWSN

        // Fallback: if all sub-engines are empty, use pure unigram
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

        // ── Merge Scores ──
        val finalScores = HashMap<String, Double>()

        fun mergeScores(source: Map<String, Double>, weightRatio: Double) {
            if (weightRatio == 0.0) return
            for ((c, score) in source) {
                finalScores[c] = (finalScores[c] ?: 0.0) + (score * weightRatio)
            }
        }

        mergeScores(effectiveSU, effectiveCurWU.toDouble() / sumW)
        mergeScores(sB, curWB.toDouble() / sumW)
        mergeScores(sT, curWT.toDouble() / sumW)
        mergeScores(sD, curWD.toDouble() / sumW)
        mergeScores(sWB, curWWB.toDouble() / sumW)
        mergeScores(sSN, curWSN.toDouble() / sumW)

        // ── Post-Processing: Pattern Penalty ──
        applyPatternPenalty(finalScores, last2, last1)

        // ── Post-Processing: Echo Booster (Chat Profile) ──
        applyEchoBooster(finalScores, text)

        // ── Post-Processing: Vowel Booster (States 2-3) ──
        applyVowelBooster(finalScores, state)

        // ── Post-Processing: Soft Anchor Booster ──
        applySoftAnchorBooster(finalScores)

        // ── Post-Processing: Unigram Tie-breaker ──
        applyUnigramTiebreaker(finalScores)

        // ── Post-Processing: Illegal Start Penalty (States 1, 7) ──
        applyIllegalStartPenalty(finalScores, state)

        return finalScores
    }

    // ═══════════════════════════════════════
    // Sub-Engine Score Calculators
    // ═══════════════════════════════════════

    private fun getUnigramScores(): Map<String, Double> {
        val unigram = repo.unigram
        if (unigram.isEmpty()) return emptyMap()
        val raw = HashMap<String, Double>(unigram.size)
        unigram.forEachIndexed { index, c ->
            raw[c] = (unigram.size - index).toDouble()
        }
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

    private fun getDictScores(
        prefix: String,
        sT: Map<String, Double>,
        sB: Map<String, Double>,
        sU: Map<String, Double>
    ): Map<String, Double> {
        if (prefix.isEmpty()) {
            cachedTriePrefix = ""
            cachedTrieNode = repo.trieDictRoot
            return emptyMap()
        }

        // Incremental trie walk using cached pointer
        val node: TrieNode?
        if (cachedTrieNode != null
            && prefix.length == cachedTriePrefix.length + 1
            && prefix.startsWith(cachedTriePrefix)
        ) {
            node = cachedTrieNode!![prefix.last()]
        } else if (cachedTrieNode != null && prefix == cachedTriePrefix) {
            node = cachedTrieNode
        } else {
            // Full walk from root
            var current = repo.trieDictRoot
            for (c in prefix) {
                current = current?.get(c)
                if (current == null) break
            }
            node = current
        }

        cachedTriePrefix = prefix
        cachedTrieNode = node

        if (node == null) return emptyMap()

        val raw = HashMap<String, Double>()
        for ((nextChar, _) in node.children) {
            val charStr = nextChar.toString()
            raw[charStr] = sT[charStr] ?: sB[charStr] ?: sU[charStr] ?: 1.0
        }
        return normalizeScores(raw)
    }

    private fun getWordBigramScores(wordsArray: List<String>): Map<String, Double> {
        if (wordsArray.isEmpty()) return emptyMap()
        val hybridWordTrie = repo.hybridWordTrie
        if (hybridWordTrie.isEmpty()) return emptyMap()

        val lastWord = wordsArray.last()
        val prevWord = if (wordsArray.size > 1) wordsArray[wordsArray.size - 2] else null

        val id2 = repo.reverseWordMap[lastWord] ?: return emptyMap()
        val id1 = if (prevWord != null) repo.reverseWordMap[prevWord] else null

        // Try trigram context first (id1 → id2 → next), then fallback to bigram (_base → id2 → next)
        var nextWordNodes: Map<String, Int>? = null

        if (id1 != null) {
            val contextNode = hybridWordTrie[id1]?.get(id2)
            nextWordNodes = if (contextNode != null && contextNode.isNotEmpty()) {
                contextNode
            } else {
                hybridWordTrie["_base"]?.get(id2)
            }
        }

        if (nextWordNodes == null) {
            nextWordNodes = hybridWordTrie["_base"]?.get(id2)
        }

        if (nextWordNodes == null) return emptyMap()

        val raw = HashMap<String, Double>()
        val wordIdMap = repo.wordIdMap

        for ((nextId, freq) in nextWordNodes) {
            val wordIndex = nextId.toIntOrNull() ?: continue
            if (wordIndex < 0 || wordIndex >= wordIdMap.size) continue
            val wordStr = wordIdMap[wordIndex]
            if (wordStr.isNotEmpty()) {
                val firstChar = wordStr[0].toString()
                val currentScore = raw[firstChar] ?: 0.0
                if (freq > currentScore) {
                    raw[firstChar] = freq.toDouble()
                }
            }
        }
        return normalizeScores(raw)
    }

    private fun getSpaceNgramScores(lastCharBeforeSpace: String): Map<String, Double> {
        if (lastCharBeforeSpace.isEmpty()) return emptyMap()
        val ctx = "$lastCharBeforeSpace "
        return parseNGramList(repo.spaceNgram[ctx])
    }

    // ═══════════════════════════════════════
    // Post-Processing
    // ═══════════════════════════════════════

    private fun applyPatternPenalty(
        scores: HashMap<String, Double>,
        last2: String,
        last1: String
    ) {
        if (last2.length != 2) return
        val charMap = repo.charMap
        val ctxTag = ThaiCharUtil.getContextTag(last2, charMap) ?: return
        val badTags = repo.patternPenalty[ctxTag] ?: return
        if (badTags.isEmpty()) return

        val rules = repo.activeProfile.rules
        for (c in scores.keys.toList()) {
            val charTag = charMap[c] ?: "O"
            if (charTag in badTags) {
                if (rules.allowEcho && c == last1) {
                    scores[c] = (scores[c] ?: 0.0) * rules.echoImmunityRatio
                } else {
                    scores[c] = (scores[c] ?: 0.0) * 0.1
                }
            }
        }
    }

    private fun applyEchoBooster(scores: HashMap<String, Double>, text: String) {
        val rules = repo.activeProfile.rules
        if (!rules.allowEcho || text.isEmpty()) return

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
            if (realLastChar.isEmpty()) {
                realLastChar = cs
                repeatCount = 1
            } else if (cs == realLastChar) {
                repeatCount++
            } else {
                break
            }
        }

        if (realLastChar.isNotEmpty()) {
            val isDragging = repeatCount >= 2 || (hasSpaceInBetween && repeatCount >= 1)
            val echoBuff: Double = when {
                rules.echoHardcapChars.contains(realLastChar) -> rules.echoHardcapBuff
                isDragging -> 999.0
                else -> rules.echoBaseBuff
            }
            scores[realLastChar] = (scores[realLastChar] ?: 0.0) + echoBuff
        }
    }

    private fun applyVowelBooster(scores: HashMap<String, Double>, state: Int) {
        val rules = repo.activeProfile.rules
        if (rules.vowelBoosterChars.isEmpty()) return
        if (state != 2 && state != 3) return

        for (v in rules.vowelBoosterChars) {
            if (scores.containsKey(v)) {
                scores[v] = (scores[v] ?: 0.0) + rules.vowelBoosterBuff
            }
        }
    }

    private fun applySoftAnchorBooster(scores: HashMap<String, Double>) {
        val rules = repo.activeProfile.rules
        for (v in rules.softAnchorChars) {
            scores[v] = (scores[v] ?: 0.0) + rules.softAnchorBuff
        }
    }

    private fun applyUnigramTiebreaker(scores: HashMap<String, Double>) {
        val unigram = repo.unigram
        for (i in unigram.indices) {
            val c = unigram[i]
            scores[c] = (scores[c] ?: 0.0) + ((unigram.size - i) * 0.001)
        }
    }

    private fun applyIllegalStartPenalty(scores: HashMap<String, Double>, state: Int) {
        if (state != 1 && state != 7) return
        val rules = repo.activeProfile.rules
        for (c in rules.illegalStartChars) {
            scores[c] = rules.illegalStartPenalty
        }
    }

    // ═══════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════

    /**
     * Greedy dictionary tokenization: find longest matching words from left to right.
     */
    private fun tokenizeGreedy(chunk: String): TokenizeResult {
        var ptr = 0
        val words = mutableListOf<String>()
        var prefix = ""

        while (ptr < chunk.length) {
            var foundWord: String? = null
            for (len in (chunk.length - ptr) downTo 1) {
                val cand = chunk.substring(ptr, ptr + len)
                if (isWordInTrie(cand)) {
                    foundWord = cand
                    break
                }
            }
            if (foundWord != null) {
                words.add(foundWord)
                ptr += foundWord.length
            } else {
                prefix = chunk.substring(ptr)
                break
            }
        }
        return TokenizeResult(words, prefix)
    }

    private data class TokenizeResult(val words: List<String>, val prefix: String)

    private fun isWordInTrie(word: String): Boolean {
        var node = repo.trieDictRoot ?: return false
        for (c in word) {
            node = node[c] ?: return false
        }
        return node.isEndOfWord
    }

    private fun parseNGramList(list: Any?): Map<String, Double> {
        if (list == null) return emptyMap()
        val raw = HashMap<String, Double>()
        when (list) {
            is List<*> -> {
                val strList = list.filterIsInstance<String>()
                strList.forEachIndexed { index, c ->
                    raw[c] = (strList.size - index).toDouble()
                }
            }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = list as Map<String, Number>
                for ((k, v) in map) {
                    raw[k] = v.toDouble()
                }
            }
        }
        return normalizeScores(raw)
    }

    private fun normalizeScores(raw: Map<String, Double>): Map<String, Double> {
        if (raw.isEmpty()) return emptyMap()
        var max = 0.0
        for (v in raw.values) {
            if (v > max) max = v
        }
        if (max == 0.0) return emptyMap()
        val result = HashMap<String, Double>(raw.size)
        for ((k, v) in raw) {
            result[k] = (v / max) * 100.0
        }
        return result
    }
}
