package com.flowboard.ime.util

import com.flowboard.ime.data.models.CharTag

/**
 * Utility functions for Thai character processing.
 */
object ThaiCharUtil {

    /**
     * Get the tag string for a character (e.g., "C", "Vp", "T").
     * Uses the loaded char map for lookup.
     */
    fun getTag(char: Char, charMap: Map<String, String>): String {
        return charMap[char.toString()] ?: "O"
    }

    /**
     * Get the CharTag enum for a character.
     */
    fun getCharTag(char: Char, charMap: Map<String, String>): CharTag {
        return CharTag.fromChar(char, charMap)
    }

    /**
     * Build a context tag string from the last two characters.
     * Example: "สร" → "C-C", "กา" → "C-Vf"
     */
    fun getContextTag(last2: String, charMap: Map<String, String>): String? {
        if (last2.length != 2) return null
        val tag1 = getTag(last2[0], charMap)
        val tag2 = getTag(last2[1], charMap)
        return "$tag1-$tag2"
    }
}
