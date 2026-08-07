package com.flowboard.ime.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.flowboard.ime.MainActivity
import com.flowboard.ime.R
import com.flowboard.ime.data.AssetLoader
import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.KeySlots
import com.flowboard.ime.engine.LanguageManager
import com.flowboard.ime.engine.LayoutManager
import com.flowboard.ime.engine.ProfileManager
import com.flowboard.ime.engine.ScoringEngine
import com.flowboard.ime.ui.KeyboardView
import com.flowboard.ime.ui.SwipeDetector
import com.flowboard.ime.util.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The main InputMethodService for Flowboard.
 */
class FlowboardIMEService : InputMethodService() {

    companion object {
        private const val TAG = "FlowboardIME"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val repo = FlowboardRepository
    private lateinit var scoringEngine: ScoringEngine
    private lateinit var layoutManager: LayoutManager
    private lateinit var languageManager: LanguageManager
    private lateinit var profileManager: ProfileManager

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Settings or theme changed — refreshing input view")
            context?.let { ctx ->
                AssetLoader(ctx).updatePersonalizationState(ctx, repo)
            }
            loadSettings()
            setHandedness(if (isFloatingMode) isFloatingLeftHanded else isDockedLeftHanded)
            setInputView(onCreateInputView())
        }
    }

    // ── Views ──
    private var keyboardView: KeyboardView? = null

    // Prediction Bar
    private var predictionRow: View? = null
    private var predictionBar: LinearLayout? = null
    private var pred1: TextView? = null
    private var pred2: TextView? = null
    private var pred3: TextView? = null
    private var btnDelete: ImageView? = null

    // Side Tools (Control Panel)
    enum class ToolbarAction { HANDEDNESS, THEME, FLOATING, CLIPBOARD, UNDO, RESIZE, MORE, DELETE }
    private val activeShortcuts = mutableListOf(
        ToolbarAction.HANDEDNESS,
        ToolbarAction.THEME,
        ToolbarAction.FLOATING,
        ToolbarAction.CLIPBOARD,
        ToolbarAction.UNDO,
        ToolbarAction.RESIZE
    )
    private val allActions = listOf(
        ToolbarAction.HANDEDNESS,
        ToolbarAction.THEME,
        ToolbarAction.FLOATING,
        ToolbarAction.CLIPBOARD,
        ToolbarAction.UNDO,
        ToolbarAction.RESIZE
    )
    private var sideTools: LinearLayout? = null
    private var dragHandleArea: View? = null
    private var resizeHandleRight: View? = null
    private var clipboardPanel: android.widget.ScrollView? = null
    private var clipboardContent: LinearLayout? = null
    private var keyboardRoot: View? = null

    // Bottom Bar
    private var btnNumbers: TextView? = null
    private var btnShift: FrameLayout? = null
    private var btnShiftIcon: ImageView? = null
    private var btnShiftText: TextView? = null
    private var btnGlobe: ImageView? = null
    private var btnSpace: FrameLayout? = null
    private var btnPeriod: FrameLayout? = null
    private var btnSend: FrameLayout? = null

    // Main Area (for handedness toggle)
    private var mainArea: LinearLayout? = null

    // Height Adjustment Overlay Views
    private var heightAdjustLayout: LinearLayout? = null
    private var btnCloseHeightAdjust: ImageView? = null

    // Control Panel States
    private var isDockedLeftHanded = false
    private var isFloatingLeftHanded = false
    private var isDarkModeOverride: Boolean? = null
    private var isFloatingMode = false
    private var floatingY = 100 // default offset from bottom in dp
    private var currentFloatingScale: Float = 1f
    private var isMorePanelOpen = false

    private fun loadSettings() {
        val prefs = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
        isDockedLeftHanded = prefs.getBoolean("docked_side_tools_left", false)
        isFloatingLeftHanded = prefs.getBoolean("floating_side_tools_left", false)
        loadActiveShortcuts()
    }

    private fun saveActiveShortcuts() {
        val str = activeShortcuts.joinToString(",") { it.name }
        getSharedPreferences("flowboard_settings", MODE_PRIVATE).edit {
            putString("active_shortcuts", str)
        }
    }

    private fun loadActiveShortcuts() {
        val prefs = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
        val str = prefs.getString("active_shortcuts", null)
        if (!str.isNullOrEmpty()) {
            val loaded = str.split(",").mapNotNull { name ->
                try { ToolbarAction.valueOf(name.trim()) } catch (_: Exception) { null }
            }
            if (loaded.isNotEmpty()) {
                activeShortcuts.clear()
                activeShortcuts.addAll(loaded)
            }
        }
    }
    
    // Undo & Minimization States
    private val typedTextHistory = mutableListOf<String>()
    private val clipboardHistory = mutableListOf<String>()
    private var lastDragHandleClickTime = 0L
    private var isMinimized = false
    private var isCommiting = false

    // Window position drag states
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    
    private var resizeInitialTouchX = 0f
    private var resizeInitialTouchY = 0f
    private var resizeInitialWidth = 0

    /** The complete text typed in the current input session */
    private val typedText = StringBuilder()

    /** Whether shift/caps is active */
    var isShiftActive = false

    /** Whether number layer is active */
    private var isNumberMode = false

    /** Which symbol page is active (0 = Core, 1 = Pro) */
    private var symbolPageIndex = 0

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        scoringEngine = ScoringEngine(repo)
        layoutManager = LayoutManager(repo)
        languageManager = LanguageManager(repo)
        profileManager = ProfileManager(repo)

        loadSettings()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, IntentFilter("com.flowboard.ime.ACTION_SETTINGS_CHANGED"), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(settingsReceiver, IntentFilter("com.flowboard.ime.ACTION_SETTINGS_CHANGED"))
        }
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    override fun onCreateInputView(): View {
        Log.d(TAG, "onCreateInputView")
        
        // Ensure Phase A critical data is loaded before inflating anything.
        // This prevents the keyboard from flashing blank when switching from another IME.
        if (!repo.isReady.value) {
            Log.d(TAG, "Waiting for critical data before creating view...")
            runBlocking {
                repo.isReady.first { it }
            }
        }
        
        val themedContext = getThemedContext()
        val inflater = LayoutInflater.from(themedContext)
        val rootView = inflater.inflate(R.layout.keyboard_layout, null, false)

        keyboardRoot = rootView.findViewById(R.id.keyboardRoot)
        dragHandleArea = rootView.findViewById(R.id.dragHandleArea)
        predictionRow = rootView.findViewById(R.id.predictionRow)

        val density = resources.displayMetrics.density
        val baseBottomPadding = if (isFloatingMode) (4 * density).toInt() else (8 * density).toInt()
        val initialNavHeight = if (!isFloatingMode) getSystemNavigationBarHeight() else 0
        keyboardRoot?.setPadding(
            (6 * density).toInt(),
            (8 * density).toInt(),
            (6 * density).toInt(),
            baseBottomPadding + initialNavHeight
        )

        setupNavigationBarPadding(rootView)

        if (isFloatingMode) {
            keyboardRoot?.background = ContextCompat.getDrawable(themedContext, R.drawable.floating_kb_bg)
            dragHandleArea?.visibility = View.VISIBLE
            resizeHandleRight?.visibility = View.VISIBLE
            predictionRow?.visibility = View.GONE
            setupDragHandle()
        } else {
            keyboardRoot?.setBackgroundColor(ContextCompat.getColor(themedContext, R.color.kb_background))
            dragHandleArea?.visibility = View.VISIBLE
            resizeHandleRight?.visibility = View.VISIBLE
            predictionRow?.visibility = View.VISIBLE
        }

        // ══════════════════════════════════════════
        // Prediction Bar
        // ══════════════════════════════════════════
        predictionBar = rootView.findViewById(R.id.predictionBar)
        resizeHandleRight = rootView.findViewById<View>(R.id.resizeHandleRight).apply {
            setOnTouchListener { _, event -> handleResizeTouch(event) }
        }
        clipboardPanel = rootView.findViewById(R.id.clipboardPanel)
        clipboardContent = rootView.findViewById(R.id.clipboardContent)
        pred1 = rootView.findViewById<TextView>(R.id.pred1).apply {
            setOnClickListener { usePrediction(this) }
        }
        pred2 = rootView.findViewById<TextView>(R.id.pred2).apply {
            setOnClickListener { usePrediction(this) }
        }
        pred3 = rootView.findViewById<TextView>(R.id.pred3).apply {
            setOnClickListener { usePrediction(this) }
        }
        btnDelete = rootView.findViewById<ImageView>(R.id.btnDelete).apply {
            setOnClickListener { handleDelete() }
            var deleteRunnable: Runnable? = null
            val handler = Handler(Looper.getMainLooper())
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        handleDelete()
                        val r = object : Runnable {
                            override fun run() {
                                handleDelete()
                                handler.postDelayed(this, 50)
                            }
                        }
                        deleteRunnable = r
                        handler.postDelayed(r, 400)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        deleteRunnable?.let { handler.removeCallbacks(it) }
                        true
                    }
                    else -> false
                }
            }
        }

        // ══════════════════════════════════════════
        // 3×3 Key Grid
        // ══════════════════════════════════════════
        keyboardView = rootView.findViewById<KeyboardView>(R.id.keyboardView).apply {
            onKeyAction = { action, keySlots ->
                handleKeyAction(action, keySlots)
            }
        }

        val morePanel = rootView.findViewById<GridLayout>(R.id.morePanel)
        if (isMorePanelOpen) {
            keyboardView?.visibility = View.GONE
            morePanel?.visibility = View.VISIBLE
            rootView.post {
                keyboardView?.let { kv ->
                    val lp = morePanel.layoutParams
                    val kvHeight = kv.height
                    lp.height = if (kvHeight > 0) kvHeight else (220 * resources.displayMetrics.density).toInt()
                    morePanel.layoutParams = lp
                }
                renderMorePanel()
            }
        } else {
            keyboardView?.visibility = View.VISIBLE
            morePanel?.visibility = View.GONE
        }

        // ══════════════════════════════════════════
        // Side Tools (Control Panel)
        // ══════════════════════════════════════════
        sideTools = rootView.findViewById(R.id.sideTools)
        mainArea = rootView.findViewById(R.id.mainArea)
        renderToolbar()

        // ══════════════════════════════════════════
        // Bottom Bar
        // ══════════════════════════════════════════
        btnNumbers = rootView.findViewById(R.id.btnNumbers)
        btnShift = rootView.findViewById(R.id.btnShift)
        btnShiftIcon = rootView.findViewById(R.id.btnShiftIcon)
        btnShiftText = rootView.findViewById(R.id.btnShiftText)
        btnGlobe = rootView.findViewById(R.id.btnGlobe)

        // Space bar with swipe-down detector (TAP -> Space, DOWN -> "0")
        val spaceSwipeDetector = SwipeDetector(thresholdPx = 25f * resources.displayMetrics.density) { action ->
            if (isNumberMode) {
                when (action) {
                    SwipeDetector.SwipeAction.TAP -> commitChar("0")
                    SwipeDetector.SwipeAction.DOWN -> commitChar(" ")
                    else -> commitChar("0")
                }
            } else {
                when (action) {
                    SwipeDetector.SwipeAction.DOWN -> commitChar("0")
                    else -> commitChar(" ")
                }
            }
        }
        btnSpace = rootView.findViewById<FrameLayout>(R.id.btnSpace).apply {
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.isPressed = true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.isPressed = false
                }
                spaceSwipeDetector.onTouchEvent(event)
                v.performClick()
                true
            }
        }

        // Period with swipe-up detector (TAP -> ".", UP -> ",")
        val periodSwipeDetector = SwipeDetector(thresholdPx = 25f * resources.displayMetrics.density) { action ->
            when (action) {
                SwipeDetector.SwipeAction.TAP -> commitChar(".")
                SwipeDetector.SwipeAction.UP -> commitChar(",")
                else -> {}
            }
        }
        btnPeriod = rootView.findViewById<FrameLayout>(R.id.btnPeriod).apply {
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.isPressed = true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.isPressed = false
                }
                periodSwipeDetector.onTouchEvent(event)
                v.performClick()
                true
            }
        }

        btnSend = rootView.findViewById<FrameLayout>(R.id.btnSend).apply {
            setOnClickListener { handleSend() }
        }

        // Height Adjustment Overlay Setup
        heightAdjustLayout = rootView.findViewById(R.id.heightAdjustLayout)
        btnCloseHeightAdjust = rootView.findViewById(R.id.btnCloseHeightAdjust)
        
        val btnSizeSmall = rootView.findViewById<Button>(R.id.btnSizeSmall)
        val btnSizeMedium = rootView.findViewById<Button>(R.id.btnSizeMedium)
        val btnSizeLarge = rootView.findViewById<Button>(R.id.btnSizeLarge)

        val prefs = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
        // Ensure default is 1.2f if not set
        var currentScale = prefs.getFloat("docked_keyboard_scale", 1.2f)
        applyDockedScale(currentScale)

        fun updateActiveButton() {
            btnSizeSmall?.backgroundTintList = ColorStateList.valueOf(if (currentScale <= 1.05f) Color.DKGRAY else Color.LTGRAY)
            btnSizeSmall?.setTextColor(if (currentScale <= 1.05f) Color.WHITE else Color.BLACK)
            
            val isMedium = currentScale > 1.05f && currentScale < 1.4f
            btnSizeMedium?.backgroundTintList = ColorStateList.valueOf(if (isMedium) Color.DKGRAY else Color.LTGRAY)
            btnSizeMedium?.setTextColor(if (isMedium) Color.WHITE else Color.BLACK)
            
            btnSizeLarge?.backgroundTintList = ColorStateList.valueOf(if (currentScale >= 1.4f) Color.DKGRAY else Color.LTGRAY)
            btnSizeLarge?.setTextColor(if (currentScale >= 1.4f) Color.WHITE else Color.BLACK)
        }

        updateActiveButton()

        btnSizeSmall?.setOnClickListener {
            currentScale = 1.0f
            prefs.edit { putFloat("docked_keyboard_scale", currentScale) }
            applyDockedScale(currentScale)
            updateActiveButton()
        }
        
        btnSizeMedium?.setOnClickListener {
            currentScale = 1.2f
            prefs.edit { putFloat("docked_keyboard_scale", currentScale) }
            applyDockedScale(currentScale)
            updateActiveButton()
        }
        
        btnSizeLarge?.setOnClickListener {
            currentScale = 1.5f
            prefs.edit { putFloat("docked_keyboard_scale", currentScale) }
            applyDockedScale(currentScale)
            updateActiveButton()
        }

        btnCloseHeightAdjust?.setOnClickListener {
            heightAdjustLayout?.visibility = View.GONE
        }

        // Initial setups
        refreshLayout()
        
        // Listen for repository readiness to populate UI if it wasn't ready on first load
        serviceScope.launch {
            repo.isReady.collect { ready ->
                if (ready) {
                    refreshLayout()
                }
            }
        }
        updatePredictions()
        updateSpaceLabelForMode()
        updateShiftButtonTint()
        setHandedness(if (isFloatingMode) isFloatingLeftHanded else isDockedLeftHanded)

        applySettingsAndTheme(rootView, themedContext)

        return rootView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView (restarting=$restarting)")

        loadSettings()

        if (::scoringEngine.isInitialized) {
            scoringEngine.resetTrieCache()
        }
        
        keyboardRoot?.let { applySettingsAndTheme(it, getThemedContext()) }
        updateSendButtonIcon(info)
        updateFloatingWindowMode()

        // Reset typing state for a new input field
        synchronized(typedText) {
            typedText.clear()
            typedTextHistory.clear()
        }
        isShiftActive = false
        isNumberMode = false
        symbolPageIndex = 0
        @Suppress("SetTextI18n")
        btnNumbers?.text = "?12"

        setHandedness(if (isFloatingMode) isFloatingLeftHanded else isDockedLeftHanded)

        refreshLayout()
        updatePredictions()
        updateSpaceLabelForMode()
        updateShiftButtonTint()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Log.d(TAG, "onFinishInputView")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        try {
            unregisterReceiver(settingsReceiver)
        } catch (_: Exception) {
            // ignore
        }
        serviceScope.cancel()
    }

    // ══════════════════════════════════════════
    // Control Panel Helper Methods
    // ══════════════════════════════════════════

    private fun renderToolbar() {
        val tools = sideTools ?: return
        tools.removeAllViews()
        val prefs = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
        val scale = if (!isFloatingMode) prefs.getFloat("docked_keyboard_scale", 1.3f) else currentFloatingScale
        
        val displayActions = if (isFloatingMode) {
            val floatingShortcuts = activeShortcuts.filter { it != ToolbarAction.RESIZE }
            val count = when {
                scale <= 0.96f -> 3
                scale >= 1.12f -> 5
                else -> 4
            }
            listOf(ToolbarAction.DELETE) + floatingShortcuts.take(count) + ToolbarAction.MORE
        } else {
            val maxToolbarItems = when {
                scale <= 1.05f -> 4
                scale >= 1.4f -> 7
                else -> 6
            }
            activeShortcuts.take(maxToolbarItems - 1) + ToolbarAction.MORE
        }

        for (action in displayActions) {
            val iconRes = when (action) {
                ToolbarAction.HANDEDNESS -> R.drawable.ic_handedness
                ToolbarAction.THEME -> R.drawable.ic_theme
                ToolbarAction.FLOATING -> if (isFloatingMode) R.drawable.ic_dock else R.drawable.ic_float
                ToolbarAction.CLIPBOARD -> R.drawable.ic_clipboard
                ToolbarAction.UNDO -> R.drawable.ic_undo
                ToolbarAction.RESIZE -> R.drawable.ic_resize
                ToolbarAction.DELETE -> R.drawable.ic_backspace
                ToolbarAction.MORE -> if (isMorePanelOpen || clipboardPanel?.visibility == View.VISIBLE) android.R.drawable.ic_menu_close_clear_cancel else R.drawable.ic_more
            }

            val iv = ImageView(getThemedContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                setImageResource(iconRes)
                
                val p = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
                val activeTheme = p.getString("active_theme", "Clean Minimal") ?: "Clean Minimal"
                val colors = ThemeManager.getThemeColors(context, activeTheme, isEffectiveDarkMode())
                imageTintList = ColorStateList.valueOf(colors.textTap)
                
                val outValue = TypedValue()
                getThemedContext().theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
                setBackgroundResource(outValue.resourceId)
                
                minimumHeight = 1
                minimumWidth = 1
                
                scaleType = ImageView.ScaleType.FIT_CENTER
                val padding = (8 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                
                setOnClickListener {
                    executeToolbarAction(action)
                }

                if (action == ToolbarAction.DELETE) {
                    var deleteRunnable: Runnable? = null
                    val handler = Handler(Looper.getMainLooper())
                    @SuppressLint("ClickableViewAccessibility")
                    setOnTouchListener { v, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                v.isPressed = true
                                handleDelete()
                                val r = object : Runnable {
                                    override fun run() {
                                        handleDelete()
                                        handler.postDelayed(this, 50)
                                    }
                                }
                                deleteRunnable = r
                                handler.postDelayed(r, 400)
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                v.isPressed = false
                                deleteRunnable?.let { handler.removeCallbacks(it) }
                                true
                            }
                            else -> false
                        }
                    }
                }

                val isCustomizable = action in activeShortcuts
                if (isCustomizable) {
                    val slotIndex = activeShortcuts.indexOf(action)
                    tag = slotIndex
                    
                    setOnDragListener { v, event ->
                        when (event.action) {
                            android.view.DragEvent.ACTION_DRAG_STARTED -> true
                            android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                                v.alpha = 0.5f
                                true
                            }
                            android.view.DragEvent.ACTION_DRAG_EXITED -> {
                                v.alpha = 1.0f
                                true
                            }
                            android.view.DragEvent.ACTION_DROP -> {
                                v.alpha = 1.0f
                                val actionStr = event.clipData.getItemAt(0).text.toString()
                                val droppedAction = ToolbarAction.valueOf(actionStr)
                                val targetSlot = v.tag as? Int ?: -1
                                if (targetSlot in activeShortcuts.indices) {
                                    val existingIndex = activeShortcuts.indexOf(droppedAction)
                                    if (existingIndex != -1) {
                                        activeShortcuts[existingIndex] = activeShortcuts[targetSlot]
                                    }
                                    activeShortcuts[targetSlot] = droppedAction
                                    saveActiveShortcuts()
                                    renderToolbar()
                                    renderMorePanel()
                                }
                                true
                            }
                            android.view.DragEvent.ACTION_DRAG_ENDED -> {
                                v.alpha = 1.0f
                                true
                            }
                            else -> false
                        }
                    }
                }
            }
            tools.addView(iv)
        }
    }

    private fun executeToolbarAction(action: ToolbarAction) {
        when (action) {
            ToolbarAction.HANDEDNESS -> {
                if (isFloatingMode) {
                    isFloatingLeftHanded = !isFloatingLeftHanded
                    getSharedPreferences("flowboard_settings", MODE_PRIVATE).edit {
                        putBoolean("floating_side_tools_left", isFloatingLeftHanded)
                    }
                    setHandedness(isFloatingLeftHanded)
                } else {
                    isDockedLeftHanded = !isDockedLeftHanded
                    getSharedPreferences("flowboard_settings", MODE_PRIVATE).edit {
                        putBoolean("docked_side_tools_left", isDockedLeftHanded)
                    }
                    setHandedness(isDockedLeftHanded)
                }
            }
            ToolbarAction.THEME -> {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("OPEN_PAGE", "themes.html")
                }
                startActivity(intent)
            }
            ToolbarAction.FLOATING -> {
                isFloatingMode = !isFloatingMode
                updateFloatingWindowMode()
                renderToolbar()
            }
            ToolbarAction.CLIPBOARD -> {
                val isClipboardOpen = clipboardPanel?.visibility == View.VISIBLE
                if (isClipboardOpen) {
                    clipboardPanel?.visibility = View.GONE
                    keyboardView?.visibility = View.VISIBLE
                } else {
                    keyboardView?.visibility = View.GONE
                    val morePanel = keyboardRoot?.findViewById<GridLayout>(R.id.morePanel)
                    morePanel?.visibility = View.GONE
                    isMorePanelOpen = false
                    
                    clipboardPanel?.visibility = View.VISIBLE
                    keyboardView?.let { kv ->
                        val lp = clipboardPanel?.layoutParams
                        if (lp != null) {
                            lp.height = if (kv.height > 0) kv.height else (220 * resources.displayMetrics.density).toInt()
                            clipboardPanel?.layoutParams = lp
                        }
                    }
                    updateClipboardPanel()
                }
                renderToolbar()
            }
            ToolbarAction.UNDO -> {
                handleUndo()
            }
            ToolbarAction.RESIZE -> {
                if (!isFloatingMode) {
                    heightAdjustLayout?.visibility = if (heightAdjustLayout?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    val morePanel = keyboardRoot?.findViewById<GridLayout>(R.id.morePanel)
                    if (morePanel?.visibility == View.VISIBLE) {
                        morePanel.visibility = View.GONE
                        keyboardView?.visibility = View.VISIBLE
                        isMorePanelOpen = false
                        renderToolbar()
                    }
                }
            }
            ToolbarAction.DELETE -> {
                handleDelete()
            }
            ToolbarAction.MORE -> {
                if (clipboardPanel?.visibility == View.VISIBLE) {
                    clipboardPanel?.visibility = View.GONE
                    keyboardView?.visibility = View.VISIBLE
                    renderToolbar()
                    return
                }
                
                isMorePanelOpen = !isMorePanelOpen
                val morePanel = keyboardRoot?.findViewById<GridLayout>(R.id.morePanel)
                if (isMorePanelOpen) {
                    keyboardView?.visibility = View.GONE
                    morePanel?.visibility = View.VISIBLE
                    keyboardView?.let { kv ->
                        val lp = morePanel?.layoutParams
                        if (lp != null) {
                            val kvHeight = kv.height
                            lp.height = if (kvHeight > 0) kvHeight else (220 * resources.displayMetrics.density).toInt()
                            morePanel.layoutParams = lp
                        }
                    }
                    renderMorePanel()
                } else {
                    keyboardView?.visibility = View.VISIBLE
                    morePanel?.visibility = View.GONE
                }
                renderToolbar()
            }
        }
    }

    private fun renderMorePanel() {
        val panel = keyboardRoot?.findViewById<GridLayout>(R.id.morePanel) ?: return
        panel.removeAllViews()
        
        val context = getThemedContext()
        val density = resources.displayMetrics.density
        
        for (action in allActions) {
            val itemLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val padding = (8 * density).toInt()
                setPadding(padding, padding, padding, padding)
                
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
            }
            
            val iconRes = when (action) {
                ToolbarAction.HANDEDNESS -> R.drawable.ic_handedness
                ToolbarAction.THEME -> R.drawable.ic_theme
                ToolbarAction.FLOATING -> if (isFloatingMode) R.drawable.ic_dock else R.drawable.ic_float
                ToolbarAction.CLIPBOARD -> R.drawable.ic_clipboard
                ToolbarAction.UNDO -> R.drawable.ic_undo
                ToolbarAction.RESIZE -> R.drawable.ic_resize
                ToolbarAction.DELETE -> R.drawable.ic_backspace
                ToolbarAction.MORE -> R.drawable.ic_more
            }
            
            val iv = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (36 * density).toInt(),
                    (36 * density).toInt()
                )
                setImageResource(iconRes)
            }
            
            val tv = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * density).toInt()
                }
                @Suppress("SetTextI18n")
                text = when (action) {
                    ToolbarAction.HANDEDNESS -> "Switch Hand"
                    ToolbarAction.THEME -> "Theme"
                    ToolbarAction.FLOATING -> "Float"
                    ToolbarAction.CLIPBOARD -> "Clipboard"
                    ToolbarAction.UNDO -> "Undo"
                    ToolbarAction.RESIZE -> "Resize"
                    else -> ""
                }
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_tap))
                gravity = Gravity.CENTER
            }
            
            itemLayout.addView(iv)
            itemLayout.addView(tv)
            
            itemLayout.setOnClickListener {
                executeToolbarAction(action)
            }
            
            itemLayout.setOnLongClickListener { v ->
                val clipData = android.content.ClipData.newPlainText("action", action.name)
                val shadowBuilder = View.DragShadowBuilder(v)
                v.startDragAndDrop(clipData, shadowBuilder, v, 0)
                true
            }
            
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            }
            itemLayout.layoutParams = params
            panel.addView(itemLayout)
        }
    }


    private fun updateClipboardPanel() {
        val container = clipboardContent ?: return
        container.removeAllViews()
        
        // Ensure primary clip is in history if missing
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        val item = clipboard?.primaryClip?.getItemAt(0)
        val currentClip = item?.coerceToText(this)?.toString()
        if (!currentClip.isNullOrEmpty() && !clipboardHistory.contains(currentClip)) {
            clipboardHistory.add(0, currentClip)
        }
        
        if (clipboardHistory.isNotEmpty()) {
            for (textToPaste in clipboardHistory) {
                val card = FrameLayout(getThemedContext()).apply {
                    val margin = (4 * resources.displayMetrics.density).toInt()
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(margin, margin, margin, margin)
                    }
                    setBackgroundResource(R.drawable.prediction_key_bg)
                    
                    val tv = TextView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        text = textToPaste
                        textSize = 15f
                        maxLines = 3
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(ContextCompat.getColor(context, R.color.text_tap))
                        val padding = (12 * resources.displayMetrics.density).toInt()
                        setPadding(padding, padding, padding, padding)
                    }
                    addView(tv)
                    
                    setOnClickListener {
                        currentInputConnection?.commitText(textToPaste, 1)
                        clipboardPanel?.visibility = View.GONE
                        keyboardView?.visibility = View.VISIBLE
                    }
                }
                
                container.addView(card)
            }
        } else {
            val tv = TextView(getThemedContext()).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                @Suppress("SetTextI18n")
                text = "Clipboard is empty"
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.text_swipe))
                gravity = Gravity.CENTER
                val padding = (24 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
            }
            container.addView(tv)
        }
    }

    private fun getThemedContext(): Context {
        val baseContext = if (isDarkModeOverride != null) {
            val config = Configuration(resources.configuration)
            config.uiMode = if (isDarkModeOverride!!) {
                (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            } else {
                (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
            }
            createConfigurationContext(config)
        } else {
            this
        }
        return ContextThemeWrapper(baseContext, R.style.Theme_Flowboard)
    }

    private fun isSystemDarkMode(): Boolean {
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES
    }

    @Suppress("DEPRECATION")
    private fun updateFloatingWindowMode() {
        val win = window?.window ?: return
        val lp = win.attributes
        val metrics = resources.displayMetrics
        val predictionRowView = win.decorView.findViewById<View>(R.id.predictionRow)
        
        val root = keyboardRoot ?: return
        setHandedness(if (isFloatingMode) isFloatingLeftHanded else isDockedLeftHanded)
        val density = metrics.density
        val btnNumbersView = root.findViewById<View>(R.id.btnNumbers)
        val btnShiftView = root.findViewById<View>(R.id.btnShift)
        val btnGlobeView = root.findViewById<View>(R.id.btnGlobe)
        val btnSpaceView = root.findViewById<View>(R.id.btnSpace)
        val btnPeriodView = root.findViewById<View>(R.id.btnPeriod)
        val btnSendView = root.findViewById<View>(R.id.btnSend)
        
        val sideToolsView = root.findViewById<View>(R.id.sideTools)
        val kbView = root.findViewById<KeyboardView>(R.id.keyboardView)
        val morePanelView = root.findViewById<View>(R.id.morePanel)
        val clipboardPanelView = root.findViewById<View>(R.id.clipboardPanel)

        val floatingControlBar = root.findViewById<View>(R.id.floatingControlBar)

        if (isFloatingMode) {
            val prefs = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
            val showSuggestions = prefs.getBoolean("show_suggestions", true)
            predictionRowView?.visibility = if (showSuggestions) View.VISIBLE else View.GONE
            btnDelete?.visibility = View.GONE
            floatingControlBar?.visibility = View.VISIBLE
            dragHandleArea?.visibility = View.VISIBLE
            resizeHandleRight?.visibility = View.VISIBLE
            setupDragHandle()

            val activeTheme = prefs.getString("active_theme", "Clean Minimal") ?: "Clean Minimal"
            val gd = android.graphics.drawable.GradientDrawable()
            gd.cornerRadius = 16 * density
            gd.setColor(ThemeManager.getThemeColors(this, activeTheme, isEffectiveDarkMode()).keyboardBackground)
            
            // Apply background to the root so it scales with the keyboard
            val outerFrame = root.parent as? View
            outerFrame?.background = null
            root.background = gd

            val prefsFloat = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
            val savedScale = prefsFloat.getFloat("floating_scale", 1f).coerceIn(0.88f, 1.20f)
            currentFloatingScale = savedScale
            renderToolbar()
            val baseKbWidth = (300 * metrics.density)
            val kbWidth = (baseKbWidth * savedScale).toInt()
            
            lp.width = kbWidth
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            
            val rootLp = root.layoutParams as? FrameLayout.LayoutParams
            if (rootLp != null) {
                rootLp.width = ViewGroup.LayoutParams.MATCH_PARENT
                rootLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                rootLp.bottomMargin = 0
                root.layoutParams = rootLp
            }
            
            val contentLp = root.findViewById<View>(R.id.keyboardContent)?.layoutParams as? FrameLayout.LayoutParams
            if (contentLp != null) {
                contentLp.bottomMargin = 0
                root.findViewById<View>(R.id.keyboardContent)?.layoutParams = contentLp
            }
            root.setPadding((6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setBackDisposition(BACK_DISPOSITION_WILL_DISMISS)
            }
            
            lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            lp.gravity = Gravity.BOTTOM or Gravity.START
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels
            lp.x = maxOf(0, minOf((screenWidth - kbWidth) / 2, screenWidth - kbWidth))
            
            root.measure(
                View.MeasureSpec.makeMeasureSpec(kbWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val kbHeight = root.measuredHeight
            val maxY = maxOf(0, screenHeight - kbHeight - (20 * density).toInt())
            lp.y = maxOf(0, minOf((floatingY * metrics.density).toInt(), maxY))
            
            root.scaleX = 1f
            root.scaleY = 1f
            
            kbView?.setKeyHeight((75 * savedScale).toInt())
            kbView?.setFontScale(savedScale)
            
            // Set proportional weights for floating mode
            btnNumbersView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.weight = 1.5f } }
            btnShiftView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.weight = 1.2f } }
            btnGlobeView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.weight = 1.2f } }
            btnSpaceView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.weight = 3.3f } }
            btnPeriodView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.weight = 1.2f } }
            btnSendView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.weight = 1.6f } }
            
            sideToolsView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.height = ViewGroup.LayoutParams.MATCH_PARENT; lp.weight = 1.4f } }
            kbView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.weight = 8.6f } }
            morePanelView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.weight = 8.6f } }
            clipboardPanelView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.weight = 8.6f } }
        } else {
            val prefs = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
            val showSuggestions = prefs.getBoolean("show_suggestions", true)
            predictionRowView?.visibility = if (showSuggestions) View.VISIBLE else View.GONE
            btnDelete?.visibility = View.VISIBLE
            floatingControlBar?.visibility = View.GONE
            
            // Remove rounded background for docked mode
            val activeTheme = prefs.getString("active_theme", "Clean Minimal") ?: "Clean Minimal"
            val outerFrame = root.parent as? View
            outerFrame?.background = null
            root.setBackgroundColor(ThemeManager.getThemeColors(this, activeTheme, isEffectiveDarkMode()).keyboardBackground)
            
            // Reset scale down transformations back to original (1f)
            root.scaleX = 1f
            root.scaleY = 1f
            val dockedScale = prefs.getFloat("docked_keyboard_scale", 1.3f)
            applyDockedScale(dockedScale)
            
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.gravity = Gravity.BOTTOM
            lp.flags = lp.flags and android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
            lp.x = 0
            lp.y = 0
            
            val contentLp = root.findViewById<View>(R.id.keyboardContent)?.layoutParams as? FrameLayout.LayoutParams
            if (contentLp != null) {
                contentLp.bottomMargin = 0
                root.findViewById<View>(R.id.keyboardContent)?.layoutParams = contentLp
            }
            
            // Restore fixed widths for docked mode
            btnNumbersView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = (46 * density).toInt(); lp.weight = 0f } }
            btnShiftView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = (38 * density).toInt(); lp.weight = 0f } }
            btnGlobeView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = (38 * density).toInt(); lp.weight = 0f } }
            btnSpaceView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.weight = 1f } }
            btnPeriodView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = (38 * density).toInt(); lp.weight = 0f } }
            btnSendView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = (50 * density).toInt(); lp.weight = 0f } }
            
            sideToolsView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = (42 * density).toInt(); lp.height = ViewGroup.LayoutParams.MATCH_PARENT; lp.weight = 0f } }
            kbView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.weight = 1f } }
            morePanelView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.weight = 1f } }
            clipboardPanelView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.weight = 1f } }

            val systemNavHeight = getSystemNavigationBarHeight()
            val baseBottomPadding = (8 * density).toInt()
            root.setPadding(
                root.paddingLeft,
                root.paddingTop,
                root.paddingRight,
                baseBottomPadding + systemNavHeight
            )
            root.requestApplyInsets()
            WindowCompat.setDecorFitsSystemWindows(win, true)
            val controller = WindowCompat.getInsetsController(win, win.decorView)
            controller.show(WindowInsetsCompat.Type.navigationBars())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setBackDisposition(BACK_DISPOSITION_WILL_DISMISS)
            }
        }
        win.attributes = lp
        updatePredictions()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragHandle() {
        val handle = dragHandleArea ?: return
        handle.setOnTouchListener { _, event ->
            val win = window?.window ?: return@setOnTouchListener false
            val lp = win.attributes
            val metrics = resources.displayMetrics

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val clickTime = System.currentTimeMillis()
                    if (clickTime - lastDragHandleClickTime < 300) {
                        toggleMinimization()
                        return@setOnTouchListener true
                    }
                    lastDragHandleClickTime = clickTime
                    initialX = lp.x
                    initialY = lp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isMinimized) return@setOnTouchListener false
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    val screenWidth = metrics.widthPixels
                    val screenHeight = metrics.heightPixels
                    val width = lp.width
                    val root = keyboardRoot
                    val kbHeight = root?.height ?: (250 * metrics.density).toInt()
                    val maxY = maxOf(0, screenHeight - kbHeight - (20 * metrics.density).toInt())

                    lp.x = maxOf(0, minOf(initialX + dx, screenWidth - width))
                    lp.y = maxOf(0, minOf(initialY - dy, maxY))

                    val density = if (metrics.density > 0) metrics.density else 1.0f
                    floatingY = (lp.y / density).toInt()

                    win.attributes = lp
                    true
                }
                else -> false
            }
        }
    }

    private fun handleResizeTouch(event: MotionEvent): Boolean {
        val win = window?.window ?: return false
        val root = keyboardRoot ?: return false
        val metrics = resources.displayMetrics
        val baseWidth = 300 * metrics.density
        if (baseWidth <= 0) return false

        val lp = win.attributes

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizeInitialTouchX = event.rawX
                resizeInitialTouchY = event.rawY
                resizeInitialWidth = lp.width
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - resizeInitialTouchX
                
                val minWidth = (264 * metrics.density).toInt()
                val maxWidth = (359 * metrics.density).toInt()
                val targetWidth = (resizeInitialWidth + dx).toInt().coerceIn(minWidth, maxWidth)
                
                val scale = (targetWidth / baseWidth).coerceIn(0.88f, 1.20f)
                currentFloatingScale = scale
                
                lp.width = targetWidth
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                win.attributes = lp
                
                root.scaleX = 1f
                root.scaleY = 1f
                
                val kbView = root.findViewById<KeyboardView>(R.id.keyboardView)
                kbView?.setKeyHeight((75 * scale).toInt())
                kbView?.setFontScale(scale)
                renderToolbar()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val scale = (lp.width / baseWidth).coerceIn(0.88f, 1.20f)
                getSharedPreferences("flowboard_settings", MODE_PRIVATE).edit {
                    putFloat("floating_scale", scale)
                }
                return true
            }
        }
        return false
    }

    private fun toggleMinimization() {
        val win = window?.window ?: return
        val lp = win.attributes
        val root = keyboardRoot ?: return
        val metrics = resources.displayMetrics

        isMinimized = !isMinimized

        if (isMinimized) {
            root.scaleX = 0.2f
            root.scaleY = 0.2f
            root.pivotX = root.width / 2f
            root.pivotY = root.height / 2f
            
            val bubbleSize = (60 * metrics.density).toInt()
            lp.width = bubbleSize
            lp.height = bubbleSize
            
            root.findViewById<View>(R.id.keyboardView)?.visibility = View.GONE
            root.findViewById<View>(R.id.bottomBar)?.visibility = View.GONE
            root.findViewById<View>(R.id.sideTools)?.visibility = View.GONE
            root.findViewById<View>(R.id.dragHandleArea)?.visibility = View.GONE
            
            root.setBackgroundResource(R.drawable.ic_float)
            
            root.setOnClickListener {
                toggleMinimization()
            }
        } else {
            root.scaleX = 1f
            root.scaleY = 1f
            
            val kbWidth = (300 * metrics.density).toInt()
            lp.width = kbWidth
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            
            root.findViewById<View>(R.id.keyboardView)?.visibility = View.VISIBLE
            root.findViewById<View>(R.id.bottomBar)?.visibility = View.VISIBLE
            root.findViewById<View>(R.id.sideTools)?.visibility = View.VISIBLE
            root.findViewById<View>(R.id.dragHandleArea)?.visibility = View.VISIBLE
            
            root.setBackgroundResource(R.drawable.floating_kb_bg)
            
            root.setOnClickListener(null)
            root.isClickable = false
        }
        win.attributes = lp
    }

    @Suppress("SetTextI18n")
    private fun updateSpaceLabelForMode() {
        val mainText = keyboardRoot?.findViewById<TextView>(R.id.btnSpaceText)
        val downText = keyboardRoot?.findViewById<TextView>(R.id.btnSpaceDownText)
        
        if (isNumberMode) {
            mainText?.text = "0"
            downText?.text = "EN"
        } else {
            mainText?.text = "EN"
            downText?.text = "0"
        }
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        updateSpaceLabelForMode()
    }

    private fun updateShiftButtonTint() {
        val prefs = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
        val activeTheme = prefs.getString("active_theme", "Clean Minimal") ?: "Clean Minimal"
        val colors = ThemeManager.getThemeColors(this, activeTheme, isEffectiveDarkMode())
        
        if (isNumberMode) {
            btnShiftIcon?.visibility = View.GONE
            btnShiftText?.visibility = View.VISIBLE
            btnShiftText?.text = if (symbolPageIndex == 0) "1/2" else "2/2"
            val color = if (symbolPageIndex == 1) colors.accent else colors.textTap
            btnShiftText?.setTextColor(color)
        } else {
            btnShiftText?.visibility = View.GONE
            btnShiftIcon?.visibility = View.VISIBLE
            
            val color = when (languageManager.shiftState) {
                LanguageManager.ShiftState.OFF -> colors.textTap
                LanguageManager.ShiftState.SHIFT_ONCE -> colors.accent
                LanguageManager.ShiftState.CAPS_LOCK -> colors.accent
            }
            btnShiftIcon?.imageTintList = ColorStateList.valueOf(color)
        }
    }

    private fun toggleNumberMode() {
        isNumberMode = !isNumberMode
        symbolPageIndex = 0
        
        btnNumbers?.text = if (isNumberMode) "Aa" else "?12"
        
        val prefs = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
        val showSuggestions = prefs.getBoolean("show_suggestions", true)
        if (isNumberMode) {
            predictionBar?.visibility = View.INVISIBLE
            predictionRow?.visibility = if (!isFloatingMode) View.VISIBLE else View.GONE
        } else {
            predictionRow?.visibility = if (showSuggestions && !isFloatingMode) View.VISIBLE else View.GONE
            predictionBar?.visibility = View.VISIBLE
            updatePredictions()
        }
        
        updateSpaceLabelForMode()
        refreshLayout()
        updateShiftButtonTint()
    }

    // ══════════════════════════════════════════
    // Input Handling
    // ══════════════════════════════════════════

    private fun getKeyId(keySlots: KeySlots): String? {
        val down = keySlots.down
        if (down.isEmpty()) return null
        val num = down.toIntOrNull()
        return if (num != null) "key_$num" else null
    }

    private fun handleKeyAction(action: SwipeDetector.SwipeAction, keySlots: KeySlots) {
        if (isNumberMode && action == SwipeDetector.SwipeAction.DOWN) {
            handleNumberModeDownSwipe(keySlots)
            return
        }

        val keyId = getKeyId(keySlots)
        val charToType = when (action) {
            SwipeDetector.SwipeAction.TAP -> keySlots.tap
            SwipeDetector.SwipeAction.UP -> keySlots.up
            SwipeDetector.SwipeAction.DOWN -> keySlots.down
            SwipeDetector.SwipeAction.LEFT -> keySlots.left
            SwipeDetector.SwipeAction.RIGHT -> keySlots.right
        }

        if (charToType.isNotEmpty()) {
            if (keyId != null && action != SwipeDetector.SwipeAction.DOWN) {
                val slotStr = when (action) {
                    SwipeDetector.SwipeAction.TAP -> "tap"
                    SwipeDetector.SwipeAction.UP -> "up"
                    SwipeDetector.SwipeAction.LEFT -> "left"
                    SwipeDetector.SwipeAction.RIGHT -> "right"
                    else -> ""
                }
                repo.lastActionKeyId = keyId
                repo.lastActionSlot = slotStr
                repo.lastActionChar = charToType
            } else {
                repo.lastActionKeyId = null
                repo.lastActionSlot = null
                repo.lastActionChar = null
            }
            commitChar(charToType)
        }
    }

    private fun handleNumberModeDownSwipe(keySlots: KeySlots) {
        val prefs = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
        val isPremium = prefs.getBoolean("is_premium_user", false)
        
        if (isPremium) {
            val macroKey = "macro_${keySlots.tap}"
            val macroText = prefs.getString(macroKey, null)
            if (!macroText.isNullOrEmpty()) {
                commitChar(macroText)
            }
        } else {
            showPremiumUpsell()
        }
    }

    private fun showPremiumUpsell() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_PAGE", "premium.html")
        }
        startActivity(intent)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        
        if (isCommiting) return
        
        val ic = currentInputConnection ?: return
        val textBeforeCursor = ic.getTextBeforeCursor(50, 0) ?: ""
        
        val lastWord = textBeforeCursor.takeLastWhile { it != ' ' && it != '\n' }.toString()
        synchronized(typedText) {
            typedText.clear()
            typedText.append(lastWord)
        }
        
        if (::scoringEngine.isInitialized) {
            scoringEngine.resetTrieCache()
        }
        updatePredictions()
    }

    override fun onComputeInsets(outInsets: Insets) {
        try {
            super.onComputeInsets(outInsets)
            if (isFloatingMode) {
                val metrics = resources?.displayMetrics ?: return
                val screenHeight = metrics.heightPixels
                if (screenHeight <= 0) return

                outInsets.contentTopInsets = screenHeight
                outInsets.visibleTopInsets = screenHeight
                outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                
                val softWindow = window ?: return
                if (softWindow.window == null) return
                
                val root = keyboardRoot ?: return
                if (!root.isAttachedToWindow) return
                
                val loc = IntArray(2)
                root.getLocationInWindow(loc)
                outInsets.touchableRegion.setEmpty()
                val scaledWidth = (root.width * root.scaleX).toInt()
                val scaledHeight = (root.height * root.scaleY).toInt()
                outInsets.touchableRegion.set(loc[0], loc[1], loc[0] + scaledWidth, loc[1] + scaledHeight)
            }
        } catch (_: Exception) {
            Log.e(TAG, "Error in onComputeInsets")
        }
    }

    private fun commitChar(char: String) {
        playClick(if (char == " ") 32 else 0)
        
        // Smart Quote Normalization (P21 feature) (Task 7)
        val normalizedChar = char
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
            .replace('\u0060', '\'')

        val finalChar = languageManager.applyCase(normalizedChar)
        
        synchronized(typedText) {
            typedTextHistory.add(typedText.toString())
            typedText.append(finalChar)
        }

        if (finalChar == " ") {
            repo.lastActionKeyId = null
            repo.lastActionSlot = null
            repo.lastActionChar = null
            repo.stickyChar = null
        }

        if (languageManager.shiftState == LanguageManager.ShiftState.OFF) {
            // Re-render if shift state auto-reset from SHIFT_ONCE
            updateShiftButtonTint()
            refreshLayout()
        }

        val ic = currentInputConnection ?: return
        isCommiting = true
        ic.commitText(finalChar, 1)
        isCommiting = false

        refreshLayout()
        updatePredictions()
    }

    private fun handleDelete() {
        playClick(KeyEvent.KEYCODE_DEL)
        repo.lastActionKeyId = null
        repo.lastActionSlot = null
        repo.lastActionChar = null
        repo.stickyChar = null

        synchronized(typedText) {
            typedTextHistory.add(typedText.toString())
            if (typedText.isNotEmpty()) {
                typedText.deleteCharAt(typedText.length - 1)
                if (::scoringEngine.isInitialized) {
                    scoringEngine.resetTrieCache()
                }
            }
        }

        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)
        isCommiting = true
        if (selectedText.isNullOrEmpty()) {
            ic.deleteSurroundingText(1, 0)
        } else {
            ic.commitText("", 1)
        }
        isCommiting = false

        refreshLayout()
        updatePredictions()
    }
    
    private fun updateSendButtonIcon(info: EditorInfo?) {
        val btnSendIconView = keyboardRoot?.findViewById<ImageView>(R.id.btnSendIcon) ?: return
        val imeAction = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (imeAction == EditorInfo.IME_ACTION_SEARCH) {
            btnSendIconView.setImageResource(R.drawable.ic_search)
        } else {
            btnSendIconView.setImageResource(R.drawable.ic_send)
        }
    }

    private fun handleSend() {
        playClick(10)
        val ic = currentInputConnection ?: return
        val editorInfo = currentInputEditorInfo

        val imeAction = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE

        val isMultiline = (editorInfo?.inputType ?: 0) and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE != 0
        val noEnterAction = (editorInfo?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0

        if (imeAction != EditorInfo.IME_ACTION_NONE && imeAction != EditorInfo.IME_ACTION_UNSPECIFIED) {
            val handled = ic.performEditorAction(imeAction)
            if (!handled) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        } else if (isMultiline && !noEnterAction) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        } else {
            val handled = ic.performEditorAction(EditorInfo.IME_ACTION_DONE)
            if (!handled) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        }

        synchronized(typedText) {
            typedText.clear()
            typedTextHistory.clear()
        }
        if (::scoringEngine.isInitialized) {
            scoringEngine.resetTrieCache()
        }
        refreshLayout()
        updatePredictions()
    }

    private fun handleGlobeClick() {
        playClick(0)
        // Show system IME picker so user can switch to another keyboard
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showInputMethodPicker()
    }

    @Suppress("DEPRECATION")
    private fun switchToNextIME() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToNextInputMethod(false)
        } else {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            val token = window?.window?.attributes?.token
            if (token != null) {
                imm?.switchToNextInputMethod(token, false)
            } else {
                imm?.switchToNextInputMethod(null, false)
            }
        }
    }

    private fun usePrediction(textView: TextView) {
        playClick(0)
        val word = textView.text.toString()
        if (word.isEmpty()) return

        val ic = currentInputConnection ?: return

        repo.lastActionKeyId = null
        repo.lastActionSlot = null
        repo.lastActionChar = null
        repo.stickyChar = null

        synchronized(typedText) {
            typedTextHistory.add(typedText.toString())
            val currentLen = typedText.length
            if (currentLen > 0) {
                ic.deleteSurroundingText(currentLen, 0)
            }
            ic.commitText("$word ", 1)

            typedText.clear()
        }
        if (::scoringEngine.isInitialized) {
            scoringEngine.resetTrieCache()
        }
        refreshLayout()
        updatePredictions()
    }

    private fun handleUndo() {
        val lastState = synchronized(typedText) {
            if (typedTextHistory.isNotEmpty()) {
                typedTextHistory.removeAt(typedTextHistory.size - 1)
            } else {
                null
            }
        }
        if (lastState != null) {
            val ic = currentInputConnection ?: return
            val currentLen = synchronized(typedText) { typedText.length }
            if (currentLen > 0) {
                ic.deleteSurroundingText(currentLen, 0)
            }
            ic.commitText(lastState, 1)
            synchronized(typedText) {
                typedText.clear()
                typedText.append(lastState)
            }
            if (::scoringEngine.isInitialized) {
                scoringEngine.resetTrieCache()
            }
            refreshLayout()
            updatePredictions()
        }
    }

    private fun toggleAltMode() {
        languageManager.cycleShift()
        refreshLayout()
        updateShiftButtonTint()
    }

    // ══════════════════════════════════════════
    // Layout Refresh Pipeline
    // ══════════════════════════════════════════

    private fun refreshLayout() {
        if (!::scoringEngine.isInitialized || !::layoutManager.isInitialized) return
        if (!repo.isReady.value) {
            Log.w(TAG, "Repository not ready yet, using empty layout")
            return
        }

        if (isNumberMode) {
            val layout = if (symbolPageIndex == 0) repo.symbolPage1 else repo.symbolPage2
            keyboardView?.isAltMode = false
            keyboardView?.updateLayout(layout)
            return
        }

        val textSnapshot = synchronized(typedText) { typedText.toString() }

        // Calculate Sticky Char
        val lastChar = repo.lastActionChar
        if (lastChar != null && scoringEngine.isDoubleCharValid(textSnapshot, lastChar)) {
            repo.stickyChar = lastChar
        } else {
            repo.stickyChar = null
        }

        val scores = scoringEngine.calculateScores(textSnapshot)
        val layout = layoutManager.assignLayout(scores)

        val displayLayout = layout.mapValues { (_, slots) ->
            KeySlots(
                tap = languageManager.getDisplayCase(slots.tap),
                up = languageManager.getDisplayCase(slots.up),
                down = languageManager.getDisplayCase(slots.down),
                left = languageManager.getDisplayCase(slots.left),
                right = languageManager.getDisplayCase(slots.right)
            )
        }

        keyboardView?.isAltMode = false
        keyboardView?.updateLayout(displayLayout)
    }

    private fun applyDockedScale(scale: Float) {
        val density = resources.displayMetrics.density
        
        val baseKeyHeight = 65
        val scaledKeyHeightDp = (baseKeyHeight * scale).toInt()
        keyboardView?.setKeyHeight(scaledKeyHeightDp)
        keyboardView?.setFontScale(scale)
        
        // Scale bottom bar text sizes
        keyboardRoot?.findViewById<TextView>(R.id.btnSpaceText)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * scale)
        keyboardRoot?.findViewById<TextView>(R.id.btnSpaceDownText)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * scale)
        keyboardRoot?.findViewById<TextView>(R.id.btnPeriodText)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f * scale)
        keyboardRoot?.findViewById<TextView>(R.id.btnPeriodCommaText)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * scale)
        keyboardRoot?.findViewById<TextView>(R.id.btnNumbers)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * scale)
        keyboardRoot?.findViewById<TextView>(R.id.btnShiftText)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * scale)
        
        val keyHeightPx = (scaledKeyHeightDp * density).toInt()
        val gapPx = (6 * density).toInt()
        val totalGridHeight = keyHeightPx * 3 + gapPx * 2
        
        // Use exact scale for outer rows based on preset selection
        val minorScale = when {
            scale <= 1.05f -> 0.9f
            scale >= 1.4f -> 1.1f
            else -> 1.0f
        }
        
        val basePredictionHeight = 42
        predictionRow?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.height = (basePredictionHeight * minorScale * density).toInt() } }
        
        val baseBottomBarHeight = 50
        val bottomBar = keyboardRoot?.findViewById<View>(R.id.bottomBar)
        bottomBar?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.height = (baseBottomBarHeight * minorScale * density).toInt() } }
        
        val sideToolsView = keyboardRoot?.findViewById<View>(R.id.sideTools)
        sideToolsView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.height = totalGridHeight } }
        
        renderToolbar()
        
        // Force IME window to resize its background to wrap the new content height
        keyboardRoot?.let {
            it.requestLayout()
            (it.parent as? View)?.requestLayout()
        }
        
        val softWindow = window?.window
        if (softWindow != null && !isFloatingMode) {
            softWindow.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            updateFullscreenMode()
            updateInputViewShown()
        }
    }

    private fun updatePredictions() {
        if (isNumberMode) {
            predictionBar?.visibility = View.INVISIBLE
            return
        }

        val text = typedText.toString().trim()

        if (text.isEmpty()) {
            pred1?.text = ""
            pred2?.text = ""
            pred3?.text = ""
            predictionBar?.visibility = View.GONE
            return
        }

        val suggestions = getSimpleSuggestions(text)
        
        if (suggestions.isEmpty()) {
            pred1?.text = ""
            pred2?.text = ""
            pred3?.text = ""
            predictionBar?.visibility = View.GONE
        } else {
            pred1?.text = suggestions.getOrElse(0) { "" }
            pred2?.text = suggestions.getOrElse(1) { "" }
            pred3?.text = suggestions.getOrElse(2) { "" }
            predictionBar?.visibility = View.VISIBLE
            pred1?.visibility = View.VISIBLE
            pred2?.visibility = View.VISIBLE
            pred3?.visibility = View.VISIBLE
        }
    }

    private fun getSimpleSuggestions(text: String): List<String> {
        val results = mutableListOf<String>()

        val root = repo.trieDict ?: return results
        val lowerText = text.lowercase()
        var node = root
        for (c in lowerText) {
            node = node[c.toString()] ?: return results
        }

        val allResults = mutableListOf<Pair<String, Int>>()

        fun dfs(n: com.flowboard.ime.data.models.TrieNode, prefix: String, depth: Int) {
            if (allResults.size >= 100) return // Candidate pool
            if (depth > 12) return // Safety depth limit
            if (n.isEndOfWord) {
                allResults.add(prefix to n.frequency)
            }
            for (entry in n.children.entries) {
                val key = entry.key
                val child = entry.value
                dfs(child, prefix + key, depth + 1)
            }
        }

        dfs(node, lowerText, 0)

        val isAllCaps = text.length > 1 && text.all { it.isUpperCase() }
        val isFirstUpper = text.isNotEmpty() && text[0].isUpperCase()

        fun applyCasing(word: String): String {
            return when {
                isAllCaps -> word.uppercase()
                isFirstUpper -> word.replaceFirstChar { it.uppercase() }
                else -> word
            }
        }

        // Effective rank = (rank + 1) * 1.4^extraChars (smaller line index = more popular)
        return allResults.sortedBy { (word, rank) ->
            val extraChars = maxOf(0, word.length - lowerText.length)
            val lenPenalty = Math.pow(1.4, extraChars.toDouble())
            (rank + 1) * lenPenalty
        }
        .map { applyCasing(it.first) }
        .take(3)
    }

    // ══════════════════════════════════════════
    // Profile & Handedness
    // ══════════════════════════════════════════

    @Suppress("unused")
    fun switchToProfile(profilePath: String) {
        serviceScope.launch {
            val mode = if (profilePath.contains("chat")) ProfileManager.ProfileMode.CHAT else ProfileManager.ProfileMode.DEFAULT
            profileManager.switchProfile(mode)
            refreshLayout()
        }
    }

    fun setHandedness(isLeftHanded: Boolean) {
        mainArea?.let { area ->
            val sideToolsView = area.findViewById<View>(R.id.sideTools) ?: return
            val lp = sideToolsView.layoutParams as LinearLayout.LayoutParams
            
            area.removeView(sideToolsView)
            if (isLeftHanded) {
                area.addView(sideToolsView, 0)
                lp.marginStart = 0
                lp.marginEnd = (6 * resources.displayMetrics.density).toInt()
            } else {
                area.addView(sideToolsView)
                lp.marginStart = (6 * resources.displayMetrics.density).toInt()
                lp.marginEnd = 0
            }
            sideToolsView.layoutParams = lp
        }

        // Rectify padding on keyboardRoot to close tight against left/right display edges
        keyboardRoot?.let { root ->
            val topPadding = root.paddingTop
            val bottomPadding = root.paddingBottom
            val density = resources.displayMetrics.density
            root.setPadding((6 * density).toInt(), topPadding, (6 * density).toInt(), bottomPadding)
        }

        updateDeleteButtonPosition()
    }

    private fun updateDeleteButtonPosition() {
        val row = predictionRow as? LinearLayout ?: return
        val del = btnDelete ?: return

        val prefs = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
        val followSideTools = prefs.getBoolean("delete_btn_follow_side_tools", true)
        val fixedSide = prefs.getString("delete_btn_fixed_side", "right") ?: "right"

        val deleteOnLeft = if (followSideTools) {
            isDockedLeftHanded
        } else {
            fixedSide == "left"
        }

        row.removeView(del)
        val density = resources.displayMetrics.density
        val lp = del.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams((44 * density).toInt(), (36 * density).toInt())
        val margin = (8 * density).toInt()

        if (deleteOnLeft) {
            row.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            row.addView(del, 0)
            lp.marginStart = 0
            lp.marginEnd = margin
        } else {
            row.gravity = Gravity.CENTER_VERTICAL or Gravity.END
            row.addView(del)
            lp.marginStart = margin
            lp.marginEnd = 0
        }
        del.layoutParams = lp
    }

    private fun isEffectiveDarkMode(): Boolean {
        return isDarkModeOverride ?: isSystemDarkMode()
    }

    private fun playClick(keyCode: Int) {
        val prefs = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
        if (!prefs.getBoolean("sound_on_keypress", false)) return
        
        val am = getSystemService(AUDIO_SERVICE) as? AudioManager
        when (keyCode) {
            32 -> am?.playSoundEffect(AudioManager.FX_KEYPRESS_SPACEBAR)
            KeyEvent.KEYCODE_DEL -> am?.playSoundEffect(AudioManager.FX_KEYPRESS_DELETE)
            10, 66 -> am?.playSoundEffect(AudioManager.FX_KEYPRESS_RETURN)
            else -> am?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
        }
    }

    private fun applySettingsAndTheme(rootView: View, themedContext: Context) {
        val prefs = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
        
        // 1. Toggles (Number row, Suggestions)
        val showNumberRow = prefs.getBoolean("show_number_row", false)
        val showSuggestions = prefs.getBoolean("show_suggestions", true)
        
        val numberRow = rootView.findViewById<View>(R.id.numberRow)
        numberRow?.visibility = if (showNumberRow) View.VISIBLE else View.GONE
        
        predictionRow?.visibility = if (showSuggestions) View.VISIBLE else View.GONE
        
        // 2. Load theme colors
        val activeTheme = prefs.getString("active_theme", "Clean Minimal") ?: "Clean Minimal"
        val isSystemDark = isEffectiveDarkMode()
        val colors = ThemeManager.getThemeColors(themedContext, activeTheme, isSystemDark)
        
        // 3. Apply colors to views
        val keyBgTintList = ColorStateList.valueOf(colors.keyBackground)
        val textTapColor = colors.textTap
        val textSwipeColor = colors.textSwipe
        val accentTintList = ColorStateList.valueOf(colors.accent)
        val toolBgTintList = ColorStateList.valueOf(colors.toolBackground)
        
        if (isFloatingMode) {
            // Under floating mode, keep background border outline
        } else {
            keyboardRoot?.setBackgroundColor(colors.keyboardBackground)
        }
        
        // Prediction Bar buttons
        pred1?.backgroundTintList = keyBgTintList
        pred1?.setTextColor(textTapColor)
        pred2?.backgroundTintList = keyBgTintList
        pred2?.setTextColor(textTapColor)
        pred3?.backgroundTintList = keyBgTintList
        pred3?.setTextColor(textTapColor)
        
        btnDelete?.backgroundTintList = keyBgTintList
        btnDelete?.imageTintList = ColorStateList.valueOf(textTapColor)
        
        // Number row buttons tinting
        val numIds = listOf(R.id.num1, R.id.num2, R.id.num3, R.id.num4, R.id.num5, R.id.num6, R.id.num7, R.id.num8, R.id.num9, R.id.num0)
        for (id in numIds) {
            rootView.findViewById<TextView>(id)?.apply {
                setOnClickListener { 
                    playClick(0)
                    commitChar(this.text.toString()) 
                }
                backgroundTintList = keyBgTintList
                setTextColor(textTapColor)
            }
        }
        
        // Side tools bar background
        sideTools?.backgroundTintList = toolBgTintList
        
        // Bottom bar buttons
        btnNumbers?.backgroundTintList = keyBgTintList
        btnNumbers?.setTextColor(textTapColor)
        btnNumbers?.setOnClickListener {
            playClick(0)
            toggleNumberMode()
        }
        
        btnShift?.backgroundTintList = keyBgTintList
        btnShift?.setOnClickListener {
            playClick(0)
            if (isNumberMode) {
                symbolPageIndex = if (symbolPageIndex == 0) 1 else 0
                refreshLayout()
                updateShiftButtonTint()
            } else {
                toggleAltMode()
            }
        }
        updateShiftButtonTint() // handles the shift text color highlight
        
        btnGlobe?.backgroundTintList = keyBgTintList
        btnGlobe?.imageTintList = ColorStateList.valueOf(textTapColor)
        btnGlobe?.setOnClickListener {
            handleGlobeClick()
        }
        btnGlobe?.setOnLongClickListener {
            switchToNextIME()
            true
        }
        
        btnSpace?.backgroundTintList = keyBgTintList
        rootView.findViewById<TextView>(R.id.btnSpaceText)?.setTextColor(textSwipeColor)
        
        btnPeriod?.backgroundTintList = keyBgTintList
        rootView.findViewById<TextView>(R.id.btnPeriodText)?.setTextColor(textTapColor)
        rootView.findViewById<TextView>(R.id.btnPeriodCommaText)?.setTextColor(textSwipeColor)
        
        btnSend?.backgroundTintList = accentTintList
        rootView.findViewById<ImageView>(R.id.btnSendIcon)?.imageTintList = ColorStateList.valueOf(colors.sendText)
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun getSystemNavigationBarHeight(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun setupNavigationBarPadding(rootView: View) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val systemNavHeight = getSystemNavigationBarHeight()
            val bottomInset = if (!isFloatingMode) {
                maxOf(navInsets.bottom, systemNavHeight)
            } else 0

            keyboardRoot?.let { root ->
                val density = resources.displayMetrics.density
                val baseBottomPadding = if (isFloatingMode) (4 * density).toInt() else (8 * density).toInt()
                val currentLeftPadding = root.paddingLeft.takeIf { it > 0 } ?: (6 * density).toInt()
                val currentRightPadding = root.paddingRight.takeIf { it > 0 } ?: (6 * density).toInt()
                val currentTopPadding = root.paddingTop.takeIf { it > 0 } ?: (8 * density).toInt()
                root.setPadding(
                    currentLeftPadding,
                    currentTopPadding,
                    currentRightPadding,
                    baseBottomPadding + bottomInset
                )
            }
            insets
        }
        rootView.requestApplyInsets()
    }
}
