package com.flowboard.ime.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.toColorInt
import com.flowboard.ime.data.models.KeySlots

/**
 * Custom view that renders a single key in the 9-key grid.
 *
 * Visual design matching the Flowboard Pro prototype:
 * - Zone-colored gradient background (top→key_bg)
 * - Raised key shadow (2dp bottom offset)
 * - 5-position character labels (center, top, left, right, bottom)
 * - Ripple effect on touch
 * - Center key (key_5) has accent-colored center text
 *
 * @see SwipeDetector for gesture logic
 */
class KeyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * Zone type determines the gradient color of the key background.
     * - TOP: Blue-tinted (#F0F4FF light / #1C2238 dark)
     * - MID: Green-tinted (#F0FFF4 light / #182820 dark)
     * - BOT: Orange-tinted (#FFF7ED light / #302018 dark)
     */
    enum class ZoneType { TOP, MID, BOT }

    /** The current character assignment for this key */
    var keySlots: KeySlots = KeySlots()
        private set

    /** Zone type controlling the gradient background color */
    var zoneType: ZoneType = ZoneType.MID
        set(value) {
            field = value
            gradientDirty = true
            invalidate()
        }

    /** Whether this is the center key (key_5) — center text is accent-colored */
    var isCenterKey: Boolean = false

    /** Whether the keyboard is in Alt Mode */
    var isAltMode: Boolean = false

    private var isPressed = false
    private var gradientDirty = true

    // ── Paints ──
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(38, 0, 0, 0) // ~15% black
    }
    private val tapTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val swipeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    // ── Colors (resolved from resources for theme support) ──
    private var colorKeyBg = 0
    private var colorZoneStart = 0
    private var colorKeyActive = 0
    private var colorTextTap = 0
    private var colorTextSwipe = 0
    private var colorAccent = 0
    private var currentThemeColors: com.flowboard.ime.util.ThemeColors? = null

    // ── Dimensions ──
    private val dp = resources.displayMetrics.density
    @Suppress("DEPRECATION")
    private val sp = resources.displayMetrics.scaledDensity
    private val cornerRadius = 12f * dp
    private val baseTapTextSize = 24f * sp
    private val baseSwipeTextSize = 15f * sp
    private val baseBottomSmallTextSize = 12f * sp
    
    private var tapTextSize = baseTapTextSize
    private var swipeTextSize = baseSwipeTextSize
    private var bottomSmallTextSize = baseBottomSmallTextSize
    private val shadowOffset = 2f * dp

    // ── Geometry ──
    private val boundsRect = RectF()
    private val shadowRect = RectF()

    // ── Gesture Detector ──
    private var swipeDetector: SwipeDetector? = null
    private val boltDrawable = androidx.core.content.ContextCompat.getDrawable(context, com.flowboard.ime.R.drawable.ic_bolt)?.mutate()

    /** The 1-based index of this key (1..9) */
    var keyIndex: Int = 1

    /** Callback for when the user performs a gesture on this key */
    var onKeyAction: ((action: SwipeDetector.SwipeAction, keySlots: KeySlots, keyIndex: Int) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = false

        // Setup ripple foreground for touch feedback
        val mask = GradientDrawable().apply {
            cornerRadius = this@KeyView.cornerRadius
            setColor(Color.WHITE)
        }
        foreground = RippleDrawable(
            ColorStateList.valueOf(Color.argb(30, 128, 128, 128)),
            null,
            mask
        )

        resolveColors()

        swipeDetector = SwipeDetector(
            thresholdPx = 25f * dp
        ) { action ->
            isPressed = false
            invalidate()
            onKeyAction?.invoke(action, keySlots, keyIndex)
        }
    }

    /** Update text sizes based on a scale factor */
    fun setFontScale(scale: Float) {
        tapTextSize = baseTapTextSize * scale
        swipeTextSize = baseSwipeTextSize * scale
        bottomSmallTextSize = baseBottomSmallTextSize * scale
        invalidate()
    }

    fun refreshTheme() {
        resolveColors()
        gradientDirty = true
        invalidate()
    }

    /**
     * Resolve colors from resources to support light/dark theme switching.
     */
    private fun resolveColors() {
        val prefs = context.getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)
        val activeTheme = prefs.getString("active_theme", "Clean Minimal") ?: "Clean Minimal"
        val isOverrideDark = if (prefs.contains("dark_mode_override")) prefs.getBoolean("dark_mode_override", false) else null
        val isSystemDark = isOverrideDark ?: ((context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        val colors = com.flowboard.ime.util.ThemeManager.getThemeColors(context, activeTheme, isSystemDark)
        currentThemeColors = colors

        colorKeyBg = colors.keyBackground
        colorKeyActive = colors.keyActive
        colorTextTap = colors.textTap
        colorTextSwipe = colors.textSwipe
        colorAccent = colors.accent
        shadowPaint.color = if (colors.isDark) "#151517".toColorInt() else "#CACAD0".toColorInt()
        updateZoneColor()
        updateGradientShader()
    }

    private fun updateZoneColor() {
        val colors = currentThemeColors ?: return
        colorZoneStart = when (zoneType) {
            ZoneType.TOP -> colors.zoneTopStart
            ZoneType.MID -> colors.zoneMidStart
            ZoneType.BOT -> colors.zoneBotStart
        }
    }

    private var normalGradientShader: Shader? = null

    private fun updateGradientShader() {
        val h = if (height > 0) height.toFloat() else 100f * dp
        updateZoneColor()
        normalGradientShader = LinearGradient(
            0f, 0f, 0f, h,
            colorZoneStart, colorKeyBg,
            Shader.TileMode.CLAMP
        )
    }

    /**
     * Update the character assignment for this key and trigger a redraw.
     */
    fun bind(slots: KeySlots) {
        this.keySlots = slots
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                invalidate()
            }
        }
        return swipeDetector?.onTouchEvent(event) ?: super.onTouchEvent(event)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGradientShader()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // ── Key Shadow (simulates the CSS box-shadow: 0 2px 0px) ──
        shadowRect.set(1f * dp, shadowOffset, w - 1f * dp, h)
        canvas.drawRoundRect(shadowRect, cornerRadius, cornerRadius, shadowPaint)

        // ── Key Background ──
        boundsRect.set(0f, 0f, w, h - shadowOffset)
        bgPaint.shader = null
        bgPaint.color = if (isPressed) colorKeyActive else colorKeyBg
        canvas.drawRoundRect(boundsRect, cornerRadius, cornerRadius, bgPaint)

        // ════════════════════════════════════════
        // TEXT RENDERING
        // ════════════════════════════════════════
        val contentBottom = boundsRect.bottom
        val centerY = contentBottom / 2f
        val centerX = w / 2f

        // ── Center Character (Tap) ──
        if (keySlots.tap.isNotEmpty()) {
            tapTextPaint.textSize = tapTextSize
            tapTextPaint.color = colorTextTap
            var textY = centerY - (tapTextPaint.descent() + tapTextPaint.ascent()) / 2f
            
            // Shift tall Thai characters downwards slightly to prevent overlapping with top text
            val isTallChar = keySlots.tap.any { it in listOf('ไ', 'ใ', 'โ', 'ป', 'ฝ', 'ฟ', 'ฬ') }
            if (isTallChar) {
                textY += 4f * dp
            }
            
            canvas.drawText(keySlots.tap, centerX, textY, tapTextPaint)
        }

        // Setup swipe text paint
        swipeTextPaint.textSize = swipeTextSize
        swipeTextPaint.color = colorTextSwipe
        swipeTextPaint.alpha = 178 // ~70% opacity (matching CSS opacity: 0.7)

        // ── Top (Swipe Up) ──
        if (keySlots.up.isNotEmpty()) {
            swipeTextPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                keySlots.up,
                centerX,
                4f * dp + swipeTextSize,
                swipeTextPaint
            )
        }

        // ── Left (Swipe Left) ──
        if (keySlots.left.isNotEmpty()) {
            swipeTextPaint.textAlign = Paint.Align.LEFT
            val textY = centerY - (swipeTextPaint.descent() + swipeTextPaint.ascent()) / 2f
            canvas.drawText(
                keySlots.left,
                8f * dp,
                textY,
                swipeTextPaint
            )
        }

        // ── Right (Swipe Right) ──
        if (keySlots.right.isNotEmpty()) {
            swipeTextPaint.textAlign = Paint.Align.RIGHT
            val textY = centerY - (swipeTextPaint.descent() + swipeTextPaint.ascent()) / 2f
            canvas.drawText(
                keySlots.right,
                w - 8f * dp,
                textY,
                swipeTextPaint
            )
        }

        // ── Bottom (Swipe Down) ──
        if (keySlots.down.isNotEmpty()) {
            if (keySlots.down == "⚡" || keySlots.down == "__BOLT__") {
                val boltWidth = (10f * dp).toInt()
                val boltHeight = (12f * dp).toInt()
                val iconLeft = (centerX - boltWidth / 2f).toInt()
                val iconBottom = (contentBottom - 4f * dp).toInt()
                val iconTop = iconBottom - boltHeight
                boltDrawable?.let { d ->
                    d.setBounds(iconLeft, iconTop, iconLeft + boltWidth, iconBottom)
                    d.setTint(colorTextSwipe)
                    d.alpha = 180
                    d.draw(canvas)
                }
            } else {
                swipeTextPaint.textAlign = Paint.Align.CENTER
                swipeTextPaint.textSize = if (keySlots.down.length > 3) bottomSmallTextSize * 0.85f else bottomSmallTextSize
                swipeTextPaint.alpha = 180
                canvas.drawText(
                    keySlots.down,
                    centerX,
                    contentBottom - 6f * dp,
                    swipeTextPaint
                )
            }
        }

        // Reset text align
        swipeTextPaint.textAlign = Paint.Align.CENTER
    }
}
