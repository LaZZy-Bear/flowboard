package com.flowboard.ime.engine

import android.content.Context
import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Manages profile switching between typing modes (Default, Chat).
 * When a profile is switched, it updates the repository's active profile
 * and bonus dictionary.
 */
class ProfileManager(
    private val context: Context,
    private val repo: FlowboardRepository
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val PROFILE_DEFAULT = "th_TH/profile_default.json"
        const val PROFILE_CHAT = "th_TH/profile_chat.json"
    }

    /**
     * Switch to a different typing profile.
     *
     * @param profilePath Relative path in assets (e.g., "th_TH/profile_chat.json")
     */
    suspend fun switchProfile(profilePath: String) {
        withContext(Dispatchers.IO) {
            try {
                val profileJson = context.assets.open(profilePath).use { stream ->
                    stream.bufferedReader().readText()
                }
                val profile = json.decodeFromString<Profile>(profileJson)
                repo.activeProfile = profile
                repo.bonusDict = profile.bonusDict
            } catch (e: Exception) {
                // Fallback to default profile
                repo.activeProfile = Profile.DEFAULT
                repo.bonusDict = emptyMap()
            }
        }
    }
}
