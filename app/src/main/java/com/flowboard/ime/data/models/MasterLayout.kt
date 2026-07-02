package com.flowboard.ime.data.models

import kotlinx.serialization.Serializable

/**
 * Represents a single key's character assignment in the master layout.
 * Each key has a primary character ([main]) and a list of alternate characters ([alts])
 * that can be dynamically placed on tap/swipe slots by the LayoutManager.
 */
@Serializable
data class MasterKey(
    val main: String,
    val alts: List<String> = emptyList()
) {
    /**
     * Returns all characters assigned to this key (main + alts) in order.
     */
    fun allMembers(): List<String> = buildList {
        add(main)
        addAll(alts)
    }
}
