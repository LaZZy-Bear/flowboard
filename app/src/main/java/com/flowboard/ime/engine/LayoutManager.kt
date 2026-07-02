package com.flowboard.ime.engine

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.KeySlots

/**
 * Manages the dynamic character placement on the 9-key grid.
 *
 * Key groups:
 * - group_top: key_1, key_2, key_3
 * - group_mid: key_4, key_5, key_6
 * - group_bot: key_7, key_8, key_9
 *
 * Placement priority per character (descending score order):
 * 1. Home key's tap slot
 * 2. Any group key's tap slot
 * 3. Home key's swipe slots (up → left → right)
 * 4. Any group key's swipe slots (up → left → right)
 */
class LayoutManager(private val repo: FlowboardRepository) {

    companion object {
        val KEY_GROUPS = mapOf(
            "group_top" to listOf("key_1", "key_2", "key_3"),
            "group_mid" to listOf("key_4", "key_5", "key_6"),
            "group_bot" to listOf("key_7", "key_8", "key_9")
        )
        private val SWIPE_SLOTS = listOf("up", "left", "right")
    }

    /**
     * Assign characters to the 9-key layout based on AI scores.
     * Numbers 1-9 are always placed in the 'down' slot.
     *
     * @param scores Map of character → prediction score
     * @return Map of key_id → KeySlots
     */
    fun assignLayout(scores: Map<String, Double>): Map<String, KeySlots> {
        val layout = HashMap<String, KeySlots>(9)
        for (i in 1..9) {
            layout["key_$i"] = KeySlots(down = i.toString())
        }

        val bonusDict = repo.bonusDict
        val masterLayout = repo.masterLayout

        for ((_, groupKeys) in KEY_GROUPS) {
            // Collect all candidate characters in this group
            data class CharCandidate(
                val char: String,
                val homeKey: String,
                val score: Double
            )

            val candidates = mutableListOf<CharCandidate>()

            for (keyId in groupKeys) {
                val masterKey = masterLayout[keyId] ?: continue
                val members = masterKey.allMembers()
                members.forEachIndexed { index, c ->
                    val aiScore = scores[c] ?: 0.0
                    val userBonus = bonusDict[c] ?: 0.0
                    val defaultBonus = 0.1 - (index * 0.001)
                    candidates.add(CharCandidate(c, keyId, aiScore + userBonus + defaultBonus))
                }
            }

            // Sort by score descending
            candidates.sortByDescending { it.score }

            // Place characters according to priority rules
            for (data in candidates) {
                var placed = false
                val c = data.char
                val home = data.homeKey

                // Rule 1: Home key's tap slot
                if (layout[home]!!.tap.isEmpty()) {
                    layout[home]!!.tap = c
                    placed = true
                }

                // Rule 2: Any group key's tap slot
                if (!placed) {
                    for (keyId in groupKeys) {
                        if (layout[keyId]!!.tap.isEmpty()) {
                            layout[keyId]!!.tap = c
                            placed = true
                            break
                        }
                    }
                }

                // Rule 3: Home key's swipe slots
                if (!placed) {
                    for (slot in SWIPE_SLOTS) {
                        if (getSlot(layout[home]!!, slot).isEmpty()) {
                            setSlot(layout[home]!!, slot, c)
                            placed = true
                            break
                        }
                    }
                }

                // Rule 4: Any group key's swipe slots
                if (!placed) {
                    outer@ for (keyId in groupKeys) {
                        for (slot in SWIPE_SLOTS) {
                            if (getSlot(layout[keyId]!!, slot).isEmpty()) {
                                setSlot(layout[keyId]!!, slot, c)
                                placed = true
                                break@outer
                            }
                        }
                    }
                }
            }
        }

        return layout
    }

    /**
     * Create an alternate layout showing characters that are missing
     * from the normal layout. Used when the user toggles Alt Mode (Aa button).
     *
     * Characters are distributed across keys in this slot order:
     * tap → up → left → right → down
     */
    fun assignMissingLayout(normalLayout: Map<String, KeySlots>): Map<String, KeySlots> {
        // 1. Collect all visible characters in normal layout
        val visibleChars = HashSet<String>()
        for (i in 1..9) {
            val k = normalLayout["key_$i"] ?: continue
            visibleChars.addAll(k.visibleChars())
        }

        // 2. Find all Thai characters that are missing
        val allThaiChars = repo.charMap.keys.filter { it != " " }
        val missingChars = allThaiChars.filter { it !in visibleChars }

        // 3. Create empty layout
        val altLayout = HashMap<String, KeySlots>(9)
        for (i in 1..9) {
            altLayout["key_$i"] = KeySlots()
        }

        // 4. Distribute missing characters: tap → up → left → right → down
        val slotsOrder = listOf("tap", "up", "left", "right", "down")
        var missingIndex = 0

        for (slot in slotsOrder) {
            for (i in 1..9) {
                if (missingIndex >= missingChars.size) return altLayout
                setSlot(altLayout["key_$i"]!!, slot, missingChars[missingIndex])
                missingIndex++
            }
        }

        return altLayout
    }

    // ── Slot Accessors ──

    private fun getSlot(key: KeySlots, slot: String): String = when (slot) {
        "tap" -> key.tap
        "up" -> key.up
        "left" -> key.left
        "right" -> key.right
        "down" -> key.down
        else -> ""
    }

    private fun setSlot(key: KeySlots, slot: String, value: String) {
        when (slot) {
            "tap" -> key.tap = value
            "up" -> key.up = value
            "left" -> key.left = value
            "right" -> key.right = value
            "down" -> key.down = value
        }
    }
}
