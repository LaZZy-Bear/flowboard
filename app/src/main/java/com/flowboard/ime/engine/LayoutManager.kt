package com.flowboard.ime.engine

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.KeySlots

/**
 * Layout Manager — Prototype 22 (3-Way Domino Strategy)
 *
 * Ported from js/layout.js. Assigns characters to 9-key layout slots
 * using the 3-Way Domino partner swap algorithm.
 *
 * Algorithm:
 *   1. buildBaseLayout: Place each character at its home key, top-scoring char wins tap slot.
 *      LAZY_TAP_RATIO = 1.15: default tap char holds tap unless runner-up is 15% better.
 *   2. partnerSwapEN (Domino 3-way):
 *      - If runner-up on Key A beats partner Key B's current tap by >15%:
 *        a. Runner-up moves to Key B's tap
 *        b. Key B's evicted tap moves to Key B's weakest swipe slot
 *        c. Key B's weakest slot's evicted char moves back to Key A's vacated slot
 *   3. fillUnrenderedChars: Sweep any unassigned chars into empty slots (home → partner → any key)
 *
 * Thai-specific methods removed:
 *   - assignLayoutTH() (7-step Thai layout)
 *   - assignMissingLayout() (Thai alt/missing character layer)
 */
class LayoutManager(private val repo: FlowboardRepository) {

    companion object {
        /** Partner Key Pairing — ported from P22 PARTNER_KEY constant. */
        val PARTNER_KEY = mapOf(
            "key_1" to "key_2", "key_2" to "key_1",  // top-left ↔ top-center
            "key_3" to "key_6", "key_6" to "key_3",  // top-right ↔ mid-right
            "key_4" to "key_7", "key_7" to "key_4",  // mid-left ↔ bot-left
            "key_8" to "key_9", "key_9" to "key_8",  // bot-center ↔ bot-right
            "key_5" to null                            // center — no partner
        )

        /** Lazy tap ratio: runner-up must beat current tap by >15% to take it. */
        private const val LAZY_TAP_RATIO = 1.15
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
            val placed = mutableSetOf<String>()

            // Find default-tap candidate and top scorer
            val defTapObj = candidates.find { it.defaultSlot == "tap" }
            var topWinner = candidates[0]

            var localCandidates = candidates
            // LAZY TAP: protect default tap if runner-up doesn't beat it by LAZY_TAP_RATIO
            if (defTapObj != null && defTapObj != candidates[0] && defTapObj.score > 0) {
                if (candidates[0].score <= defTapObj.score * LAZY_TAP_RATIO) {
                    topWinner = defTapObj
                    localCandidates = candidates.filter { it != defTapObj }.toMutableList()
                    localCandidates.add(0, defTapObj)
                    charactersByHomeKey[keyId] = localCandidates
                }
            }

            // Place tap winner
            if (topWinner.score > 0 && (keyMap["tap"] ?: "").isEmpty()) {
                keyMap["tap"] = topWinner.char
                placed.add(topWinner.char)

                // If winner displaced the default tap char, place it in winner's old default slot
                if (topWinner.defaultSlot != "tap" && topWinner.defaultSlot != "down") {
                    if (defTapObj != null && (keyMap[topWinner.defaultSlot] ?: "").isEmpty()) {
                        keyMap[topWinner.defaultSlot] = defTapObj.char
                        placed.add(defTapObj.char)
                    }
                }
            }

            // Fill remaining candidates in their default slots
            for (c in localCandidates) {
                if (!placed.contains(c.char) && c.defaultSlot != "down" &&
                    (keyMap[c.defaultSlot] ?: "").isEmpty()
                ) {
                    keyMap[c.defaultSlot] = c.char
                    placed.add(c.char)
                }
            }

            // Fill any remaining chars into empty slots
            val fillSlots = listOf("tap", "up", "left", "right")
            for (c in localCandidates) {
                if (!placed.contains(c.char) && c.defaultSlot != "down") {
                    for (slot in fillSlots) {
                        if ((keyMap[slot] ?: "").isEmpty()) {
                            keyMap[slot] = c.char
                            placed.add(c.char)
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

            if (runnerUp.score > partnerTapScore * LAZY_TAP_RATIO) {
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
     * Ported from P22 fillUnrenderedChars() in layout.js.
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
