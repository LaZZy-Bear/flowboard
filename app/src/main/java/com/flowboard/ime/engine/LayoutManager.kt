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
        // Phase 1: Base layout — place chars at homeKey
        val (baseLayout, charsByHome) = buildBaseLayout(scores)

        // Phase 2: Partner swap — language-specific strategy
        val evicted = when (repo.layoutStrategy) {
            "EN" -> partnerSwapEN(baseLayout, charsByHome, scores)
            else -> partnerSwapTH(baseLayout, charsByHome, scores)
        }

        // Phase 3: Fill unrendered — rescue chars with score > 0
        return fillUnrenderedChars(baseLayout, scores, evicted)
    }

    private fun buildBaseLayout(scores: Map<String, Double>): Pair<MutableMap<String, MutableMap<String, String>>, Map<String, List<Candidate>>> {
        val newLayout = mutableMapOf<String, MutableMap<String, String>>()
        for (i in 1..9) {
            newLayout["key_$i"] = mutableMapOf("tap" to "", "up" to "", "left" to "", "right" to "", "down" to i.toString())
        }

        val charactersByHomeKey = mutableMapOf<String, MutableList<Candidate>>()
        for (i in 1..9) {
            charactersByHomeKey["key_$i"] = mutableListOf()
        }

        for ((char, info) in repo.masterLayout) {
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
                keyMap["tap"] = topWinner.char
                if (topWinner.defaultSlot != "tap" && topWinner.defaultSlot != "down") {
                    val defTapObj = candidates.find { it.defaultSlot == "tap" }
                    if (defTapObj != null) {
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
                    if (c.defaultSlot != "down") {
                        keyMap[c.defaultSlot] = c.char
                    }
                }
            }
        }

        return Pair(newLayout, charactersByHomeKey)
    }

    private fun partnerSwapTH(
        newLayout: MutableMap<String, MutableMap<String, String>>,
        charactersByHomeKey: Map<String, List<Candidate>>,
        scores: Map<String, Double>
    ): List<String> {
        val evictedFromTap = mutableListOf<String>()
        for (i in 1..9) {
            val keyId = "key_$i"
            val partnerKeyId = PARTNER_KEY[keyId] ?: continue
            val candidates = charactersByHomeKey[keyId] ?: continue
            if (candidates.size > 1) {
                val runnerUp = candidates[1]
                if (runnerUp.score > 0) {
                    val partnerTapChar = newLayout[partnerKeyId]?.get("tap") ?: ""
                    val partnerTapScore = scores[partnerTapChar] ?: 0.0
                    if (runnerUp.score > partnerTapScore) {
                        if (partnerTapChar.isNotEmpty()) evictedFromTap.add(partnerTapChar)
                        newLayout[partnerKeyId]?.set("tap", runnerUp.char)
                        val keySlotsMap = newLayout[keyId] ?: continue
                        for ((slot, char) in keySlotsMap) {
                            if (char == runnerUp.char) {
                                keySlotsMap[slot] = ""
                                break
                            }
                        }
                    }
                }
            }
        }
        return evictedFromTap
    }

    private fun partnerSwapEN(
        newLayout: MutableMap<String, MutableMap<String, String>>,
        charactersByHomeKey: Map<String, List<Candidate>>,
        scores: Map<String, Double>
    ): List<String> {
        for (i in 1..9) {
            val keyId = "key_$i"
            val partnerKeyId = PARTNER_KEY[keyId] ?: continue

            val candidates = charactersByHomeKey[keyId] ?: continue
            if (candidates.size > 1) {
                val runnerUp = candidates[1]
                if (runnerUp.score > 0) {
                    val partnerTapChar = newLayout[partnerKeyId]?.get("tap") ?: ""
                    val partnerTapScore = if (partnerTapChar.isNotEmpty()) (scores[partnerTapChar] ?: 0.0) else 0.0

                    if (runnerUp.score > partnerTapScore) {
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
                val occupantScore = if (occupant.isNotEmpty()) (scores[occupant] ?: 0.0) else 0.0

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
                            val occupantScore = if (occupant.isNotEmpty()) (scores[occupant] ?: 0.0) else 0.0
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
