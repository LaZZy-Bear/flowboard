package com.flowboard.ime.engine

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.KeySlots

class LayoutManager(private val repo: FlowboardRepository) {

    companion object {
        // Partner Key Pairing configuration
        val PARTNER_KEY = mapOf(
            "key_1" to "key_2", "key_2" to "key_1",  // top-left ↔ top-center
            "key_3" to "key_6", "key_6" to "key_3",  // top-right ↔ mid-right
            "key_4" to "key_7", "key_7" to "key_4",  // mid-left ↔ bot-left
            "key_8" to "key_9", "key_9" to "key_8",  // bot-center ↔ bot-right
            "key_5" to null                           // center — no partner
        )
    }

    data class Candidate(val char: String, val defaultSlot: String, val score: Double)

    fun assignLayout(scores: Map<String, Double>): Map<String, KeySlots> {
        if (repo.layoutStrategy == "TH") {
            return assignLayoutTH(scores)
        }

        // Phase 1: Base layout — place chars at homeKey
        val (baseLayout, charsByHome) = buildBaseLayout(scores)

        // Phase 2: Partner swap — language-specific strategy
        val evicted = partnerSwapEN(baseLayout, charsByHome, scores)

        // Phase 3: Fill unrendered — rescue chars with score > 0
        return fillUnrenderedChars(baseLayout, scores, evicted)
    }

    private fun buildBaseLayout(scores: Map<String, Double>): Pair<MutableMap<String, MutableMap<String, String>>, Map<String, List<Candidate>>> {
        val newLayout = mutableMapOf<String, MutableMap<String, String>>()
        for (i in 1..9) {
            newLayout["key_$i"] = mutableMapOf("tap" to "", "up" to "", "left" to "", "right" to "", "down" to i.toString())
        }

        val stickyChar = repo.stickyChar
        val stickyKeyId = repo.lastActionKeyId
        val stickySlot = repo.lastActionSlot
        if (stickyChar != null && stickyKeyId != null && stickySlot != null) {
            newLayout[stickyKeyId]?.set(stickySlot, stickyChar)
        }

        val charactersByHomeKey = mutableMapOf<String, MutableList<Candidate>>()
        for (i in 1..9) {
            charactersByHomeKey["key_$i"] = mutableListOf()
        }

        for ((char, info) in repo.masterLayout) {
            if (stickyChar != null && char == stickyChar) continue
            val home = info.homeKey
            val score = scores[char] ?: 0.0
            charactersByHomeKey[home]?.add(Candidate(char, info.defaultSlot, score))
        }

        for (k in charactersByHomeKey.keys) {
            charactersByHomeKey[k]?.sortByDescending { it.score }
        }

        for (i in 1..9) {
            val keyId = "key_$i"
            val candidates = charactersByHomeKey[keyId] ?: continue
            if (candidates.isEmpty()) continue

            val topWinner = candidates[0]
            val keyMap = newLayout[keyId] ?: continue
            if (topWinner.score > 0) {
                if ((keyMap["tap"] ?: "").isEmpty()) {
                    keyMap["tap"] = topWinner.char
                }
                if (topWinner.defaultSlot != "tap" && topWinner.defaultSlot != "down") {
                    val defTapObj = candidates.find { it.defaultSlot == "tap" }
                    if (defTapObj != null && (keyMap[topWinner.defaultSlot] ?: "").isEmpty()) {
                        keyMap[topWinner.defaultSlot] = defTapObj.char
                    }
                }
                val slots = listOf("up", "left", "right")
                for (slot in slots) {
                    if ((keyMap[slot] ?: "").isEmpty()) {
                        val defCharObj = candidates.find { it.defaultSlot == slot }
                        if (defCharObj != null && defCharObj.char != topWinner.char) {
                            keyMap[slot] = defCharObj.char
                        }
                    }
                }
            } else {
                for (c in candidates) {
                    if (c.defaultSlot != "down" && (keyMap[c.defaultSlot] ?: "").isEmpty()) {
                        keyMap[c.defaultSlot] = c.char
                    }
                }
            }
        }

        return Pair(newLayout, charactersByHomeKey)
    }

    private fun assignLayoutTH(scores: Map<String, Double>): Map<String, KeySlots> {
        val newLayout = mutableMapOf<String, MutableMap<String, String>>()
        for (i in 1..9) {
            newLayout["key_$i"] = mutableMapOf("tap" to "", "up" to "", "left" to "", "right" to "", "down" to i.toString())
        }

        val stickyChar = repo.stickyChar
        val stickyKeyId = repo.lastActionKeyId
        val stickySlot = repo.lastActionSlot
        if (stickyChar != null && stickyKeyId != null && stickySlot != null) {
            newLayout[stickyKeyId]?.set(stickySlot, stickyChar)
        }

        val candidatesByKey = mutableMapOf<String, MutableList<Candidate>>()
        for (i in 1..9) {
            candidatesByKey["key_$i"] = mutableListOf()
        }

        for ((char, info) in repo.masterLayout) {
            if (stickyChar != null && char == stickyChar) continue
            val score = scores[char] ?: 0.0
            candidatesByKey[info.homeKey]?.add(Candidate(char, info.defaultSlot, score))
        }

        for (k in candidatesByKey.keys) {
            candidatesByKey[k]?.sortByDescending { it.score }
        }

        val wonTap = mutableSetOf<String>()

        // 1. Initial Tap assignment
        val initialTap = mutableMapOf<String, Candidate>()
        for (i in 1..9) {
            val keyId = "key_$i"
            if (stickyChar != null && stickyKeyId == keyId && stickySlot == "tap") {
                initialTap[keyId] = Candidate(stickyChar, "tap", Double.POSITIVE_INFINITY)
                continue
            }

            val candidates = candidatesByKey[keyId] ?: continue
            if (candidates.isNotEmpty()) {
                val topCandidate = candidates[0]
                if (topCandidate.score > 0.0) {
                    initialTap[keyId] = topCandidate
                } else {
                    val defTap = candidates.find { it.defaultSlot == "tap" }
                    if (defTap != null) initialTap[keyId] = defTap
                }
            }
        }

        // 2. Partner Swap for Tap (x1.12)
        val partnerSwapWins = mutableMapOf<String, Candidate>()
        for (i in 1..9) {
            val keyId = "key_$i"
            val partnerKeyId = PARTNER_KEY[keyId] ?: continue
            val candidates = candidatesByKey[keyId] ?: continue
            if (candidates.size > 1) {
                val runnerUp = candidates[1]
                if (runnerUp.score > 0.0) {
                    val pTap = initialTap[partnerKeyId]
                    val pTapScore = pTap?.score ?: 0.0
                    if (runnerUp.score > pTapScore * 1.12) {
                        partnerSwapWins[partnerKeyId] = runnerUp
                    }
                }
            }
        }

        val freeAgents = mutableListOf<Candidate>()

        for (i in 1..9) {
            val keyId = "key_$i"
            val currentTap = initialTap[keyId]
            val swapWin = partnerSwapWins[keyId]
            val keyMap = newLayout[keyId] ?: continue
            if (swapWin != null) {
                keyMap["tap"] = swapWin.char
                wonTap.add(swapWin.char)
            } else {
                if (currentTap != null) {
                    keyMap["tap"] = currentTap.char
                    wonTap.add(currentTap.char)
                }
            }

            // 3. Identify free agents (default tap character that is NOT on tap)
            val defaultTapCand = candidatesByKey[keyId]?.find { it.defaultSlot == "tap" }
            val assignedTap = keyMap["tap"] ?: ""
            if (defaultTapCand != null && assignedTap != defaultTapCand.char) {
                freeAgents.add(defaultTapCand)
            }
        }

        // 4. Swipe Group Competition
        val swipeSlots = listOf("up", "left", "right")
        for (slot in swipeSlots) {
            val homeWinners = mutableMapOf<String, Candidate>()
            val homeLosers = mutableMapOf<String, List<Candidate>>()

            for (i in 1..9) {
                val keyId = "key_$i"
                val competitors = candidatesByKey[keyId]?.filter {
                    it.defaultSlot == slot && !wonTap.contains(it.char)
                } ?: emptyList()

                if (competitors.isNotEmpty()) {
                    homeWinners[keyId] = competitors[0]
                    homeLosers[keyId] = competitors.drop(1)
                }
            }

            for (i in 1..9) {
                val keyId = "key_$i"
                val winner = homeWinners[keyId]
                if (winner != null) {
                    val keyMap = newLayout[keyId] ?: continue
                    if ((keyMap[slot] ?: "").isEmpty()) { // Protect Sticky!
                        keyMap[slot] = winner.char
                    }
                }
            }

            // 5. Swipe Partner Migration (x1.12)
            val migrations = mutableMapOf<String, Candidate>()
            for (i in 1..9) {
                val keyId = "key_$i"
                val partnerKeyId = PARTNER_KEY[keyId] ?: continue
                val losers = homeLosers[keyId] ?: emptyList()
                if (losers.isNotEmpty()) {
                    val bestLoser = losers[0]
                    if (bestLoser.score > 0.0) {
                        val partnerMap = newLayout[partnerKeyId] ?: continue
                        if ((partnerMap[slot] ?: "").isNotEmpty() && !homeWinners.containsKey(partnerKeyId)) {
                            continue // Partner slot is pinned by sticky
                        }
                        val partnerWinner = homeWinners[partnerKeyId]
                        val partnerWinnerScore = partnerWinner?.score ?: 0.0
                        if (bestLoser.score > partnerWinnerScore * 1.12) {
                            migrations[partnerKeyId] = bestLoser
                        }
                    }
                }
            }

            for ((targetKey, bestLoser) in migrations) {
                val targetMap = newLayout[targetKey] ?: continue
                if ((targetMap[slot] ?: "").isEmpty()) { // Protect Sticky!
                    targetMap[slot] = bestLoser.char
                }
            }
        }

        // 6. Free Agent Placement (weakest swipe slot in home key)
        freeAgents.forEach { fa ->
            val entry = repo.masterLayout[fa.char] ?: return@forEach
            val keyId = entry.homeKey

            var minScore = Double.MAX_VALUE
            var weakestSlot: String? = null

            for (slot in swipeSlots) {
                val occupantChar = newLayout[keyId]?.get(slot) ?: ""
                val occupantScore = if (occupantChar.isNotEmpty()) {
                    if (occupantChar == stickyChar) Double.MAX_VALUE else (scores[occupantChar] ?: 0.0)
                } else 0.0

                if (occupantScore < minScore) {
                    minScore = occupantScore
                    weakestSlot = slot
                }
            }

            if (weakestSlot != null && fa.score > minScore) {
                val keyMap = newLayout[keyId] ?: return@forEach
                if ((keyMap[weakestSlot] ?: "").isEmpty() || (keyMap[weakestSlot] ?: "") == fa.char) { // protect sticky
                    keyMap[weakestSlot] = fa.char
                }
            }
        }

        // 7. Missing Characters Fallback
        return fillUnrenderedChars(newLayout, scores, emptyList())
    }

    private fun partnerSwapEN(
        newLayout: MutableMap<String, MutableMap<String, String>>,
        charactersByHomeKey: Map<String, List<Candidate>>,
        scores: Map<String, Double>
    ): List<String> {
        val stickyChar = repo.stickyChar
        for (i in 1..9) {
            val keyId = "key_$i"
            val partnerKeyId = PARTNER_KEY[keyId] ?: continue

            val candidates = charactersByHomeKey[keyId] ?: continue
            if (candidates.size > 1) {
                val runnerUp = candidates[1]
                if (runnerUp.score > 0) {
                    val partnerTapChar = newLayout[partnerKeyId]?.get("tap") ?: ""
                    var partnerTapScore = if (partnerTapChar.isNotEmpty()) (scores[partnerTapChar] ?: 0.0) else 0.0
                    if (stickyChar != null && partnerTapChar == stickyChar) {
                        partnerTapScore = Double.MAX_VALUE
                    }

                    if (runnerUp.score > partnerTapScore * 1.2) {
                        var runnerUpOldSlot: String? = null
                        val keySlotsMap = newLayout[keyId] ?: continue
                        for ((slot, char) in keySlotsMap) {
                            if (char == runnerUp.char) {
                                runnerUpOldSlot = slot
                                keySlotsMap[slot] = ""
                                break
                            }
                        }

                        newLayout[partnerKeyId]?.set("tap", runnerUp.char)

                        if (partnerTapChar.isNotEmpty()) {
                            var minScore = Double.MAX_VALUE
                            var weakestSlot: String? = null
                            var weakestChar: String? = null

                            val slots = listOf("up", "left", "right")
                            val partnerSlotsMap = newLayout[partnerKeyId] ?: continue
                            for (slot in slots) {
                                val occupant = partnerSlotsMap[slot] ?: ""
                                if (stickyChar != null && occupant == stickyChar) continue
                                if (occupant.isEmpty()) {
                                    weakestSlot = slot
                                    weakestChar = ""
                                    minScore = -1.0
                                    break
                                } else {
                                    val occupantScore = scores[occupant] ?: 0.0
                                    if (occupantScore < minScore) {
                                        minScore = occupantScore
                                        weakestSlot = slot
                                        weakestChar = occupant
                                    }
                                }
                            }

                            if (weakestSlot != null) {
                                partnerSlotsMap[weakestSlot] = partnerTapChar

                                if (!weakestChar.isNullOrEmpty() && runnerUpOldSlot != null) {
                                    keySlotsMap[runnerUpOldSlot] = weakestChar
                                }
                            }
                        }
                    }
                }
            }
        }
        return emptyList()
    }

    data class EvictResult(val slot: String, val char: String, val score: Double)

    private fun fillUnrenderedChars(
        newLayout: MutableMap<String, MutableMap<String, String>>,
        scores: Map<String, Double>,
        evictedFromTap: List<String>
    ): Map<String, KeySlots> {
        val currentlyRendered = mutableSetOf<String>()
        for (k in newLayout.keys) {
            val keyMap = newLayout[k] ?: continue
            for ((slot, char) in keyMap) {
                if (slot != "down" && char.isNotEmpty()) {
                    currentlyRendered.add(char)
                }
            }
        }

        val unrenderedScoredChars = mutableListOf<String>()
        for (char in scores.keys) {
            val score = scores[char] ?: 0.0
            if (score > 0 && repo.masterLayout.containsKey(char) && !currentlyRendered.contains(char)) {
                unrenderedScoredChars.add(char)
            }
        }
        for (char in evictedFromTap) {
            if (!currentlyRendered.contains(char)) {
                unrenderedScoredChars.add(char)
            }
        }

        val uniqueUnrendered = unrenderedScoredChars.distinct().toMutableList()
        uniqueUnrendered.sortByDescending { scores[it] ?: 0.0 }

        fun findBestSlotToEvict(targetKey: String?, checkZeroOnly: Boolean): EvictResult? {
            if (targetKey == null) return null
            val slots = listOf("up", "left", "right", "tap")
            var bestSlot: String? = null
            var minScore = Double.MAX_VALUE
            var minChar = ""

            val keyMap = newLayout[targetKey] ?: return null
            for (slot in slots) {
                val occupant = keyMap[slot] ?: ""
                var occupantScore = if (occupant.isNotEmpty()) (scores[occupant] ?: 0.0) else 0.0
                if (repo.stickyChar != null && occupant == repo.stickyChar) {
                    occupantScore = Double.MAX_VALUE // protect sticky key
                }

                if (checkZeroOnly) {
                    if (occupantScore == 0.0) {
                        return EvictResult(slot, occupant, 0.0)
                    }
                } else {
                    if (occupant.isNotEmpty() && occupantScore < minScore) {
                        minScore = occupantScore
                        bestSlot = slot
                        minChar = occupant
                    }
                }
            }
            return if (checkZeroOnly) null else if (bestSlot != null) EvictResult(bestSlot, minChar, minScore) else null
        }

        for (char in uniqueUnrendered) {
            val entry = repo.masterLayout[char] ?: continue
            val homeKey = entry.homeKey
            val partnerKey = PARTNER_KEY[homeKey]

            if (repo.layoutStrategy == "EN") {
                var placed = false
                fun fillEmpty(targetKey: String?): Boolean {
                    if (targetKey == null) return false
                    val slots = listOf("up", "left", "right", "tap")
                    val keyMap = newLayout[targetKey] ?: return false
                    for (slot in slots) {
                        if ((keyMap[slot] ?: "").isEmpty()) {
                            keyMap[slot] = char
                            return true
                        }
                    }
                    return false
                }
                placed = fillEmpty(homeKey)
                if (!placed && partnerKey != null) placed = fillEmpty(partnerKey)

                if (!placed) {
                    var bestSlot: String? = null
                    var minScore = Double.MAX_VALUE
                    var chosenKey = homeKey

                    fun checkSlots(key: String?) {
                        if (key == null) return
                        val slots = listOf("up", "left", "right", "tap")
                        val keyMap = newLayout[key] ?: return
                        for (slot in slots) {
                            val occupant = keyMap[slot] ?: ""
                            var occupantScore = if (occupant.isNotEmpty()) (scores[occupant] ?: 0.0) else 0.0
                            if (repo.stickyChar != null && occupant == repo.stickyChar) {
                                occupantScore = Double.MAX_VALUE // protect sticky key
                            }
                            if (occupantScore < minScore) {
                                minScore = occupantScore
                                bestSlot = slot
                                chosenKey = key
                            }
                        }
                    }
                    checkSlots(homeKey)
                    checkSlots(partnerKey)

                    if (bestSlot != null && (scores[char] ?: 0.0) > minScore) {
                        newLayout[chosenKey]?.set(bestSlot!!, char)
                    }
                }
            } else {
                var target = findBestSlotToEvict(homeKey, true)
                var chosenKey = homeKey
                if (target == null && partnerKey != null) {
                    target = findBestSlotToEvict(partnerKey, true)
                    chosenKey = partnerKey
                }
                if (target == null) {
                    target = findBestSlotToEvict(homeKey, false)
                    chosenKey = homeKey
                }
                if (target == null && partnerKey != null) {
                    target = findBestSlotToEvict(partnerKey, false)
                    chosenKey = partnerKey
                }
                if (target?.slot != null) {
                    val charScore = scores[char] ?: 0.0
                    if (charScore > target.score) {
                        newLayout[chosenKey]?.set(target.slot, char)
                    }
                }
            }
        }

        val finalResult = mutableMapOf<String, KeySlots>()
        for (i in 1..9) {
            val key = "key_$i"
            val map = newLayout[key]!!
            finalResult[key] = KeySlots(
                tap = map["tap"] ?: "",
                up = map["up"] ?: "",
                left = map["left"] ?: "",
                right = map["right"] ?: "",
                down = map["down"] ?: ""
            )
        }

        return finalResult
    }

    fun assignMissingLayout(normalLayout: Map<String, KeySlots>): Map<String, KeySlots> {
        val visibleChars = mutableSetOf<String>()
        for (i in 1..9) {
            val keyId = "key_$i"
            val k = normalLayout[keyId] ?: continue
            if (k.tap.isNotEmpty()) visibleChars.add(k.tap)
            if (k.up.isNotEmpty()) visibleChars.add(k.up)
            if (k.left.isNotEmpty()) visibleChars.add(k.left)
            if (k.right.isNotEmpty()) visibleChars.add(k.right)
        }

        val allThaiChars = repo.charMap.values.filter { c ->
            c != " " && !c.matches(Regex("^[0-9]$")) && !c.matches(Regex("^[๐-๙]$"))
        }

        val missingChars = allThaiChars.filter { it !in visibleChars }
        val altLayout = mutableMapOf<String, KeySlots>()
        val thaiNumbers = listOf("", "๑", "๒", "๓", "๔", "๕", "๖", "๗", "๘", "๙")
        for (i in 1..9) {
            altLayout["key_$i"] = KeySlots(down = thaiNumbers[i])
        }

        val slotsOrder = listOf("tap", "up", "left", "right")
        var missingIndex = 0

        for (slot in slotsOrder) {
            for (i in 1..9) {
                if (missingIndex < missingChars.size) {
                    val keyId = "key_$i"
                    val keySlotObj = altLayout[keyId] ?: continue
                    when (slot) {
                        "tap" -> keySlotObj.tap = missingChars[missingIndex]
                        "up" -> keySlotObj.up = missingChars[missingIndex]
                        "left" -> keySlotObj.left = missingChars[missingIndex]
                        "right" -> keySlotObj.right = missingChars[missingIndex]
                    }
                    missingIndex++
                }
            }
        }

        return altLayout
    }
}
