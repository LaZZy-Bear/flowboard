package com.flowboard.ime.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import com.flowboard.ime.data.models.KeySlots

/**
 * Custom ViewGroup that renders the 3×3 grid of 9 keyboard keys.
 *
 * Each row is assigned a zone type for gradient coloring:
 * - Row 0 (keys 1-3): TOP zone (blue tint) — tones and vowels
 * - Row 1 (keys 4-6): MID zone (green tint) — common consonants
 * - Row 2 (keys 7-9): BOT zone (orange tint) — vowels and others
 *
 * Keys are rectangular (wider than tall) matching the prototype's
 * aspect ratio. Height is fixed at 75dp per key.
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    /** The 9 key views arranged in a 3×3 grid */
    private val keyViews = Array(9) { index ->
        KeyView(context).apply {
            keyIndex = index + 1 // 1..9 matching key_1..key_9
        }
    }

    /** Callback for when a key action occurs (swipe/tap + key data + keyIndex) */
    var onKeyAction: ((action: SwipeDetector.SwipeAction, keySlots: KeySlots, keyIndex: Int) -> Unit)? = null
        set(value) {
            field = value
            keyViews.forEach { it.onKeyAction = value }
        }

    /** Whether the keyboard is in Alt Mode (missing chars layer) */
    var isAltMode: Boolean = false
        set(value) {
            field = value
            keyViews.forEach { it.isAltMode = value }
        }

    private val gapPx = (6 * resources.displayMetrics.density).toInt()
    private var keyHeightDp = 75
    private var keyHeightPx = (keyHeightDp * resources.displayMetrics.density).toInt()

    fun setKeyHeight(dp: Int) {
        keyHeightDp = dp
        keyHeightPx = (keyHeightDp * resources.displayMetrics.density).toInt()
        requestLayout()
    }

    fun setFontScale(scale: Float) {
        keyViews.forEach { it.setFontScale(scale) }
    }

    fun refreshTheme() {
        keyViews.forEach { it.refreshTheme() }
    }

    init {
        for (key in keyViews) {
            addView(key)
        }
    }

    /**
     * Update all 9 keys with new layout data.
     *
     * @param layout Map of "key_1".."key_9" → KeySlots
     */
    fun updateLayout(layout: Map<String, KeySlots>) {
        for (i in 0 until 9) {
            val keyId = "key_${i + 1}"
            val slots = layout[keyId] ?: KeySlots()
            keyViews[i].bind(slots)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        if (availableWidth <= gapPx * 2) {
            setMeasuredDimension(availableWidth, 0)
            return
        }

        // Each key width = (total_width - 2_gaps) / 3
        val keyWidth = (availableWidth - gapPx * 2) / 3

        // Total height = 3 rows × key_height + 2 gaps
        val totalHeight = keyHeightPx * 3 + gapPx * 2

        // Measure each key: wider than tall (rectangular, not square)
        val widthSpec = MeasureSpec.makeMeasureSpec(keyWidth, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(keyHeightPx, MeasureSpec.EXACTLY)
        for (key in keyViews) {
            key.measure(widthSpec, heightSpec)
        }

        setMeasuredDimension(availableWidth, totalHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val keyWidth = keyViews[0].measuredWidth

        for (i in 0 until 9) {
            val row = i / 3
            val col = i % 3

            val left = col * (keyWidth + gapPx)
            val top = row * (keyHeightPx + gapPx)

            keyViews[i].layout(left, top, left + keyWidth, top + keyHeightPx)
        }
    }
}
