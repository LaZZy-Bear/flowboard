package com.flowboard.ime.data.models

import kotlinx.serialization.Serializable

/**
 * Represents a single character's layout assignment in the master layout.
 * Each character maps to a specific homeKey (e.g., "key_5") and a default slot
 * (e.g., "tap", "up", "left", "right").
 */
@Serializable
data class MasterLayoutEntry(
    val homeKey: String,
    val defaultSlot: String
)
