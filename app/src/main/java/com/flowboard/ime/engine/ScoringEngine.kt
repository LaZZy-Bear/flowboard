package com.flowboard.ime.engine

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.EngineWeights
import com.flowboard.ime.data.models.TrieNode
import com.flowboard.ime.data.models.WordBigramEntry
import com.flowboard.ime.util.ThaiCharUtil

class ScoringEngine(private val repo: FlowboardRepository) {

    companion object {
        // Updated Weights to match P21 V7
        private val STATE_WEIGHTS = mapOf(
            1 to EngineWeights(U = 87, B = 9, T = 15, D = 71, WB = 8, SN = 0),
            2 to EngineWeights(U = 0, B = 94, T = 87, D = 37, WB = 0, SN = 0),
            3 to EngineWeights(U = 0, B = 10, T = 100, D = 35, WB = 0, SN = 3),
            4 to EngineWeights(U = 0, B = 3, T = 11, D = 100, WB = 0, SN = 0),
            5 to EngineWeights(U = 26, B = 4, T = 100, D = 8, WB = 73, SN = 6),
            7 to EngineWeights(U = 8, B = 5, T = 19, D = 71, WB = 100, SN = 2)
        )
    }

    private var cachedTriePrefix: String = ""
    private var cachedTrieNode: TrieNode? = null

    var engineStatus: String = "State 1 (Start)"
        private set

    fun resetTrieCache() {
        cachedTriePrefix = ""
        cachedTrieNode = null
    }

    fun calculateScores(text: String): Map<String, Double> {
        val engineText = if (repo.activeLang == "EN") text.lowercase() else text
        val len = engineText.length
        val last1 = if (len >= 1) engineText.substring(len - 1) else ""
        val last2 = if (len >= 2) engineText.substring(len - 2) else ""
        val isSpace = last1 == " "
        val lastCharBeforeSpace = if (len >= 2) engineText.substring(len - 2, len - 1) else ""

        var activePrefix = ""
        var activeWordsArray: List<String> = emptyList()

        if (repo.activeLang == "EN") {
            val enParts = engineText.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (engineText.endsWith(" ") || engineText.isEmpty()) {
                activePrefix = ""
                activeWordsArray = enParts
            } else {
                activePrefix = enParts.lastOrNull() ?: ""
                activeWordsArray = if (enParts.isNotEmpty()) enParts.dropLast(1) else emptyList()
            }
        } else {
            val parts = engineText.split(" ")
            val lastChunk = parts.last()
            val previousChunk = if (parts.size > 1) parts[parts.size - 2] else ""

            if (lastChunk.isNotEmpty()) {
                val result = tokenizeGreedy(lastChunk)
                activePrefix = result.prefix
                activeWordsArray = result.words
            } else if (isSpace && previousChunk.isNotEmpty()) {
                val result = tokenizeGreedy(previousChunk)
                activeWordsArray = result.words
            }
        }

        val state = when {
            len == 0 -> 1
            isSpace -> 7
            activePrefix.isEmpty() -> 5
            activePrefix.length == 1 -> 2
            activePrefix.length == 2 -> 3
            else -> 4
        }

        val fallbackWeights = EngineWeights(U = 100, B = 0, T = 0, D = 0, WB = 0, SN = 0)
        val stateWeightsObj = STATE_WEIGHTS[state] ?: fallbackWeights
        val W = stateWeightsObj.mutableCopy()
        engineStatus = STATE_WEIGHTS[state]?.let {
            "State $state (U${it.U}, B${it.B}, T${it.T}, D${it.D}, WB${it.WB}, SN${it.SN})"
        } ?: "Fallback"

        val sU = getUnigramScores()
        val sB = getBigramScores(last1)
        val sT = getTrigramScores(last2)
        val sD = getDictScores(activePrefix, sT, sB, sU)
        val sWB = getWordBigramScores(activeWordsArray)
        val sSN = getSpaceNgramScores(lastCharBeforeSpace)

        val hasDictScores = sD.isNotEmpty()
        if (activePrefix.isNotEmpty() && !hasDictScores) {
            W.T += W.D
            W.D = 0
            engineStatus += " [OOV Decay ⚠️]"
        }

        val curWU = if (sU.isNotEmpty()) W.U else 0
        val curWB = if (sB.isNotEmpty()) W.B else 0
        val curWT = if (sT.isNotEmpty()) W.T else 0
        val curWD = if (hasDictScores) W.D else 0
        val curWWB = if (sWB.isNotEmpty()) W.WB else 0
        val curWSN = if (sSN.isNotEmpty()) W.SN else 0

        var sumW = curWU + curWB + curWT + curWD + curWWB + curWSN

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

        // ── Post-Processing ──
        val rules = repo.activeProfile.rules

        if (repo.activeLang == "TH") {
            applyPatternPenalty(finalScores, last2, last1)
        }

        if (rules.allowEcho && len > 0) {
            applyEchoBooster(finalScores, engineText)
        }

        if (repo.activeLang == "TH") {
            if (state == 2 || state == 3) applyVowelBooster(finalScores)
            applySoftAnchorBooster(finalScores)
            if (state == 1 || state == 7) applyIllegalStartPenalty(finalScores)
        }

        applyBonusDict(finalScores)
        applyUnigramTiebreaker(finalScores)

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
            cachedTrieNode = repo.trieDict
            return emptyMap()
        }

        val node: TrieNode?
        if (cachedTrieNode != null
            && prefix.length == cachedTriePrefix.length + 1
            && prefix.startsWith(cachedTriePrefix)
        ) {
            val lastChar = prefix.last().toString()
            val nodeKey = if (repo.activeLang == "EN") lastChar else repo.charReverseMap[lastChar]
            node = if (nodeKey != null) cachedTrieNode!![nodeKey] else null
        } else if (cachedTrieNode != null && prefix == cachedTriePrefix) {
            node = cachedTrieNode
        } else {
            var current = repo.trieDict
            for (c in prefix) {
                val nodeKey = if (repo.activeLang == "EN") c.toString() else repo.charReverseMap[c.toString()]
                if (nodeKey == null) {
                    current = null
                    break
                }
                current = current?.get(nodeKey)
                if (current == null) break
            }
            node = current
        }

        cachedTriePrefix = prefix
        cachedTrieNode = node

        if (node == null) return emptyMap()

        val raw = HashMap<String, Double>()
        for ((nextKey, _) in node.children) {
            if (nextKey != "_w") {
                val realChar = if (repo.activeLang == "EN") nextKey else repo.charMap[nextKey]
                if (realChar != null) {
                    raw[realChar] = sT[realChar] ?: sB[realChar] ?: sU[realChar] ?: 1.0
                }
            }
        }
        return normalizeScores(raw)
    }

    private fun getWordBigramScores(wordsArray: List<String>): Map<String, Double> {
        if (wordsArray.isEmpty()) return emptyMap()
        val cwb = repo.clusteredBigram
        if (cwb.bigram.isEmpty()) return emptyMap()

        val lastWord = wordsArray.last()
        val wordId = repo.wordReverseMap[lastWord] ?: return emptyMap()
        
        val nodeData = cwb.bigram[wordId.toString()] ?: return emptyMap()

        val nextWordIDs: List<Int> = when (nodeData) {
            is WordBigramEntry.DirectList -> nodeData.ids
            is WordBigramEntry.GroupRef -> {
                val groupIds = cwb.groups[nodeData.group] ?: emptyList()
                if (nodeData.extra != null) {
                    val list = groupIds.toMutableList()
                    list.add(nodeData.extra)
                    list
                } else {
                    groupIds
                }
            }
        }

        val raw = HashMap<String, Double>()
        for (i in nextWordIDs.indices) {
            val nextWord = repo.wordList.getOrNull(nextWordIDs[i]) ?: continue
            if (nextWord.isNotEmpty()) {
                val firstChar = nextWord[0].toString()
                val score = ((nextWordIDs.size - i) * 10).toDouble()
                if ((raw[firstChar] ?: 0.0) < score) {
                    raw[firstChar] = score
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
        val tag1 = ThaiCharUtil.getTag(last2[0], repo.thaiCharMap)
        val tag2 = ThaiCharUtil.getTag(last2[1], repo.thaiCharMap)
        val ctxTag = "$tag1-$tag2"
        val badTags = repo.patternPenalty[ctxTag] ?: return
        if (badTags.isEmpty()) return

        val rules = repo.activeProfile.rules
        for (c in scores.keys.toList()) {
            val charTag = repo.thaiCharMap[c] ?: "O"
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
                isDragging -> rules.echoDragBuff
                else -> rules.echoBaseBuff
            }
            scores[realLastChar] = (scores[realLastChar] ?: 0.0) + echoBuff
        }
    }

    private fun applyVowelBooster(scores: HashMap<String, Double>) {
        val rules = repo.activeProfile.rules
        if (rules.vowelBoosterChars.isEmpty()) return
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

    private fun applyIllegalStartPenalty(scores: HashMap<String, Double>) {
        val rules = repo.activeProfile.rules
        for (c in rules.illegalStartChars) {
            scores[c] = rules.illegalStartPenalty
        }
    }

    // ═══════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════

    private fun isWordInTrie(word: String): Boolean {
        var node = repo.trieDict
        for (c in word) {
            val nodeKey = if (repo.activeLang == "EN") c.toString() else repo.charReverseMap[c.toString()]
            if (nodeKey == null || node?.get(nodeKey) == null) return false
            node = node[nodeKey]
        }
        return node?.isEndOfWord == true
    }

    private fun getWordIndex(word: String): Int {
        var node = repo.trieDict
        for (c in word) {
            val nodeKey = if (repo.activeLang == "EN") c.toString() else repo.charReverseMap[c.toString()]
            if (nodeKey == null || node?.get(nodeKey) == null) return -1
            node = node[nodeKey]
        }
        return if (node?.isEndOfWord == true) node.frequency else -1
    }

    private fun tokenizeGreedy(chunk: String): TokenizeResult {
        var ptr = 0
        val words = mutableListOf<String>()
        var tempPrefix = ""

        while (ptr < chunk.length) {
            var bestWord: String? = null
            var bestScore = Double.NEGATIVE_INFINITY
            for (len in chunk.length - ptr downTo 1) {
                val cand = chunk.substring(ptr, ptr + len)
                val wordIdx = getWordIndex(cand)
                if (wordIdx >= 0) {
                    val score = (cand.length * 9261).toDouble() - wordIdx
                    if (score > bestScore) {
                        bestScore = score
                        bestWord = cand
                    }
                }
            }

            if (bestWord != null) {
                if (tempPrefix.isNotEmpty()) tempPrefix = ""
                words.add(bestWord)
                ptr += bestWord.length
            } else {
                tempPrefix += chunk[ptr]
                ptr += 1
            }
        }
        return TokenizeResult(words, tempPrefix)
    }

    private fun applyBonusDict(scores: HashMap<String, Double>) {
        for ((char, bonus) in repo.bonusDict) {
            scores[char] = (scores[char] ?: 0.0) + bonus
        }
    }

    private data class TokenizeResult(val words: List<String>, val prefix: String)

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

    fun isDoubleCharValid(text: String, charToTest: String): Boolean {
        if (text.isEmpty() || charToTest.isEmpty()) return false

        val engineText = if (repo.activeLang == "EN") text.lowercase() else text
        var activePrefix = ""

        if (repo.activeLang == "EN") {
            val enParts = engineText.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            activePrefix = if (engineText.endsWith(" ") || engineText.isEmpty()) "" else enParts.lastOrNull() ?: ""
        } else {
            val parts = engineText.split(" ")
            activePrefix = parts.last()
        }

        if (activePrefix.isEmpty()) return false

        // One-Time Only Constraint
        if (activePrefix.length >= 2) {
            val prevChar = activePrefix[activePrefix.length - 2].toString()
            val currChar = activePrefix[activePrefix.length - 1].toString()
            if (prevChar == charToTest && currChar == charToTest) {
                return false
            }
        }

        val safeCharToTest = if (repo.activeLang == "EN") charToTest.lowercase() else charToTest
        val testPrefix = activePrefix + safeCharToTest

        var isValid = false
        for (startIdx in 0 until testPrefix.length - 1) {
            val subPrefix = testPrefix.substring(startIdx)
            var node = repo.trieDict
            var branchValid = true
            for (c in subPrefix) {
                val nodeKey = if (repo.activeLang == "EN") c.toString() else repo.charReverseMap[c.toString()]
                if (nodeKey == null || node?.get(nodeKey) == null) {
                    branchValid = false
                    break
                }
                node = node[nodeKey]
            }
            if (branchValid) {
                isValid = true
                break
            }
        }
        return isValid
    }
}
