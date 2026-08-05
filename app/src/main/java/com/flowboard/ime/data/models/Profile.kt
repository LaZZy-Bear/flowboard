package com.flowboard.ime.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A typing profile that contains behavior rules and character bonus scores.
 * Profiles control features like echo boosting (for chat-style typing).
 *
 * Built-in profiles:
 * - Default — Standard English typing (no echo)
 * - "profile_chat.json" — Chat/social typing (echo enabled)
 */
@Serializable
data class Profile(
    @SerialName("profile_name")
    val profileName: String = "",

    val language: String = "EN",

    val rules: ProfileRules = ProfileRules(),

    @SerialName("bonus_dict")
    val bonusDict: Map<String, Double> = emptyMap()
) {
    companion object {
        val DEFAULT = Profile(profileName = "Default")
    }
}

/**
 * Rules that modify the scoring engine's post-processing behavior.
 * Thai-specific rules (vowelBooster, softAnchor, illegalStart) have been removed.
 */
@Serializable
data class ProfileRules(
    /** Whether echo boosting is enabled (repeating the same character) */
    @SerialName("allow_echo")
    val allowEcho: Boolean = false,

    /** Penalty reduction ratio when echo char hits pattern penalty */
    @SerialName("echo_immunity_ratio")
    val echoImmunityRatio: Double = 0.9,

    /** Base score boost for repeated characters */
    @SerialName("echo_base_buff")
    val echoBaseBuff: Double = 3.0,

    /** Score boost when dragging (repeating char >=2 times or across space) — effectively locks char to center */
    @SerialName("echo_drag_buff")
    val echoDragBuff: Double = 15.0,

    /** Characters that have a hard cap on echo boost */
    @SerialName("echo_hardcap_chars")
    val echoHardcapChars: List<String> = emptyList(),

    /** Echo boost value for hard-capped characters */
    @SerialName("echo_hardcap_buff")
    val echoHardcapBuff: Double = 1.0
)
