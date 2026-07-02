package com.flowboard.ime.data.models

/**
 * Represents the 5 character slots on a single key:
 * - [tap]:   Center character (highest priority)
 * - [up]:    Swipe up character
 * - [left]:  Swipe left character
 * - [right]: Swipe right character
 * - [down]:  Swipe down character (numbers 1-9 in normal mode)
 */
data class KeySlots(
    var tap: String = "",
    var up: String = "",
    var left: String = "",
    var right: String = "",
    var down: String = ""
) {
    /**
     * Returns a set of all visible characters (non-empty slots, excluding down in normal mode).
     */
    fun visibleChars(): Set<String> {
        return buildSet {
            if (tap.isNotEmpty()) add(tap)
            if (up.isNotEmpty()) add(up)
            if (left.isNotEmpty()) add(left)
            if (right.isNotEmpty()) add(right)
        }
    }

    /**
     * Returns all characters including the down slot.
     */
    fun allChars(): Set<String> {
        return buildSet {
            if (tap.isNotEmpty()) add(tap)
            if (up.isNotEmpty()) add(up)
            if (left.isNotEmpty()) add(left)
            if (right.isNotEmpty()) add(right)
            if (down.isNotEmpty()) add(down)
        }
    }
}
