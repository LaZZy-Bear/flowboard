package com.flowboard.ime.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A typing profile that contains behavior rules and character bonus scores.
 * Profiles control features like echo boosting (for chat-style typing),
 * vowel boosting, and illegal start character penalties.
 *
 * Two built-in profiles:
 * - "profile_default.json" — Formal Thai typing (no echo)
 * - "profile_chat.json"    — Chat/social typing (echo enabled)
 */
@Serializable
data class Profile(
    @SerialName("profile_name")
    val profileName: String = "",

    val language: String = "TH",

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

    /** Score boost when dragging (repeating char >=2 times) — effectively locks char to center */
    @SerialName("echo_drag_buff")
    val echoDragBuff: Double = 15.0,

    /** Characters that have a hard cap on echo boost (mainly top/bottom vowels) */
    @SerialName("echo_hardcap_chars")
    val echoHardcapChars: List<String> = emptyList(),

    /** Echo boost value for hard-capped characters */
    @SerialName("echo_hardcap_buff")
    val echoHardcapBuff: Double = 1.0,

    /** Vowel characters that receive extra boost in States 2-3 */
    @SerialName("vowel_booster_chars")
    val vowelBoosterChars: List<String> = emptyList(),

    /** Boost amount for vowel booster */
    @SerialName("vowel_booster_buff")
    val vowelBoosterBuff: Double = 10.0,

    /** Consonants that receive a constant boost to stay reachable */
    @SerialName("soft_anchor_chars")
    val softAnchorChars: List<String> = emptyList(),

    /** Boost amount for soft anchors */
    @SerialName("soft_anchor_buff")
    val softAnchorBuff: Double = 5.0,

    /** Characters that cannot start a word (vowels/tones) */
    @SerialName("illegal_start_chars")
    val illegalStartChars: List<String> = emptyList(),

    /** Penalty score for illegal start characters */
    @SerialName("illegal_start_penalty")
    val illegalStartPenalty: Double = -999.0
)
