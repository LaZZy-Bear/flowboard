package com.flowboard.ime.engine

import com.flowboard.ime.data.FlowboardRepository

/**
 * Manages language switching, shift/caps lock state, and case application.
 */
class LanguageManager(private val repo: FlowboardRepository) {

    enum class ShiftState { OFF, SHIFT_ONCE, CAPS_LOCK }
    
    var shiftState: ShiftState = ShiftState.OFF
        private set

    /**
     * Toggle between TH and EN. Returns the new active language.
     */
    fun toggleLanguage(): String {
        val newLang = if (repo.activeLang == "TH") "EN" else "TH"
        repo.setLanguage(newLang)
        shiftState = ShiftState.OFF  // reset shift on lang change
        return newLang
    }

    private var lastShiftTapTime: Long = 0L

    /**
     * Cycle shift state (EN only):
     * - OFF -> SHIFT_ONCE
     * - SHIFT_ONCE -> CAPS_LOCK (if pressed within 0.8s) or OFF (if pressed after 0.8s)
     * - CAPS_LOCK -> OFF
     */
    fun cycleShift(): ShiftState {
        val now = System.currentTimeMillis()
        val delta = now - lastShiftTapTime
        lastShiftTapTime = now

        shiftState = when (shiftState) {
            ShiftState.OFF -> ShiftState.SHIFT_ONCE
            ShiftState.SHIFT_ONCE -> {
                if (delta < 800) {
                    ShiftState.CAPS_LOCK
                } else {
                    ShiftState.OFF
                }
            }
            ShiftState.CAPS_LOCK -> ShiftState.OFF
        }
        return shiftState
    }

    /**
     * Get case to display character without consuming state.
     */
    fun getDisplayCase(char: String): String {
        if (repo.activeLang != "EN") return char
        return when (shiftState) {
            ShiftState.OFF -> char.lowercase()
            ShiftState.SHIFT_ONCE, ShiftState.CAPS_LOCK -> char.uppercase()
        }
    }

    /**
     * Apply case to character based on shift state (EN only).
     * Automatically resets SHIFT_ONCE back to OFF.
     */
    fun applyCase(char: String): String {
        if (repo.activeLang != "EN") return char
        return when (shiftState) {
            ShiftState.OFF -> char.lowercase()
            ShiftState.SHIFT_ONCE -> {
                shiftState = ShiftState.OFF  // auto-reset after one char
                lastShiftTapTime = 0L       // reset tap time to prevent accidental double-tap logic on next tap
                char.uppercase()
            }
            ShiftState.CAPS_LOCK -> char.uppercase()
        }
    }

    /**
     * Is alt/missing mode available? (Thai only)
     */
    fun isAltModeAvailable(): Boolean = repo.activeLang == "TH"
}
