package com.flowboard.ime.ui

import android.view.MotionEvent

/**
 * Detects 5-direction swipe gestures on a keyboard key:
 * TAP, UP, DOWN, LEFT, RIGHT.
 *
 * A swipe is detected when the pointer moves more than [thresholdPx] pixels
 * from the touch-down point. The dominant axis (horizontal vs vertical) and
 * direction determine the action.
 *
 * @param thresholdPx Minimum distance in pixels to register a swipe (default 25dp equivalent)
 * @param onAction Callback invoked with the detected [SwipeAction]
 */
class SwipeDetector(
    private val thresholdPx: Float = 50f,
    private val onAction: (SwipeAction) -> Unit
) {

    /**
     * The 5 possible swipe actions on a key.
     */
    enum class SwipeAction {
        /** Simple tap (no significant movement) */
        TAP,
        /** Swipe upward */
        UP,
        /** Swipe downward */
        DOWN,
        /** Swipe left */
        LEFT,
        /** Swipe right */
        RIGHT
    }

    private var startX = 0f
    private var startY = 0f
    private var isDown = false

    /**
     * Process a touch event on the key.
     * Call this from the key view's onTouchEvent or touch listener.
     *
     * @return true if the event was consumed
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                isDown = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDown) return false
                isDown = false

                val dx = event.x - startX
                val dy = event.y - startY
                val absDx = Math.abs(dx)
                val absDy = Math.abs(dy)

                val action = if (Math.max(absDx, absDy) > thresholdPx) {
                    when {
                        absDy > absDx && dy < 0 -> SwipeAction.UP
                        absDy > absDx && dy > 0 -> SwipeAction.DOWN
                        absDx > absDy && dx < 0 -> SwipeAction.LEFT
                        absDx > absDy && dx > 0 -> SwipeAction.RIGHT
                        else -> SwipeAction.TAP
                    }
                } else {
                    SwipeAction.TAP
                }

                onAction(action)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDown = false
                return true
            }
        }
        return false
    }

    /**
     * Reset the detector state.
     */
    fun reset() {
        isDown = false
    }
}
