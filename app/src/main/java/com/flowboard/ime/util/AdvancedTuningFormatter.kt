package com.flowboard.ime.util

import com.flowboard.ime.data.models.EngineWeights
import com.flowboard.ime.data.models.MasterLayoutEntry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Helper to convert between human-friendly easy-to-edit text formats
 * and JSON data structures for Flowboard Master Layout and State Engine Weights.
 */
object AdvancedTuningFormatter {

    fun sanitizeSingleCharKey(rawKey: String): String {
        if (rawKey.isEmpty()) return ""
        val codePoints = rawKey.trim().codePoints().toArray()
        return if (codePoints.isNotEmpty()) {
            String(codePoints, 0, 1)
        } else {
            rawKey.trim().take(1)
        }
    }

    /**
     * Converts Master Layout map into clean, human-readable text (9 lines).
     *
     * Example:
     * Key 1: tap=j, up=l, left=_, right=#
     * Key 2: tap=z, up=a, left=", right=t
     * ...
     */
    fun layoutToEasyText(layout: Map<String, MasterLayoutEntry>): String {
        val sb = StringBuilder()
        for (k in 1..9) {
            val keyId = "key_$k"
            val slots = mutableMapOf<String, String>()
            for ((char, entry) in layout) {
                if (entry.homeKey.equals(keyId, ignoreCase = true)) {
                    slots[entry.defaultSlot.lowercase()] = char
                }
            }
            val tap = slots["tap"] ?: ""
            val up = slots["up"] ?: ""
            val left = slots["left"] ?: ""
            val right = slots["right"] ?: ""
            val down = slots["down"] ?: ""

            sb.append("Key $k: tap=$tap, up=$up, left=$left, right=$right")
            if (down.isNotEmpty()) {
                sb.append(", down=$down")
            }
            if (k < 9) sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * Parses Easy Text (or JSON fallback) into Map<String, MasterLayoutEntry>.
     */
    fun easyTextToLayout(raw: String, json: Json): Map<String, MasterLayoutEntry> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyMap()

        // Fallback: Check if it's raw JSON
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val parsed = json.decodeFromString<Map<String, MasterLayoutEntry>>(trimmed)
                val sanitized = mutableMapOf<String, MasterLayoutEntry>()
                for ((rawChar, entry) in parsed) {
                    val single = sanitizeSingleCharKey(rawChar)
                    if (single.isNotEmpty() && !sanitized.containsKey(single)) {
                        sanitized[single] = entry
                    }
                }
                return sanitized
            } catch (_: Exception) {}
        }

        val result = mutableMapOf<String, MasterLayoutEntry>()
        val keyLineRegex = Regex("""^(?:Key\s*)?([1-9])\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)
        val slotMarkerRegex = Regex("""\b(tap|up|left|right|down)\s*=\s*""", RegexOption.IGNORE_CASE)

        for (line in trimmed.lines()) {
            val lineTrimmed = line.trim()
            if (lineTrimmed.isEmpty() || lineTrimmed.startsWith("#") || lineTrimmed.startsWith("//")) continue

            val match = keyLineRegex.find(lineTrimmed)
            if (match != null) {
                val keyNum = match.groupValues[1].toInt()
                val homeKey = "key_$keyNum"
                val rest = match.groupValues[2].trim()

                val slotMatches = slotMarkerRegex.findAll(rest).toList()
                if (slotMatches.isNotEmpty()) {
                    for (i in slotMatches.indices) {
                        val slot = slotMatches[i].groupValues[1].lowercase()
                        val valueStart = slotMatches[i].range.last + 1
                        val valueEnd = if (i + 1 < slotMatches.size) slotMatches[i + 1].range.first else rest.length
                        var rawVal = rest.substring(valueStart, valueEnd).trim()

                        // Remove trailing delimiter comma if present (e.g. "d," -> "d", ",," -> ",")
                        if (rawVal.endsWith(",")) {
                            rawVal = rawVal.substring(0, rawVal.length - 1).trim()
                        }
                        if ((rawVal.startsWith("\"") && rawVal.endsWith("\"") && rawVal.length >= 2) ||
                            (rawVal.startsWith("'") && rawVal.endsWith("'") && rawVal.length >= 2)) {
                            rawVal = rawVal.substring(1, rawVal.length - 1)
                        }
                        val singleChar = sanitizeSingleCharKey(rawVal)
                        if (singleChar.isNotEmpty()) {
                            result[singleChar] = MasterLayoutEntry(homeKey = homeKey, defaultSlot = slot)
                        }
                    }
                } else {
                    // Positional: split by space or comma: tap up left right
                    val tokens = rest.split(Regex(""",\s*|\s+""")).filter { it.isNotEmpty() }
                    val slotNames = listOf("tap", "up", "left", "right", "down")
                    for (i in tokens.indices) {
                        if (i < slotNames.size) {
                            val singleChar = sanitizeSingleCharKey(tokens[i])
                            if (singleChar.isNotEmpty()) {
                                result[singleChar] = MasterLayoutEntry(homeKey = homeKey, defaultSlot = slotNames[i])
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * Converts Engine Weights map into clean, human-readable text (6 lines).
     *
     * Example:
     * State 1: U=25, B=23, T=41, D=33, WB=38, WT=77, STC=0
     * State 2: U=0, B=7, T=76, D=10, WB=93, WT=100, STC=67
     * ...
     */
    fun weightsToEasyText(weights: Map<Int, EngineWeights>): String {
        val sb = StringBuilder()
        val states = listOf(1, 2, 3, 4, 7, 8)
        for ((idx, state) in states.withIndex()) {
            val w = weights[state] ?: EngineWeights()
            sb.append("State $state: U=${w.U}, B=${w.B}, T=${w.T}, D=${w.D}, WB=${w.WB}, WT=${w.WT}, STC=${w.STC}")
            if (idx < states.size - 1) sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * Parses Easy Text (or JSON fallback) into Map<Int, EngineWeights>.
     */
    fun easyTextToWeights(raw: String, json: Json): Map<Int, EngineWeights> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyMap()

        // Fallback: Check if it's raw JSON
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val parsed = json.decodeFromString<Map<String, EngineWeights>>(trimmed)
                val intMap = parsed.mapNotNull { entry ->
                    val k = entry.key.toIntOrNull()
                    if (k != null) k to entry.value else null
                }.toMap()
                if (intMap.isNotEmpty()) return intMap
            } catch (_: Exception) {}
        }

        val result = mutableMapOf<Int, EngineWeights>()
        val stateLineRegex = Regex("""^(?:State\s*)?(\d+)\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)

        for (line in trimmed.lines()) {
            val lineTrimmed = line.trim()
            if (lineTrimmed.isEmpty() || lineTrimmed.startsWith("#") || lineTrimmed.startsWith("//")) continue

            val match = stateLineRegex.find(lineTrimmed)
            if (match != null) {
                val stateNum = match.groupValues[1].toInt()
                val rest = match.groupValues[2].trim()

                var uVal = Regex("""\bU\s*=\s*(\d+)""", RegexOption.IGNORE_CASE).find(rest)?.groupValues?.get(1)?.toIntOrNull()
                var bVal = Regex("""\bB\s*=\s*(\d+)""", RegexOption.IGNORE_CASE).find(rest)?.groupValues?.get(1)?.toIntOrNull()
                var tVal = Regex("""\bT\s*=\s*(\d+)""", RegexOption.IGNORE_CASE).find(rest)?.groupValues?.get(1)?.toIntOrNull()
                var dVal = Regex("""\bD\s*=\s*(\d+)""", RegexOption.IGNORE_CASE).find(rest)?.groupValues?.get(1)?.toIntOrNull()
                var wbVal = Regex("""\bWB\s*=\s*(\d+)""", RegexOption.IGNORE_CASE).find(rest)?.groupValues?.get(1)?.toIntOrNull()
                var wtVal = Regex("""\bWT\s*=\s*(\d+)""", RegexOption.IGNORE_CASE).find(rest)?.groupValues?.get(1)?.toIntOrNull()
                var stcVal = Regex("""\bSTC\s*=\s*(\d+)""", RegexOption.IGNORE_CASE).find(rest)?.groupValues?.get(1)?.toIntOrNull()

                // If not labeled with U=, B=, etc., support positional numbers: 25, 23, 41, 33, 35, 69, 0
                if (uVal == null && bVal == null && tVal == null) {
                    val numbers = Regex("""\d+""").findAll(rest).map { it.value.toInt() }.toList()
                    if (numbers.isNotEmpty()) uVal = numbers.getOrNull(0)
                    if (numbers.size > 1) bVal = numbers.getOrNull(1)
                    if (numbers.size > 2) tVal = numbers.getOrNull(2)
                    if (numbers.size > 3) dVal = numbers.getOrNull(3)
                    if (numbers.size > 4) wbVal = numbers.getOrNull(4)
                    if (numbers.size > 5) wtVal = numbers.getOrNull(5)
                    if (numbers.size > 6) stcVal = numbers.getOrNull(6)
                }

                val statusStr = when (stateNum) {
                    1 -> "State 1 (Start)"
                    7 -> "State 7 (Standard Spacebar)"
                    8 -> "State 8 (Connector Spacebar)"
                    else -> "State $stateNum"
                }

                result[stateNum] = EngineWeights(
                    U = uVal ?: 0,
                    B = bVal ?: 0,
                    T = tVal ?: 0,
                    D = dVal ?: 0,
                    WB = wbVal ?: 0,
                    WT = wtVal ?: 0,
                    STC = stcVal ?: 0,
                    status = statusStr
                )
            }
        }
        return result
    }
}
