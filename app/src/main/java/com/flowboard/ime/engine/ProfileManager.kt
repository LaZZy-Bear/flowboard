package com.flowboard.ime.engine

import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.Profile

/**
 * Manages profile switching between typing modes (Default, Chat).
 * Profiles are already loaded in memory per-language during AssetLoader phase A.
 */
class ProfileManager(private val repo: FlowboardRepository) {

    enum class ProfileMode { DEFAULT, CHAT }
    
    var currentMode: ProfileMode = ProfileMode.DEFAULT
        private set

    /**
     * Switch to a different typing profile mode.
     * Uses the appropriate profile for the currently active language.
     * English does not have a default profile, so it falls back to empty rules.
     */
    fun switchProfile(mode: ProfileMode) {
        currentMode = mode
        
        val langData = repo.languageRegistry[repo.activeLang]
        if (langData == null) {
            applyProfile(Profile.DEFAULT)
            return
        }
        
        val targetProfile = when (mode) {
            ProfileMode.CHAT -> langData.chatProfile
            ProfileMode.DEFAULT -> langData.defaultProfile
        }
        
        applyProfile(targetProfile ?: Profile.DEFAULT)
    }
    
    /**
     * Re-applies the current profile mode (useful after a language switch).
     */
    fun refreshProfile() {
        switchProfile(currentMode)
    }

    private fun applyProfile(profile: Profile) {
        repo.activeProfile = profile
        repo.bonusDict = profile.bonusDict
    }
}
