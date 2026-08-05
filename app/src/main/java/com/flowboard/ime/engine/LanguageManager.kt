package com.flowboard.ime.engine

import com.flowboard.ime.data.FlowboardRepository

/**
 * Manages shift/caps lock state and character case application.
 *
 * English-only. Language switching removed (no more toggleLanguage).
 * This class now acts purely as a shift/case state manager.
 */
class LanguageManager(private val repo: FlowboardRepository) {

    enum class ShiftState { OFF, SHIFT_ONCE, CAPS_LOCK }

    var shiftState: ShiftState = ShiftState.OFF
        private set

    private var lastShiftTapTime: Long = 0L

    /**
     * Cycle shift state:
     * - OFF → SHIFT_ONCE
     * - SHIFT_ONCE → CAPS_LOCK (if pressed within 0.8s) or OFF (if pressed after 0.8s)
     * - CAPS_LOCK → OFF
     */
    fun cycleShift(): ShiftState {
        val now = System.currentTimeMillis()
        val delta = now - lastShiftTapTime
        lastShiftTapTime = now

        shiftState = when (shiftState) {
            ShiftState.OFF -> ShiftState.SHIFT_ONCE
            ShiftState.SHIFT_ONCE -> {
                if (delta < 800) ShiftState.CAPS_LOCK else ShiftState.OFF
            }
            ShiftState.CAPS_LOCK -> ShiftState.OFF
        }
        return shiftState
    }

    /**
     * Get display case for a character without consuming the shift state.
     */
    fun getDisplayCase(char: String): String {
        return when (shiftState) {
            ShiftState.OFF -> char.lowercase()
            ShiftState.SHIFT_ONCE, ShiftState.CAPS_LOCK -> char.uppercase()
        }
    }

    /**
     * Apply case to a character based on current shift state.
     * SHIFT_ONCE automatically resets to OFF after one character.
     */
    fun applyCase(char: String): String {
        return when (shiftState) {
            ShiftState.OFF -> char.lowercase()
            ShiftState.SHIFT_ONCE -> {
                shiftState = ShiftState.OFF
                lastShiftTapTime = 0L  // Reset tap time to prevent accidental CAPS_LOCK
                char.uppercase()
            }
            ShiftState.CAPS_LOCK -> char.uppercase()
        }
    }
}
