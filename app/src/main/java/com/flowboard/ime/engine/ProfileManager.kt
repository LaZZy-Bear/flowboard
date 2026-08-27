package com.flowboard.ime.engine

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.Profile

/**
 * Manages profile switching between typing modes (Default/Chat).
 *
 * English-only. Profile data is loaded by AssetLoader (profile_chat.json).
 * The "Default" profile uses empty rules (no echo). Chat mode enables echo.
 */
class ProfileManager(private val repo: FlowboardRepository) {

    enum class ProfileMode { DEFAULT, CHAT }

    var currentMode: ProfileMode = ProfileMode.DEFAULT
        private set

    // Store profiles in memory after initial load
    private var defaultProfile: Profile = Profile.DEFAULT
    private var chatProfile: Profile = Profile.DEFAULT

    /**
     * Called by FlowboardApplication after Phase B loading completes,
     * to cache both profiles from the repository's active profile.
     */
    @Suppress("unused")
    fun setLoadedProfiles(chatProfileLoaded: Profile) {
        defaultProfile = Profile.DEFAULT
        chatProfile = chatProfileLoaded
        applyProfile(defaultProfile)
    }

    /**
     * Switch to a different typing profile mode.
     */
    fun switchProfile(mode: ProfileMode) {
        currentMode = mode
        applyProfile(if (mode == ProfileMode.CHAT) chatProfile else defaultProfile)
    }

    /**
     * Re-apply the current profile mode (e.g., after data reload).
     */
    @Suppress("unused")
    fun refreshProfile() {
        applyProfile(if (currentMode == ProfileMode.CHAT) chatProfile else defaultProfile)
    }

    private fun applyProfile(profile: Profile) {
        repo.activeProfile = profile
        repo.bonusDict = profile.bonusDict
    }
}
