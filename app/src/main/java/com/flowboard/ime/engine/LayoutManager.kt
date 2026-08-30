package com.flowboard.ime.engine

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.KeySlots

/**
 * Layout Manager — 3-Way Domino Partner Strategy
 *
 * Assigns characters to 9-key layout slots using dynamic scoring and
 * the 3-Way Domino partner swap algorithm.
 *
 * Algorithm:
 *   1. buildBaseLayout: Place each character at its home key, top-scoring char wins tap slot.
 *      LAZY_TAP_RATIO: default tap char holds tap unless runner-up is sufficiently better.
 *   2. partnerSwapEN (Domino 3-way):
 *      - If runner-up on Key A beats partner Key B's current tap by > partnerTapRatio:
 *        a. Runner-up moves to Key B's tap
 *        b. Key B's evicted tap moves to Key B's weakest swipe slot
 *        c. Key B's weakest slot's evicted char moves back to Key A's vacated slot
 *   3. fillUnrenderedChars: Sweep any unassigned chars into empty slots (home → partner → any key)
 */
class LayoutManager(private val repo: FlowboardRepository) {

    companion object {
        /** Partner Key Pairing for 3-way domino swaps. */
        val PARTNER_KEY = mapOf(
            "key_1" to "key_2", "key_2" to "key_1",  // top-left ↔ top-center
            "key_3" to "key_6", "key_6" to "key_3",  // top-right ↔ mid-right
            "key_4" to "key_7", "key_7" to "key_4",  // mid-left ↔ bot-left
            "key_8" to "key_9", "key_9" to "key_8",  // bot-center ↔ bot-right
            "key_5" to null                            // center — no partner
        )

        /** Default lazy tap ratio: runner-up must beat current default tap by >15% (1.15x) to take it on the same key. */
        const val DEFAULT_LAZY_TAP_RATIO = 1.15

        /** Default partner tap ratio: runner-up on Key A must beat partner Key B's tap by >35% (1.35x) to swap. */
        const val DEFAULT_PARTNER_TAP_RATIO = 1.35
    }

    data class Candidate(val char: String, val defaultSlot: String, val score: Double)

    fun assignLayout(scores: Map<String, Double>): Map<String, KeySlots> {
        val (baseLayout, charsByHome) = buildBaseLayout(scores)
        partnerSwapEN(baseLayout, charsByHome, scores)
        return fillUnrenderedChars(baseLayout, scores)
    }

    // ═══════════════════════════════════════
    // Phase 1: Build Base Layout
    // ═══════════════════════════════════════

    private fun buildBaseLayout(
        scores: Map<String, Double>
    ): Pair<MutableMap<String, MutableMap<String, String>>, Map<String, List<Candidate>>> {

        val newLayout = mutableMapOf<String, MutableMap<String, String>>()
        for (i in 1..9) {
            newLayout["key_$i"] = mutableMapOf("tap" to "", "up" to "", "left" to "", "right" to "", "down" to i.toString())
        }

        // Apply sticky key: if the last-typed char should be held on tap, lock it to tap slot
        val stickyChar = repo.stickyChar
        val stickyKeyId = repo.lastActionKeyId
        if (stickyChar != null && stickyKeyId != null) {
            newLayout[stickyKeyId]?.set("tap", stickyChar)
        }

        // Build per-key candidate lists from masterLayout
        val charactersByHomeKey = mutableMapOf<String, MutableList<Candidate>>()
        for (i in 1..9) charactersByHomeKey["key_$i"] = mutableListOf()

        for ((char, info) in repo.masterLayout) {
            if (stickyChar != null && char == stickyChar) continue
            val home = info.homeKey
            val score = scores[char] ?: 0.0
            charactersByHomeKey[home]?.add(Candidate(char, info.defaultSlot, score))
        }

        for (k in charactersByHomeKey.keys) {
            charactersByHomeKey[k]?.sortByDescending { it.score }
        }

        // Assign slots per key
        for (i in 1..9) {
            val keyId = "key_$i"
            val candidates = charactersByHomeKey[keyId] ?: continue
            if (candidates.isEmpty()) continue

            val keyMap = newLayout[keyId] ?: continue
            val placedCandidates = mutableSetOf<Candidate>()

            // Find default-tap candidate and top scorer
            val defTapObj = candidates.find { it.defaultSlot == "tap" }
            var topWinner = candidates[0]

            var localCandidates = candidates
            // LAZY TAP: protect default tap if runner-up doesn't beat it by LAZY_TAP_RATIO
            if (defTapObj != null && defTapObj != candidates[0]) {
                if (repo.lazyTapRatio >= 5.0) {
                    // Lock mode (high ratio / 10x): 100% protect default tap slot
                    topWinner = defTapObj
                    localCandidates = candidates.filter { it != defTapObj }.toMutableList()
                    localCandidates.add(0, defTapObj)
                    charactersByHomeKey[keyId] = localCandidates
                } else if (defTapObj.score > 0 && candidates[0].score <= defTapObj.score * repo.lazyTapRatio) {
                    topWinner = defTapObj
                    localCandidates = candidates.filter { it != defTapObj }.toMutableList()
                    localCandidates.add(0, defTapObj)
                    charactersByHomeKey[keyId] = localCandidates
                }
            }

            // Place tap winner
            if ((keyMap["tap"] ?: "").isEmpty()) {
                keyMap["tap"] = topWinner.char
                placedCandidates.add(topWinner)

                // If winner displaced the default tap char, place it in winner's old default slot
                if (topWinner.defaultSlot != "tap" && topWinner.defaultSlot != "down") {
                    if (defTapObj != null && (keyMap[topWinner.defaultSlot] ?: "").isEmpty()) {
                        keyMap[topWinner.defaultSlot] = defTapObj.char
                        placedCandidates.add(defTapObj)
                    }
                }
            }

            // Fill remaining candidates in their default slots
            for (c in localCandidates) {
                if (!placedCandidates.contains(c) && c.defaultSlot != "down" &&
                    (keyMap[c.defaultSlot] ?: "").isEmpty()
                ) {
                    keyMap[c.defaultSlot] = c.char
                    placedCandidates.add(c)
                }
            }

            // Fill any remaining chars into empty slots
            val fillSlots = listOf("tap", "up", "left", "right")
            for (c in localCandidates) {
                if (!placedCandidates.contains(c) && c.defaultSlot != "down") {
                    for (slot in fillSlots) {
                        if ((keyMap[slot] ?: "").isEmpty()) {
                            keyMap[slot] = c.char
                            placedCandidates.add(c)
                            break
                        }
                    }
                }
            }
        }

        return Pair(newLayout, charactersByHomeKey)
    }

    // ═══════════════════════════════════════
    // Phase 2: 3-Way Domino Partner Swap
    // ═══════════════════════════════════════

    private fun partnerSwapEN(
        newLayout: MutableMap<String, MutableMap<String, String>>,
        charactersByHomeKey: Map<String, List<Candidate>>,
        scores: Map<String, Double>
    ) {
        val stickyChar = repo.stickyChar

        for (i in 1..9) {
            val keyId = "key_$i"
            val partnerKeyId = PARTNER_KEY[keyId] ?: continue

            val candidates = charactersByHomeKey[keyId] ?: continue
            if (candidates.size < 2) continue

            val runnerUp = candidates[1]
            if (runnerUp.score <= 0) continue

            val partnerTapChar = newLayout[partnerKeyId]?.get("tap") ?: ""
            var partnerTapScore = if (partnerTapChar.isNotEmpty()) (scores[partnerTapChar] ?: 0.0) else 0.0
            if (stickyChar != null && partnerTapChar == stickyChar) {
                partnerTapScore = Double.MAX_VALUE  // Protect sticky char
            }

            if (repo.partnerTapRatio >= 5.0) {
                // Lock mode (high ratio / 10x): completely disable partner swap
                continue
            }

            val effectiveThreshold = if (partnerTapScore > 0.0) {
                partnerTapScore * repo.partnerTapRatio
            } else {
                5.0 * repo.partnerTapRatio
            }

            if (runnerUp.score > effectiveThreshold) {
                // Domino Step 1: Clear runner-up from Key A
                val keySlotsMap = newLayout[keyId] ?: continue
                var runnerUpOldSlot: String? = null
                for ((slot, char) in keySlotsMap) {
                    if (char == runnerUp.char) {
                        runnerUpOldSlot = slot
                        keySlotsMap[slot] = ""
                        break
                    }
                }

                // Domino Step 2: Runner-up takes partner Key B's tap
                newLayout[partnerKeyId]?.set("tap", runnerUp.char)

                if (partnerTapChar.isNotEmpty()) {
                    // Domino Step 3: Evicted tap → weakest swipe slot on Key B
                    val swipeSlots = listOf("up", "left", "right")
                    val partnerMap = newLayout[partnerKeyId] ?: continue
                    var weakestSlot: String? = null
                    var weakestChar: String? = null
                    var minScore = Double.MAX_VALUE

                    for (slot in swipeSlots) {
                        val occupant = partnerMap[slot] ?: ""
                        if (stickyChar != null && occupant == stickyChar) continue
                        if (occupant.isEmpty()) {
                            weakestSlot = slot
                            weakestChar = ""
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
                        partnerMap[weakestSlot] = partnerTapChar

                        // Domino Step 4: Evicted weakest slot char → Key A's vacated slot
                        if (!weakestChar.isNullOrEmpty() && runnerUpOldSlot != null) {
                            keySlotsMap[runnerUpOldSlot] = weakestChar
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════
    // Phase 3: Fill Unrendered Characters
    // ═══════════════════════════════════════

    /**
     * Sweeps all characters that aren't placed yet and fills them into empty slots.
     * Guarantees all 36 characters appear somewhere (36 chars, 36 slots = exact fit).
     */
    private fun fillUnrenderedChars(
        newLayout: MutableMap<String, MutableMap<String, String>>,
        scores: Map<String, Double>
    ): Map<String, KeySlots> {
        // Collect currently rendered characters
        val currentlyRendered = mutableSetOf<String>()
        for (k in newLayout.keys) {
            val keyMap = newLayout[k] ?: continue
            for ((slot, char) in keyMap) {
                if (slot != "down" && char.isNotEmpty()) currentlyRendered.add(char)
            }
        }

        // Find all unrendered chars from masterLayout
        val unrendered = mutableListOf<String>()
        for (char in repo.masterLayout.keys) {
            if (!currentlyRendered.contains(char)) unrendered.add(char)
        }
        unrendered.sortByDescending { scores[it] ?: 0.0 }

        // Try to place each unrendered char
        for (char in unrendered) {
            val homeKey = repo.masterLayout[char]?.homeKey ?: continue
            val partnerKey = PARTNER_KEY[homeKey]

            fun fillEmpty(targetKey: String?): Boolean {
                if (targetKey == null) return false
                val fillSlots = listOf("up", "left", "right", "tap")
                val keyMap = newLayout[targetKey] ?: return false
                for (slot in fillSlots) {
                    if ((keyMap[slot] ?: "").isEmpty()) {
                        keyMap[slot] = char
                        return true
                    }
                }
                return false
            }

            // Priority: home → partner → any key
            val placed = fillEmpty(homeKey) ||
                    (partnerKey != null && fillEmpty(partnerKey)) ||
                    (1..9).any { fillEmpty("key_$it") }

            if (!placed) {
                // Guaranteed fallback: should not happen with 36 chars in 36 slots
                android.util.Log.w("LayoutManager", "Could not place char '$char' in any slot!")
            }
        }

        // Convert to KeySlots
        return (1..9).associate { i ->
            val key = "key_$i"
            val map = newLayout[key]!!
            key to KeySlots(
                tap = map["tap"] ?: "",
                up = map["up"] ?: "",
                left = map["left"] ?: "",
                right = map["right"] ?: "",
                down = map["down"] ?: ""
            )
        }
    }
}
