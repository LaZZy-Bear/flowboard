package com.flowboard.ime.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flowboard.ime.data.ClipboardItem
import com.flowboard.ime.data.EmojiCategory
import com.flowboard.ime.data.EmojiRepository
import com.flowboard.ime.ui.EmojiAdapter
import com.flowboard.ime.util.ThemeColors
import android.content.pm.PackageManager
import com.flowboard.ime.data.ClipboardManagerHelper
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.LayoutInflater
import android.widget.Toast
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
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
import com.flowboard.ime.engine.LiveLearningManager
import com.flowboard.ime.engine.ProfileManager
import com.flowboard.ime.engine.ScoringEngine
import com.flowboard.ime.engine.WordPredictionEngine
import com.flowboard.ime.ui.KeyboardView
import com.flowboard.ime.ui.SwipeDetector
import com.flowboard.ime.util.SoundHapticManager
import com.flowboard.ime.util.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    private lateinit var wordPredictionEngine: WordPredictionEngine

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val key = intent?.getStringExtra("setting_key")
            Log.d(TAG, "Settings or theme changed in memory: key=$key")

            when (key) {
                "active_theme" -> {
                    val themeName = intent.getStringExtra("setting_val_str") ?: "Clean Minimal"
                    isDarkModeOverride = when (themeName) {
                        "Dark" -> true
                        "Light" -> false
                        else -> null
                    }
                    val root = keyboardRoot ?: return
                    applySettingsAndTheme(root, getThemedContext())
                    keyboardView?.refreshTheme()
                    renderToolbar()
                    refreshLayout()
                }
                "sound_on_keypress" -> {
                    soundHapticManager.isSoundEnabled = intent.getBooleanExtra("setting_val_bool", true)
                }
                "vibration_on_keypress" -> {
                    soundHapticManager.isVibrationEnabled = intent.getBooleanExtra("setting_val_bool", true)
                }
                "show_suggestions" -> {
                    isShowSuggestions = intent.getBooleanExtra("setting_val_bool", true)
                    updatePredictions()
                }
                "docked_side_tools_left" -> {
                    isDockedLeftHanded = intent.getBooleanExtra("setting_val_bool", true)
                    setHandedness(if (isFloatingMode) isFloatingLeftHanded else isDockedLeftHanded)
                }
                "floating_side_tools_left" -> {
                    isFloatingLeftHanded = intent.getBooleanExtra("setting_val_bool", false)
                    setHandedness(if (isFloatingMode) isFloatingLeftHanded else isDockedLeftHanded)
                }
                "delete_btn_follow_side_tools", "delete_btn_fixed_side" -> {
                    updateDeleteButtonPosition()
                }
                "personalization_enabled" -> {
                    context?.let { ctx ->
                        liveLearningManager.loadProfile()
                        AssetLoader(ctx).updatePersonalizationState(ctx, repo)
                    }
                    if (::scoringEngine.isInitialized) {
                        scoringEngine.resetTrieCache()
                    }
                    val root = keyboardRoot
                    if (root != null) {
                        refreshLayout()
                        renderToolbar()
                        updatePredictions()
                    }
                }
                "clear_personalization" -> {
                    liveLearningManager.clearProfile()
                    context?.let { ctx ->
                        AssetLoader(ctx).updatePersonalizationState(ctx, repo)
                    }
                    if (::scoringEngine.isInitialized) {
                        scoringEngine.resetTrieCache()
                    }
                    val root = keyboardRoot
                    if (root != null) {
                        refreshLayout()
                        renderToolbar()
                        updatePredictions()
                    }
                }
                else -> {
                    if (key?.startsWith("shortcut_") == true) {
                        val num = intent.getIntExtra("shortcut_key_num", 0)
                        if (num in 1..9) {
                            shortcutLabels[num] = intent.getStringExtra("shortcut_label") ?: ""
                            shortcutTexts[num] = intent.getStringExtra("shortcut_text") ?: ""
                            if (isNumberMode) {
                                refreshLayout()
                            }
                        }
                    } else {
                        // Personalization / general reload
                        loadSettings()
                        context?.let { ctx ->
                            liveLearningManager.loadProfile()
                            AssetLoader(ctx).updatePersonalizationState(ctx, repo)
                        }
                        val root = keyboardRoot
                        if (root != null) {
                            applySettingsAndTheme(root, getThemedContext())
                            keyboardView?.refreshTheme()
                            refreshLayout()
                            renderToolbar()
                            updatePredictions()
                        }
                    }
                }
            }
        }
    }

    // ── Views ──
    private var keyboardView: KeyboardView? = null

    // Prediction Bar
    private var predictionRow: View? = null
    private var predictionBar: LinearLayout? = null
    private var notificationBar: LinearLayout? = null
    private var notificationText: TextView? = null
    private var notificationDismissRunnable: Runnable? = null
    private val notificationHandler = Handler(Looper.getMainLooper())
    private var pred1: TextView? = null
    private var pred2: TextView? = null
    private var pred3: TextView? = null
    private var btnDelete: ImageView? = null

    // Side Tools (Control Panel)
    enum class ToolbarAction { HANDEDNESS, THEME, FLOATING, CLIPBOARD, UNDO, RESIZE, TEXT_EDIT, VOICE, EMOJI, SETTINGS, MORE, DELETE }
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
        ToolbarAction.RESIZE,
        ToolbarAction.TEXT_EDIT,
        ToolbarAction.VOICE,
        ToolbarAction.EMOJI,
        ToolbarAction.SETTINGS
    )
    private var sideTools: LinearLayout? = null
    private var dragHandleArea: View? = null
    private var resizeHandleRight: View? = null
    private var clipboardPanel: View? = null
    private var clipboardContent: LinearLayout? = null
    private var textEditPanel: View? = null
    private var quickThemePanel: View? = null
    private var undoRedoPanel: View? = null
    private var voiceInputPanel: LinearLayout? = null
    private var voiceLiveText: TextView? = null
    private var voiceStatusText: TextView? = null
    private var btnVoiceMic: FrameLayout? = null
    private var ivVoiceMicIcon: ImageView? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningVoice = false
    private var isTextSelecting = false
    private var emojiPanel: LinearLayout? = null
    private var emojiRecyclerView: RecyclerView? = null
    private var emojiAdapter: EmojiAdapter? = null
    private var currentEmojiCategory: EmojiCategory = EmojiCategory.SMILEYS
    private lateinit var clipboardHelper: ClipboardManagerHelper
    private var quickPasteBar: View? = null
    private var quickPasteText: TextView? = null
    private var quickPasteDismiss: View? = null
    private var btnClearUnpinned: TextView? = null
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
    private var isDockedLeftHanded = true
    private var isFloatingLeftHanded = false
    private var isDarkModeOverride: Boolean? = null
    private var isFloatingMode = false
    private var floatingX = -1 // default offset from left in dp (-1 means auto-center)
    private var floatingY = 100 // default offset from bottom in dp
    private var currentFloatingScale: Float = 1f
    private var isMorePanelOpen = false
    private var morePanelPage = 0
    private val soundHapticManager by lazy { SoundHapticManager(this) }
    private var isShowSuggestions = true

    private val shortcutLabels = Array(10) { "" }
    private val shortcutTexts = Array(10) { "" }

    private fun loadSettings() {
        val prefs = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
        soundHapticManager.isSoundEnabled = prefs.getBoolean("sound_on_keypress", true)
        soundHapticManager.isVibrationEnabled = prefs.getBoolean("vibration_on_keypress", true)
        isShowSuggestions = prefs.getBoolean("show_suggestions", true)
        isDockedLeftHanded = prefs.getBoolean("docked_side_tools_left", true)
        isFloatingLeftHanded = prefs.getBoolean("floating_side_tools_left", false)
        isFloatingMode = prefs.getBoolean("is_floating_mode", false)
        floatingX = prefs.getInt("floating_x", -1)
        floatingY = prefs.getInt("floating_y", 100)
        currentFloatingScale = prefs.getFloat("floating_scale", 1f).coerceIn(0.88f, 1.20f)
        isDarkModeOverride = if (prefs.contains("dark_mode_override")) prefs.getBoolean("dark_mode_override", false) else null
        for (i in 1..9) {
            shortcutLabels[i] = prefs.getString("shortcut_label_$i", "")?.trim() ?: ""
            shortcutTexts[i] = prefs.getString("shortcut_text_$i", "")?.trim() ?: ""
        }
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
    private val typedTextRedoHistory = mutableListOf<String>()
    private var lastDragHandleClickTime = 0L
    private var isMinimized = false
    private var isCommiting = false
    private var lastLocalActionTime = 0L

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

    private val liveLearningManager by lazy { LiveLearningManager(this) }
    private var clipboardManager: ClipboardManager? = null
    private var primaryClipListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        scoringEngine = ScoringEngine(repo)
        layoutManager = LayoutManager(repo)
        languageManager = LanguageManager(repo)
        profileManager = ProfileManager(repo)
        wordPredictionEngine = WordPredictionEngine(repo)

        loadSettings()
        liveLearningManager.loadProfile()

        clipboardHelper = ClipboardManagerHelper(this)
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        primaryClipListener = ClipboardManager.OnPrimaryClipChangedListener {
            val clipMgr = clipboardManager ?: return@OnPrimaryClipChangedListener
            val primaryClip = clipMgr.primaryClip
            if (primaryClip != null && primaryClip.itemCount > 0) {
                // Privacy check: ignore sensitive clipboard items (e.g. passwords from password managers on API 33+)
                val desc = clipMgr.primaryClipDescription
                val isSensitive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    desc?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) ?: false
                } else false

                val clipText = primaryClip.getItemAt(0)?.coerceToText(this)?.toString()
                if (!clipText.isNullOrBlank()) {
                    if (!isSensitive) {
                        // Regular text: save to persistent clipboard history & show quick paste
                        val newItem = clipboardHelper.addClip(clipText)
                        if (newItem != null) {
                            showQuickPasteChip(newItem.text)
                            if (clipboardPanel?.visibility == View.VISIBLE) {
                                updateClipboardPanel()
                            }
                        }
                    } else {
                        // Sensitive text (e.g. Password/OTP): allow instant 1-tap paste via toolbar, but DO NOT save to persistent history
                        showQuickPasteChip(clipText)
                    }
                }
            }
        }
        primaryClipListener?.let { clipboardManager?.addPrimaryClipChangedListener(it) }

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
        loadSettings()
        
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
        notificationBar = rootView.findViewById(R.id.notificationBar)
        notificationText = rootView.findViewById(R.id.notificationText)
        quickPasteBar = rootView.findViewById(R.id.quickPasteBar)
        quickPasteText = rootView.findViewById(R.id.quickPasteText)
        quickPasteDismiss = rootView.findViewById<View>(R.id.quickPasteDismiss)?.apply {
            setOnClickListener {
                quickPasteBar?.visibility = View.GONE
                updateDeleteButtonPosition()
                updatePredictions()
            }
        }
        resizeHandleRight = rootView.findViewById<View>(R.id.resizeHandleRight).apply {
            setOnTouchListener { _, event -> handleResizeTouch(event) }
        }
        clipboardPanel = rootView.findViewById(R.id.clipboardPanel)
        clipboardContent = rootView.findViewById(R.id.clipboardContent)
        btnClearUnpinned = rootView.findViewById<TextView>(R.id.btnClearUnpinned)?.apply {
            setOnClickListener {
                clipboardHelper.clearUnpinned()
                updateClipboardPanel()
            }
        }
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
                                handler.postDelayed(this, 67)
                            }
                        }
                        deleteRunnable = r
                        handler.postDelayed(r, 260)
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
            onKeyAction = { action, keySlots, keyIndex ->
                handleKeyAction(action, keySlots, keyIndex)
            }
        }

        val morePanel = rootView.findViewById<View>(R.id.morePanel)
        if (isMorePanelOpen) {
            keyboardView?.visibility = View.GONE
            morePanel?.visibility = View.VISIBLE
            rootView.post {
                keyboardView?.let { kv ->
                    val lp = morePanel?.layoutParams
                    if (lp != null) {
                        val kvHeight = kv.height
                        lp.height = if (kvHeight > 0) kvHeight else (220 * resources.displayMetrics.density).toInt()
                        morePanel.layoutParams = lp
                    }
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
                    SwipeDetector.SwipeAction.DOWN -> {
                        soundHapticManager.playSwipe()
                        commitChar(" ", playAudio = false)
                    }
                    else -> commitChar("0")
                }
            } else {
                when (action) {
                    SwipeDetector.SwipeAction.DOWN -> {
                        soundHapticManager.playSwipe()
                        commitChar("0", playAudio = false)
                    }
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

        // Period with swipe-up detector (TAP -> ".", UP -> Emoji Panel)
        val periodSwipeDetector = SwipeDetector(thresholdPx = 25f * resources.displayMetrics.density) { action ->
            when (action) {
                SwipeDetector.SwipeAction.TAP -> commitChar(".")
                SwipeDetector.SwipeAction.UP -> {
                    soundHapticManager.playSwipe()
                    showSubPanel(emojiPanel)
                }
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
        if (!isFloatingMode) {
            applyDockedScale(currentScale)
        }

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
            soundHapticManager.playTap()
            currentScale = 1.0f
            prefs.edit { putFloat("docked_keyboard_scale", currentScale) }
            applyDockedScale(currentScale)
            updateActiveButton()
        }
        
        btnSizeMedium?.setOnClickListener {
            soundHapticManager.playTap()
            currentScale = 1.2f
            prefs.edit { putFloat("docked_keyboard_scale", currentScale) }
            applyDockedScale(currentScale)
            updateActiveButton()
        }
        
        btnSizeLarge?.setOnClickListener {
            soundHapticManager.playTap()
            currentScale = 1.5f
            prefs.edit { putFloat("docked_keyboard_scale", currentScale) }
            applyDockedScale(currentScale)
            updateActiveButton()
        }

        btnCloseHeightAdjust?.setOnClickListener {
            soundHapticManager.playTap()
            heightAdjustLayout?.visibility = View.GONE
            keyboardView?.visibility = View.VISIBLE
            renderToolbar()
        }

        // Text Editing Panel Setup
        textEditPanel = rootView.findViewById(R.id.textEditPanel)
        textEditPanel?.let { panel ->
            val btnArrowUp = panel.findViewById<ImageView>(R.id.btnTextArrowUp)
            val btnArrowDown = panel.findViewById<ImageView>(R.id.btnTextArrowDown)
            val btnArrowLeft = panel.findViewById<ImageView>(R.id.btnTextArrowLeft)
            val btnArrowRight = panel.findViewById<ImageView>(R.id.btnTextArrowRight)
            val btnJumpStart = panel.findViewById<ImageView>(R.id.btnTextJumpStart)
            val btnJumpEnd = panel.findViewById<ImageView>(R.id.btnTextJumpEnd)
            val btnSelect = panel.findViewById<TextView>(R.id.btnTextSelect)
            val btnSelectAll = panel.findViewById<TextView>(R.id.btnTextSelectAll)
            val btnCut = panel.findViewById<TextView>(R.id.btnTextCut)
            val btnCopy = panel.findViewById<TextView>(R.id.btnTextCopy)
            val btnPaste = panel.findViewById<TextView>(R.id.btnTextPaste)
            val btnDelete = panel.findViewById<TextView>(R.id.btnTextDelete)

            @Suppress("SetTextI18n")
            fun updateSelectButtonState() {
                val p = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
                val activeTheme = p.getString("active_theme", "Clean Minimal") ?: "Clean Minimal"
                val colors = ThemeManager.getThemeColors(this@FlowboardIMEService, activeTheme, isEffectiveDarkMode())
                if (isTextSelecting) {
                    btnSelect?.backgroundTintList = ColorStateList.valueOf(colors.accent)
                    btnSelect?.setTextColor(colors.sendText)
                    btnSelect?.text = "Selecting"
                } else {
                    btnSelect?.backgroundTintList = ColorStateList.valueOf(colors.keyBackground)
                    btnSelect?.setTextColor(colors.textTap)
                    btnSelect?.text = "Select"
                }
            }

            btnSelect?.setOnClickListener {
                soundHapticManager.playTap()
                isTextSelecting = !isTextSelecting
                updateSelectButtonState()
            }

            btnSelectAll?.setOnClickListener {
                soundHapticManager.playTap()
                currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
            }

            btnCut?.setOnClickListener {
                soundHapticManager.playTap()
                currentInputConnection?.performContextMenuAction(android.R.id.cut)
            }

            btnCopy?.setOnClickListener {
                soundHapticManager.playTap()
                currentInputConnection?.performContextMenuAction(android.R.id.copy)
            }

            btnPaste?.setOnClickListener {
                soundHapticManager.playTap()
                currentInputConnection?.performContextMenuAction(android.R.id.paste)
            }

            btnDelete?.apply {
                setOnClickListener {
                    soundHapticManager.playTap()
                    handleDelete()
                }
                var deleteRunnable: Runnable? = null
                val handler = Handler(Looper.getMainLooper())
                @SuppressLint("ClickableViewAccessibility")
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.isPressed = true
                            soundHapticManager.playTap()
                            handleDelete()
                            val r = object : Runnable {
                                override fun run() {
                                    handleDelete()
                                    handler.postDelayed(this, 67)
                                }
                            }
                            deleteRunnable = r
                            handler.postDelayed(r, 260)
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

            btnArrowUp?.setOnClickListener {
                sendDpadKey(KeyEvent.KEYCODE_DPAD_UP, isTextSelecting)
            }

            btnArrowDown?.setOnClickListener {
                sendDpadKey(KeyEvent.KEYCODE_DPAD_DOWN, isTextSelecting)
            }

            btnArrowLeft?.setOnClickListener {
                sendDpadKey(KeyEvent.KEYCODE_DPAD_LEFT, isTextSelecting)
            }

            btnArrowRight?.setOnClickListener {
                sendDpadKey(KeyEvent.KEYCODE_DPAD_RIGHT, isTextSelecting)
            }

            btnJumpStart?.setOnClickListener {
                sendDpadKey(KeyEvent.KEYCODE_MOVE_HOME, isTextSelecting)
            }

            btnJumpEnd?.setOnClickListener {
                sendDpadKey(KeyEvent.KEYCODE_MOVE_END, isTextSelecting)
            }
        }

        // Quick Theme Panel Setup
        quickThemePanel = rootView.findViewById(R.id.quickThemePanel)
        quickThemePanel?.let { panel ->
            val btnThemeLight = panel.findViewById<TextView>(R.id.btnThemeLight)
            val btnThemeDark = panel.findViewById<TextView>(R.id.btnThemeDark)
            val btnThemeSystem = panel.findViewById<TextView>(R.id.btnThemeSystem)
            val pOcean = panel.findViewById<FrameLayout>(R.id.paletteOcean)
            val pTeal = panel.findViewById<FrameLayout>(R.id.paletteTeal)
            val pCoral = panel.findViewById<FrameLayout>(R.id.paletteCoral)
            val pSakura = panel.findViewById<FrameLayout>(R.id.paletteSakura)
            val btnOpenFullThemes = panel.findViewById<TextView>(R.id.btnOpenFullThemes)

            pOcean?.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor("#1565C0".toColorInt())
            }
            pTeal?.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor("#00C853".toColorInt())
            }
            pCoral?.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor("#FBBC05".toColorInt())
            }
            pSakura?.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor("#EC4899".toColorInt())
            }

            fun applyThemeDirect(themeName: String, darkOverride: Boolean? = null) {
                soundHapticManager.playTap()
                isDarkModeOverride = darkOverride
                getSharedPreferences("flowboard_settings", MODE_PRIVATE).edit {
                    putString("active_theme", themeName)
                    if (darkOverride != null) {
                        putBoolean("dark_mode_override", darkOverride)
                    } else {
                        remove("dark_mode_override")
                    }
                }
                keyboardRoot?.let { applySettingsAndTheme(it, getThemedContext()) }
                keyboardView?.refreshTheme()
                refreshLayout()
                renderToolbar()
            }

            btnThemeLight?.setOnClickListener { applyThemeDirect("Light", false) }
            btnThemeDark?.setOnClickListener { applyThemeDirect("Dark", true) }
            btnThemeSystem?.setOnClickListener { applyThemeDirect("System default", null) }

            pOcean?.setOnClickListener { applyThemeDirect("Ocean Blue", false) }
            pTeal?.setOnClickListener { applyThemeDirect("Mint Teal", false) }
            pCoral?.setOnClickListener { applyThemeDirect("Sunset Coral", false) }
            pSakura?.setOnClickListener { applyThemeDirect("Sakura Bloom", false) }

            btnOpenFullThemes?.setOnClickListener {
                soundHapticManager.playTap()
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("OPEN_PAGE", "themes")
                }
                startActivity(intent)
            }
        }

        // Undo & Redo Panel Setup
        undoRedoPanel = rootView.findViewById(R.id.undoRedoPanel)
        undoRedoPanel?.let { panel ->
            val cardUndo = panel.findViewById<View>(R.id.cardUndo)
            val cardRedo = panel.findViewById<View>(R.id.cardRedo)
            val cardClearAll = panel.findViewById<View>(R.id.cardClearAll)

            cardUndo?.setOnClickListener { handleUndo() }
            cardRedo?.setOnClickListener { handleRedo() }
            cardClearAll?.setOnClickListener { handleClearAll() }
        }

        // Voice Input Panel Setup
        voiceInputPanel = rootView.findViewById(R.id.voiceInputPanel)
        voiceLiveText = rootView.findViewById(R.id.voiceLiveText)
        voiceStatusText = rootView.findViewById(R.id.voiceStatusText)
        btnVoiceMic = rootView.findViewById(R.id.btnVoiceMic)
        ivVoiceMicIcon = rootView.findViewById(R.id.ivVoiceMicIcon)

        btnVoiceMic?.setOnClickListener {
            if (isListeningVoice) {
                stopVoiceRecognition()
            } else {
                startVoiceRecognition()
            }
        }

        // Emoji Panel Setup
        emojiPanel = rootView.findViewById(R.id.emojiPanel)
        emojiRecyclerView = rootView.findViewById(R.id.emojiRecyclerView)

        val gridColumns = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 10 else 7
        emojiRecyclerView?.layoutManager = GridLayoutManager(this, gridColumns)
        val initialScale = if (isFloatingMode) currentFloatingScale else 1f
        emojiAdapter = EmojiAdapter(
            emojis = EmojiRepository.getEmojisForCategory(this, EmojiCategory.SMILEYS),
            emojiSizeSp = if (isFloatingMode) 19f * initialScale else 23f
        ) { emoji ->
            playClick(0)
            currentInputConnection?.commitText(emoji, 1)
            EmojiRepository.addRecentEmoji(this, emoji)
            typedTextRedoHistory.clear()
        }
        emojiRecyclerView?.adapter = emojiAdapter

        setupEmojiCategoryTabs(rootView)

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

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (!restarting) {
            val previousText = synchronized(typedText) { typedText.toString() }
            if (previousText.isNotEmpty() && isLearningAllowedForCurrentField()) {
                liveLearningManager.recordWordTyped(previousText)
                liveLearningManager.saveProfileIfDirty()
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView (restarting=$restarting)")

        closeSubPanelsToKeyboard()

        if (::scoringEngine.isInitialized) {
            scoringEngine.resetTrieCache()
        }
        
        updateSendButtonIcon(info)
        if (isFloatingMode) {
            updateFloatingWindowMode()
        }

        // Reset typing state for a new input field
        repo.lastActionKeyId = null
        repo.lastActionSlot = null
        repo.lastActionChar = null
        repo.stickyChar = null

        synchronized(typedText) {
            val previousText = typedText.toString()
            if (previousText.isNotEmpty() && isLearningAllowedForCurrentField()) {
                liveLearningManager.recordWordTyped(previousText)
                liveLearningManager.saveProfileIfDirty()
            }
            typedText.clear()
            typedTextHistory.clear()
            val existing = try {
                currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: ""
            } catch (_: Exception) { "" }
            if (existing.isNotEmpty()) {
                typedText.append(existing)
            }
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

    private fun isCurrentInputPasswordField(): Boolean {
        val info = currentInputEditorInfo ?: return false
        val inputType = info.inputType
        val variation = inputType and EditorInfo.TYPE_MASK_VARIATION
        return variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                (inputType and EditorInfo.TYPE_MASK_CLASS == EditorInfo.TYPE_CLASS_NUMBER && variation == 0x00000010)
    }

    private fun isLearningAllowedForCurrentField(): Boolean {
        val info = currentInputEditorInfo
        if (info != null) {
            val noLearning = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
            } else false
            if (noLearning) {
                return false
            }
        }
        if (isCurrentInputPasswordField() && !liveLearningManager.isLearnPasswordsEnabled()) {
            return false
        }
        return true
    }

    private fun isWordDelimiter(ch: String): Boolean {
        if (ch.isEmpty()) return false
        val c = ch[0]
        return !c.isLetterOrDigit() && c != '\'' && c != '@' && c != '.' && c != '-'
    }

    private var lastRecordedSessionText: String? = null

    override fun onFinishInput() {
        super.onFinishInput()
        Log.d(TAG, "onFinishInput")
        lastRecordedSessionText = null
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Log.d(TAG, "onFinishInputView (finishingInput=$finishingInput)")
        closeSubPanelsToKeyboard()
        val currentText = getFullTextBeforeCursor()
        if (currentText.isNotEmpty() && currentText != lastRecordedSessionText && isLearningAllowedForCurrentField()) {
            lastRecordedSessionText = currentText
            liveLearningManager.recordWordTyped(currentText)
            liveLearningManager.saveProfileIfDirty()
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        Log.d(TAG, "onWindowHidden")
        closeSubPanelsToKeyboard()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        stopVoiceRecognition()
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {}
        try {
            primaryClipListener?.let { clipboardManager?.removePrimaryClipChangedListener(it) }
        } catch (_: Exception) {}
        primaryClipListener = null
        clipboardManager = null
        liveLearningManager.saveProfileIfDirty()
        soundHapticManager.release()
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
                ToolbarAction.TEXT_EDIT -> R.drawable.ic_text_edit
                ToolbarAction.VOICE -> R.drawable.ic_mic
                ToolbarAction.EMOJI -> R.drawable.ic_emoji
                ToolbarAction.SETTINGS -> R.drawable.ic_settings
                ToolbarAction.DELETE -> R.drawable.ic_backspace
                ToolbarAction.MORE -> if (isAnySubPanelOpen()) android.R.drawable.ic_menu_close_clear_cancel else R.drawable.ic_more
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
                                        handler.postDelayed(this, 67)
                                    }
                                }
                                deleteRunnable = r
                                handler.postDelayed(r, 260)
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
                                try {
                                    val actionStr = event.clipData?.getItemAt(0)?.text?.toString()
                                    if (actionStr != null) {
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
                                    }
                                } catch (_: Exception) {}
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

    private fun updateEmojiCategoryHighlight(selectedCat: EmojiCategory) {
        val root = keyboardRoot ?: return
        val catMap = listOf(
            R.id.btnEmojiCatRecent to EmojiCategory.RECENT,
            R.id.btnEmojiCatSmileys to EmojiCategory.SMILEYS,
            R.id.btnEmojiCatPeople to EmojiCategory.PEOPLE,
            R.id.btnEmojiCatAnimals to EmojiCategory.ANIMALS,
            R.id.btnEmojiCatFood to EmojiCategory.FOOD,
            R.id.btnEmojiCatTravel to EmojiCategory.TRAVEL,
            R.id.btnEmojiCatActivities to EmojiCategory.ACTIVITIES,
            R.id.btnEmojiCatObjects to EmojiCategory.OBJECTS,
            R.id.btnEmojiCatFlags to EmojiCategory.FLAGS
        )

        val density = resources.displayMetrics.density
        val p = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
        val activeTheme = p.getString("active_theme", "Clean Minimal") ?: "Clean Minimal"
        val colors = ThemeManager.getThemeColors(this, activeTheme, isEffectiveDarkMode())

        val scrollContainer = root.findViewById<HorizontalScrollView>(R.id.emojiCategoryScroll)
        scrollContainer?.background = null

        val divider = root.findViewById<View>(R.id.emojiCategoryDivider)
        divider?.setBackgroundColor(if (colors.isDark) 0x1AFFFFFF else 0x1A000000)

        for ((viewId, cat) in catMap) {
            val tab = root.findViewById<TextView>(viewId) ?: continue
            if (cat == selectedCat) {
                tab.alpha = 1.0f
                val activeBg = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 17 * density
                    setColor(colors.keyBackground)
                }
                tab.background = activeBg
                if (scrollContainer != null) {
                    tab.post {
                        val scrollX = tab.left - (scrollContainer.width - tab.width) / 2
                        scrollContainer.smoothScrollTo(maxOf(0, scrollX), 0)
                    }
                }
            } else {
                tab.alpha = 0.45f
                tab.background = null
            }
        }
    }

    private fun setupEmojiCategoryTabs(rootView: View) {
        val catMap = listOf(
            R.id.btnEmojiCatRecent to EmojiCategory.RECENT,
            R.id.btnEmojiCatSmileys to EmojiCategory.SMILEYS,
            R.id.btnEmojiCatPeople to EmojiCategory.PEOPLE,
            R.id.btnEmojiCatAnimals to EmojiCategory.ANIMALS,
            R.id.btnEmojiCatFood to EmojiCategory.FOOD,
            R.id.btnEmojiCatTravel to EmojiCategory.TRAVEL,
            R.id.btnEmojiCatActivities to EmojiCategory.ACTIVITIES,
            R.id.btnEmojiCatObjects to EmojiCategory.OBJECTS,
            R.id.btnEmojiCatFlags to EmojiCategory.FLAGS
        )

        fun selectCategory(cat: EmojiCategory) {
            currentEmojiCategory = cat
            val emojis = EmojiRepository.getEmojisForCategory(this, cat)
            emojiAdapter?.updateEmojis(emojis)
            emojiRecyclerView?.scrollToPosition(0)
            updateEmojiCategoryHighlight(cat)
        }

        for ((viewId, cat) in catMap) {
            rootView.findViewById<TextView>(viewId)?.setOnClickListener {
                playClick(0)
                selectCategory(cat)
            }
        }

        updateEmojiCategoryHighlight(currentEmojiCategory)
    }

    private fun isAnySubPanelOpen(): Boolean {
        val morePanel = keyboardRoot?.findViewById<View>(R.id.morePanel)
        return isMorePanelOpen ||
                (morePanel?.isVisible == true) ||
                (clipboardPanel?.isVisible == true) ||
                (textEditPanel?.isVisible == true) ||
                (quickThemePanel?.isVisible == true) ||
                (undoRedoPanel?.isVisible == true) ||
                (voiceInputPanel?.isVisible == true) ||
                (emojiPanel?.isVisible == true) ||
                (heightAdjustLayout?.isVisible == true)
    }

    private fun hideAllSubPanels() {
        keyboardRoot?.findViewById<View>(R.id.morePanel)?.visibility = View.GONE
        clipboardPanel?.visibility = View.GONE
        textEditPanel?.visibility = View.GONE
        quickThemePanel?.visibility = View.GONE
        undoRedoPanel?.visibility = View.GONE
        voiceInputPanel?.visibility = View.GONE
        emojiPanel?.visibility = View.GONE
        heightAdjustLayout?.visibility = View.GONE
        isMorePanelOpen = false
        morePanelPage = 0
    }

    private fun showSubPanel(panel: View?) {
        if (panel == null) return
        val kv = keyboardView
        val scale = if (isFloatingMode) currentFloatingScale else 1f
        val density = resources.displayMetrics.density
        val targetHeight = if (kv != null && kv.height > 0) {
            kv.height
        } else {
            ((75 * scale * 3 + 12) * density).toInt()
        }

        hideAllSubPanels()
        kv?.visibility = View.GONE

        val p = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
        val activeTheme = p.getString("active_theme", "Clean Minimal") ?: "Clean Minimal"
        val colors = ThemeManager.getThemeColors(this, activeTheme, isEffectiveDarkMode())
        keyboardRoot?.let { applyPanelsTheme(it, colors) }

        if (panel == textEditPanel) {
            isTextSelecting = false
            val btnSelect = panel.findViewById<TextView>(R.id.btnTextSelect)
            btnSelect?.backgroundTintList = ColorStateList.valueOf(colors.keyBackground)
            btnSelect?.setTextColor(colors.textTap)
            btnSelect?.setText(R.string.select)
        } else if (panel == emojiPanel) {
            val emojis = EmojiRepository.getEmojisForCategory(this, currentEmojiCategory)
            emojiAdapter?.updateEmojis(emojis)
            emojiAdapter?.setEmojiSize(if (isFloatingMode) 19f * scale else 23f)
            updateEmojiCategoryHighlight(currentEmojiCategory)
        }

        val lp = panel.layoutParams as? LinearLayout.LayoutParams
        if (lp != null) {
            lp.height = targetHeight
            lp.width = 0
            lp.weight = if (isFloatingMode) 8.6f else 1f
            panel.layoutParams = lp
        }
        panel.visibility = View.VISIBLE
        if (panel.id == R.id.morePanel) {
            isMorePanelOpen = true
        }
        renderToolbar()
    }

    private fun closeSubPanelsToKeyboard() {
        stopVoiceRecognition()
        isTextSelecting = false
        hideAllSubPanels()
        keyboardView?.visibility = View.VISIBLE
        if (isFloatingMode) {
            val scale = currentFloatingScale
            keyboardView?.setKeyHeight((75 * scale).toInt())
            keyboardView?.setFontScale(scale)
        }
        renderToolbar()
    }

    private fun sendDpadKey(keyCode: Int, isShift: Boolean) {
        soundHapticManager.playTap()
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        if (isShift) {
            val meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD))
        } else {
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD)
            ic.sendKeyEvent(down)
            ic.sendKeyEvent(up)
        }
    }

    @Suppress("SetTextI18n")
    private fun startVoiceRecognition() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission required for Voice typing", Toast.LENGTH_LONG).show()
            try {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("REQUEST_AUDIO_PERMISSION", true)
                }
                startActivity(intent)
            } catch (_: Exception) {}
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Voice recognition service not available on this device", Toast.LENGTH_SHORT).show()
            return
        }

        showSubPanel(voiceInputPanel)
        voiceLiveText?.text = "Listening... Speak now"
        voiceStatusText?.text = "Listening..."
        ivVoiceMicIcon?.imageTintList = ColorStateList.valueOf("#E74C3C".toColorInt())
        btnVoiceMic?.setBackgroundColor("#33E74C3C".toColorInt())

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        voiceStatusText?.text = "Listening... Speak now"
                        updateVoiceMicTheme()
                    }

                    override fun onBeginningOfSpeech() {
                        voiceStatusText?.text = "Listening..."
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        voiceStatusText?.text = "Processing speech..."
                    }

                    override fun onError(error: Int) {
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Tap mic to retry."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout. Tap mic to speak."
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network connection error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                            else -> "Voice recognition stopped. Tap mic to retry."
                        }
                        voiceStatusText?.text = msg
                        isListeningVoice = false
                        updateVoiceMicTheme()
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()
                        if (!text.isNullOrEmpty()) {
                            voiceLiveText?.text = text
                            currentInputConnection?.commitText("$text ", 1)
                            typedTextRedoHistory.clear()
                        }
                        voiceStatusText?.text = "Tap mic to speak again"
                        isListeningVoice = false
                        updateVoiceMicTheme()
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()
                        if (!text.isNullOrEmpty()) {
                            voiceLiveText?.text = text
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            speechRecognizer?.startListening(intent)
            isListeningVoice = true
            updateVoiceMicTheme()
        } catch (e: Exception) {
            voiceStatusText?.text = "Error starting voice recognition: ${e.message}"
            isListeningVoice = false
            updateVoiceMicTheme()
        }
    }

    private fun updateVoiceMicTheme() {
        val p = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
        val activeTheme = p.getString("active_theme", "Clean Minimal") ?: "Clean Minimal"
        val colors = ThemeManager.getThemeColors(this, activeTheme, isEffectiveDarkMode())
        if (isListeningVoice) {
            btnVoiceMic?.backgroundTintList = ColorStateList.valueOf(colors.accent)
            ivVoiceMicIcon?.imageTintList = ColorStateList.valueOf(colors.sendText)
        } else {
            btnVoiceMic?.backgroundTintList = ColorStateList.valueOf(colors.keyBackground)
            ivVoiceMicIcon?.imageTintList = ColorStateList.valueOf(colors.textTap)
        }
    }

    @Suppress("SetTextI18n")
    private fun stopVoiceRecognition() {
        if (isListeningVoice) {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
            isListeningVoice = false
            voiceStatusText?.text = "Tap mic to start"
            updateVoiceMicTheme()
        }
    }

    private fun executeToolbarAction(action: ToolbarAction) {
        soundHapticManager.playTap()
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
                if (quickThemePanel?.visibility == View.VISIBLE) {
                    closeSubPanelsToKeyboard()
                } else {
                    showSubPanel(quickThemePanel)
                }
            }
            ToolbarAction.FLOATING -> {
                closeSubPanelsToKeyboard()
                isFloatingMode = !isFloatingMode
                getSharedPreferences("flowboard_settings", MODE_PRIVATE).edit {
                    putBoolean("is_floating_mode", isFloatingMode)
                }
                updateFloatingWindowMode()
                closeSubPanelsToKeyboard()
            }
            ToolbarAction.CLIPBOARD -> {
                if (clipboardPanel?.visibility == View.VISIBLE) {
                    closeSubPanelsToKeyboard()
                } else {
                    showSubPanel(clipboardPanel)
                    updateClipboardPanel()
                }
            }
            ToolbarAction.UNDO -> {
                if (undoRedoPanel?.visibility == View.VISIBLE) {
                    closeSubPanelsToKeyboard()
                } else {
                    showSubPanel(undoRedoPanel)
                }
            }
            ToolbarAction.RESIZE -> {
                if (!isFloatingMode) {
                    val isCurrentlyVisible = heightAdjustLayout?.visibility == View.VISIBLE
                    hideAllSubPanels()
                    keyboardView?.visibility = View.VISIBLE
                    if (!isCurrentlyVisible) {
                        heightAdjustLayout?.visibility = View.VISIBLE
                    }
                    renderToolbar()
                }
            }
            ToolbarAction.TEXT_EDIT -> {
                if (textEditPanel?.visibility == View.VISIBLE) {
                    closeSubPanelsToKeyboard()
                } else {
                    showSubPanel(textEditPanel)
                }
            }
            ToolbarAction.VOICE -> {
                if (voiceInputPanel?.visibility == View.VISIBLE) {
                    closeSubPanelsToKeyboard()
                } else {
                    startVoiceRecognition()
                }
            }
            ToolbarAction.EMOJI -> {
                if (emojiPanel?.visibility == View.VISIBLE) {
                    closeSubPanelsToKeyboard()
                } else {
                    showSubPanel(emojiPanel)
                }
            }
            ToolbarAction.SETTINGS -> {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("OPEN_PAGE", "settings")
                }
                startActivity(intent)
            }
            ToolbarAction.DELETE -> {
                handleDelete()
            }
            ToolbarAction.MORE -> {
                if (isAnySubPanelOpen()) {
                    closeSubPanelsToKeyboard()
                    return
                }

                isMorePanelOpen = true
                morePanelPage = 0
                val morePanel = keyboardRoot?.findViewById<View>(R.id.morePanel)
                showSubPanel(morePanel)
                renderMorePanel()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun renderMorePanel() {
        val grid = keyboardRoot?.findViewById<GridLayout>(R.id.morePanelGrid) ?: return
        grid.removeAllViews()

        val context = getThemedContext()
        val density = resources.displayMetrics.density
        val p = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
        val activeTheme = p.getString("active_theme", "Clean Minimal") ?: "Clean Minimal"
        val colors = ThemeManager.getThemeColors(context, activeTheme, isEffectiveDarkMode())

        val morePageDot1 = keyboardRoot?.findViewById<View>(R.id.morePageDot1)
        val morePageDot2 = keyboardRoot?.findViewById<View>(R.id.morePageDot2)
        val btnMorePage1 = keyboardRoot?.findViewById<View>(R.id.btnMorePage1)
        val btnMorePage2 = keyboardRoot?.findViewById<View>(R.id.btnMorePage2)

        // 9 items per page (3x3)
        val pageSize = 9
        val pageActions = if (morePanelPage == 0) {
            allActions.take(pageSize)
        } else {
            allActions.drop(pageSize)
        }

        // Update Dots
        val activeColor = colors.accent
        val inactiveColor = Color.argb(80, Color.red(colors.textTap), Color.green(colors.textTap), Color.blue(colors.textTap))

        if (morePanelPage == 0) {
            morePageDot1?.backgroundTintList = ColorStateList.valueOf(activeColor)
            morePageDot2?.backgroundTintList = ColorStateList.valueOf(inactiveColor)
        } else {
            morePageDot1?.backgroundTintList = ColorStateList.valueOf(inactiveColor)
            morePageDot2?.backgroundTintList = ColorStateList.valueOf(activeColor)
        }

        btnMorePage1?.setOnClickListener {
            if (morePanelPage != 0) {
                soundHapticManager.playSwipe()
                morePanelPage = 0
                renderMorePanel()
            }
        }

        btnMorePage2?.setOnClickListener {
            if (morePanelPage != 1) {
                soundHapticManager.playSwipe()
                morePanelPage = 1
                renderMorePanel()
            }
        }

        val moreSwipeDetector = SwipeDetector(thresholdPx = 25f * density) { action ->
            when (action) {
                SwipeDetector.SwipeAction.LEFT -> {
                    if (morePanelPage == 0) {
                        soundHapticManager.playSwipe()
                        morePanelPage = 1
                        renderMorePanel()
                    }
                }
                SwipeDetector.SwipeAction.RIGHT -> {
                    if (morePanelPage == 1) {
                        soundHapticManager.playSwipe()
                        morePanelPage = 0
                        renderMorePanel()
                    }
                }
                else -> {}
            }
        }
        keyboardRoot?.findViewById<View>(R.id.morePanel)?.setOnTouchListener { v, event ->
            moreSwipeDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                v.performClick()
            }
            true
        }

        for (action in pageActions) {
            val itemLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val padding = (6 * density).toInt()
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
                ToolbarAction.TEXT_EDIT -> R.drawable.ic_text_edit
                ToolbarAction.VOICE -> R.drawable.ic_mic
                ToolbarAction.EMOJI -> R.drawable.ic_emoji
                ToolbarAction.SETTINGS -> R.drawable.ic_settings
                ToolbarAction.DELETE -> R.drawable.ic_backspace
                ToolbarAction.MORE -> R.drawable.ic_more
            }

            val iv = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (30 * density).toInt(),
                    (30 * density).toInt()
                )
                setImageResource(iconRes)
                setColorFilter(colors.textTap)
            }

            val tv = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (2 * density).toInt()
                }
                @Suppress("SetTextI18n")
                text = when (action) {
                    ToolbarAction.HANDEDNESS -> "Switch Hand"
                    ToolbarAction.THEME -> "Theme"
                    ToolbarAction.FLOATING -> if (isFloatingMode) "Dock" else "Floating"
                    ToolbarAction.CLIPBOARD -> "Clipboard"
                    ToolbarAction.UNDO -> "Undo/Redo"
                    ToolbarAction.RESIZE -> "Resize"
                    ToolbarAction.TEXT_EDIT -> "Text Edit"
                    ToolbarAction.VOICE -> "Voice"
                    ToolbarAction.EMOJI -> "Emoji"
                    ToolbarAction.SETTINGS -> "Settings"
                    else -> ""
                }
                textSize = 11f
                setTextColor(colors.textTap)
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
            grid.addView(itemLayout)
        }

        // Fill remaining slots on page with dummy invisible cells to maintain consistent 3x3 layout dimensions
        val dummyCount = pageSize - pageActions.size
        repeat(dummyCount) {
            val dummy = View(context).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
            }
            grid.addView(dummy)
        }
    }

    private fun showQuickPasteChip(text: String) {
        val qpBar = quickPasteBar ?: return
        val predBar = predictionBar ?: return
        val txt = quickPasteText ?: return

        txt.text = text
        predBar.visibility = View.GONE
        qpBar.visibility = View.VISIBLE

        qpBar.setOnClickListener {
            currentInputConnection?.commitText(text, 1)
            qpBar.visibility = View.GONE
            updateDeleteButtonPosition()
            updatePredictions()
        }
    }

    @Suppress("SetTextI18n")
    private fun updateClipboardPanel() {
        val container = clipboardContent ?: return
        container.removeAllViews()

        val items = clipboardHelper.getItems()
        val ctx = getThemedContext()
        val p = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
        val activeTheme = p.getString("active_theme", "Clean Minimal") ?: "Clean Minimal"
        val colors = ThemeManager.getThemeColors(ctx, activeTheme, isEffectiveDarkMode())

        if (items.isEmpty()) {
            val emptyTv = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (32 * resources.displayMetrics.density).toInt()
                }
                text = "Clipboard is empty"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(colors.textSwipe)
            }
            container.addView(emptyTv)
            return
        }

        val pinnedItems = items.filter { it.isPinned }
        val recentItems = items.filter { !it.isPinned }

        val density = resources.displayMetrics.density

        if (pinnedItems.isNotEmpty()) {
            val pinnedHeader = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                }
                text = "Pinned"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colors.textTap)
            }
            container.addView(pinnedHeader)

            for (clip in pinnedItems) {
                container.addView(createClipboardItemCard(clip, colors))
            }
        }

        if (recentItems.isNotEmpty()) {
            val recentHeader = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins((8 * density).toInt(), if (pinnedItems.isNotEmpty()) (12 * density).toInt() else (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                }
                text = "Recent"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colors.textTap)
            }
            container.addView(recentHeader)

            for (clip in recentItems) {
                container.addView(createClipboardItemCard(clip, colors))
            }
        }
    }

    private fun createClipboardItemCard(clip: ClipboardItem, colors: ThemeColors = ThemeManager.getThemeColors(getThemedContext(), getSharedPreferences("flowboard_settings", MODE_PRIVATE).getString("active_theme", "Clean Minimal") ?: "Clean Minimal", isEffectiveDarkMode())): View {
        val ctx = getThemedContext()
        val density = resources.displayMetrics.density

        val card = LinearLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins((2 * density).toInt(), (3 * density).toInt(), (2 * density).toInt(), (3 * density).toInt())
            }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.prediction_key_bg)
            backgroundTintList = ColorStateList.valueOf(colors.keyBackground)
            setPadding((12 * density).toInt(), (8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt())
        }

        val textTv = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            text = clip.text
            textSize = 14f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(colors.textTap)
        }

        val pinBtn = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt())
            setImageResource(if (clip.isPinned) R.drawable.ic_pin else R.drawable.ic_pin_outline)
            setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
            setColorFilter(if (clip.isPinned) colors.accent else colors.textSwipe)
            contentDescription = if (clip.isPinned) "Unpin" else "Pin"
            setOnClickListener {
                clipboardHelper.togglePin(clip.id)
                updateClipboardPanel()
            }
        }

        val delBtn = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt()).apply {
                marginStart = (2 * density).toInt()
            }
            setImageResource(R.drawable.ic_backspace)
            setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
            setColorFilter(colors.textSwipe)
            contentDescription = "Delete"
            setOnClickListener {
                clipboardHelper.deleteItem(clip.id)
                updateClipboardPanel()
            }
        }

        card.addView(textTv)
        card.addView(pinBtn)
        card.addView(delBtn)

        card.setOnClickListener {
            currentInputConnection?.commitText(clip.text, 1)
            clipboardPanel?.visibility = View.GONE
            keyboardView?.visibility = View.VISIBLE
            renderToolbar()
        }

        return card
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
        closeSubPanelsToKeyboard()
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
            predictionRowView?.visibility = View.VISIBLE
            btnDelete?.visibility = View.GONE
            floatingControlBar?.visibility = View.VISIBLE
            dragHandleArea?.visibility = View.VISIBLE
            resizeHandleRight?.visibility = View.VISIBLE
            setupDragHandle()
            updatePredictions()

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
            val maxX = maxOf(0, screenWidth - kbWidth)
            
            kbView?.setKeyHeight((75 * savedScale).toInt())
            kbView?.setFontScale(savedScale)
            
            // Set proportional weights for floating mode BEFORE measuring
            btnNumbersView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.weight = 1.5f; it.layoutParams = lp } }
            btnShiftView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.weight = 1.2f; it.layoutParams = lp } }
            btnGlobeView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.weight = 1.2f; it.layoutParams = lp } }
            btnSpaceView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.weight = 3.3f; it.layoutParams = lp } }
            btnPeriodView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.weight = 1.2f; it.layoutParams = lp } }
            btnSendView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.weight = 1.6f; it.layoutParams = lp } }
            
            sideToolsView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.height = ViewGroup.LayoutParams.MATCH_PARENT; lp.weight = 1.4f; it.layoutParams = lp } }
            
            val allFloatingPanels = listOfNotNull(
                kbView,
                morePanelView,
                clipboardPanelView,
                root.findViewById<View>(R.id.textEditPanel),
                root.findViewById<View>(R.id.quickThemePanel),
                root.findViewById<View>(R.id.undoRedoPanel),
                voiceInputPanel,
                emojiPanel
            )
            for (p in allFloatingPanels) {
                (p.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.width = 0
                    lp.weight = 8.6f
                    p.layoutParams = lp
                }
            }

            root.findViewById<View>(R.id.bottomBar)?.requestLayout()
            root.findViewById<View>(R.id.mainArea)?.requestLayout()
            root.requestLayout()

            root.measure(
                View.MeasureSpec.makeMeasureSpec(kbWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val kbHeight = root.measuredHeight
            val maxY = maxOf(0, screenHeight - kbHeight - (20 * density).toInt())
            
            val targetX = if (floatingX >= 0) (floatingX * density).toInt() else (screenWidth - kbWidth) / 2
            lp.x = maxOf(0, minOf(targetX, maxX))
            lp.y = maxOf(0, minOf((floatingY * density).toInt(), maxY))

            if (floatingX < 0) {
                floatingX = (lp.x / density).toInt()
            }
            
            root.scaleX = 1f
            root.scaleY = 1f
        } else {
            val prefs = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
            predictionRowView?.visibility = View.VISIBLE
            btnDelete?.visibility = View.VISIBLE
            floatingControlBar?.visibility = View.GONE
            updatePredictions()
            
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
            btnNumbersView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = (46 * density).toInt(); lp.weight = 0f; it.layoutParams = lp } }
            btnShiftView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = (38 * density).toInt(); lp.weight = 0f; it.layoutParams = lp } }
            btnGlobeView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = (38 * density).toInt(); lp.weight = 0f; it.layoutParams = lp } }
            btnSpaceView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = 0; lp.weight = 1f; it.layoutParams = lp } }
            btnPeriodView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = (38 * density).toInt(); lp.weight = 0f; it.layoutParams = lp } }
            btnSendView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = (50 * density).toInt(); lp.weight = 0f; it.layoutParams = lp } }
            
            sideToolsView?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.let { lp -> lp.width = (42 * density).toInt(); lp.height = ViewGroup.LayoutParams.MATCH_PARENT; lp.weight = 0f; it.layoutParams = lp } }
            
            val allDockedPanels = listOfNotNull(
                kbView,
                morePanelView,
                clipboardPanelView,
                root.findViewById<View>(R.id.textEditPanel),
                root.findViewById<View>(R.id.quickThemePanel),
                root.findViewById<View>(R.id.undoRedoPanel),
                voiceInputPanel,
                emojiPanel
            )
            for (p in allDockedPanels) {
                (p.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.width = 0
                    lp.weight = 1f
                    p.layoutParams = lp
                }
            }

            root.findViewById<View>(R.id.bottomBar)?.requestLayout()
            root.findViewById<View>(R.id.mainArea)?.requestLayout()
            root.requestLayout()

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
                    val maxX = maxOf(0, screenWidth - width)
                    val maxY = maxOf(0, screenHeight - kbHeight - (20 * metrics.density).toInt())

                    lp.x = maxOf(0, minOf(initialX + dx, maxX))
                    lp.y = maxOf(0, minOf(initialY - dy, maxY))

                    val density = if (metrics.density > 0) metrics.density else 1.0f
                    floatingX = (lp.x / density).toInt()
                    floatingY = (lp.y / density).toInt()

                    win.attributes = lp
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    getSharedPreferences("flowboard_settings", MODE_PRIVATE).edit {
                        putInt("floating_x", floatingX)
                        putInt("floating_y", floatingY)
                    }
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

                // Dynamically adjust height of any currently open subpanel
                val subPanelHeight = (3 * (75 * scale * metrics.density).toInt() + (12 * metrics.density).toInt())
                val openSubPanel = listOfNotNull(
                    root.findViewById<View>(R.id.morePanel),
                    clipboardPanel,
                    textEditPanel,
                    quickThemePanel,
                    undoRedoPanel,
                    voiceInputPanel,
                    emojiPanel
                ).firstOrNull { it.isVisible }

                openSubPanel?.let { p ->
                    (p.layoutParams as? LinearLayout.LayoutParams)?.let { pLp ->
                        pLp.height = subPanelHeight
                        p.layoutParams = pLp
                    }
                }

                renderToolbar()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val scale = (lp.width / baseWidth).coerceIn(0.88f, 1.20f)
                currentFloatingScale = scale
                val maxX = maxOf(0, metrics.widthPixels - lp.width)
                lp.x = maxOf(0, minOf(lp.x, maxX))
                win.attributes = lp
                val density = if (metrics.density > 0) metrics.density else 1.0f
                floatingX = (lp.x / density).toInt()
                getSharedPreferences("flowboard_settings", MODE_PRIVATE).edit {
                    putFloat("floating_scale", scale)
                    putInt("floating_x", floatingX)
                    putInt("floating_y", floatingY)
                }

                val kbView = root.findViewById<KeyboardView>(R.id.keyboardView)
                kbView?.setKeyHeight((75 * scale).toInt())
                kbView?.setFontScale(scale)
                renderToolbar()
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
        
        if (isNumberMode) {
            predictionBar?.visibility = View.GONE
            predictionRow?.visibility = View.VISIBLE
        } else {
            predictionRow?.visibility = View.VISIBLE
            updatePredictions()
        }
        
        updateSpaceLabelForMode()
        refreshLayout()
        updateShiftButtonTint()
    }

    // ══════════════════════════════════════════
    // Input Handling
    // ══════════════════════════════════════════

    private fun handleKeyAction(action: SwipeDetector.SwipeAction, keySlots: KeySlots, keyIndex: Int) {
        if (isNumberMode && action == SwipeDetector.SwipeAction.DOWN) {
            handleNumberModeDownSwipe(keyIndex)
            return
        }

        val keyId = "key_$keyIndex"
        val charToType = when (action) {
            SwipeDetector.SwipeAction.TAP -> keySlots.tap
            SwipeDetector.SwipeAction.UP -> keySlots.up
            SwipeDetector.SwipeAction.DOWN -> keySlots.down
            SwipeDetector.SwipeAction.LEFT -> keySlots.left
            SwipeDetector.SwipeAction.RIGHT -> keySlots.right
        }

        if (charToType.isNotEmpty()) {
            if (action == SwipeDetector.SwipeAction.TAP) {
                soundHapticManager.playTap()
            } else {
                soundHapticManager.playSwipe()
            }

            if (action != SwipeDetector.SwipeAction.DOWN) {
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
            commitChar(charToType, playAudio = false)
        }
    }

    private fun showPredictionNotification(message: String, durationMs: Long = 2000L) {
        notificationDismissRunnable?.let { notificationHandler.removeCallbacks(it) }

        notificationText?.text = message
        notificationBar?.visibility = View.VISIBLE
        quickPasteBar?.visibility = View.GONE
        predictionBar?.visibility = View.GONE

        predictionRow?.visibility = View.VISIBLE

        notificationDismissRunnable = Runnable {
            notificationBar?.visibility = View.GONE
            if (isNumberMode) {
                predictionBar?.visibility = View.GONE
                predictionRow?.visibility = View.VISIBLE
            } else {
                predictionRow?.visibility = View.VISIBLE
                updatePredictions()
            }
        }
        notificationHandler.postDelayed(notificationDismissRunnable!!, durationMs)
    }

    private fun handleNumberModeDownSwipe(keyIndex: Int) {
        soundHapticManager.playSwipe()
        val shortcutText = shortcutTexts.getOrNull(keyIndex)
        if (!shortcutText.isNullOrEmpty()) {
            lastLocalActionTime = SystemClock.uptimeMillis()
            synchronized(typedText) {
                typedTextHistory.add(typedText.toString())
                typedTextRedoHistory.clear()
                typedText.append(shortcutText)
            }
            val ic = currentInputConnection
            if (ic != null) {
                isCommiting = true
                ic.commitText(shortcutText, 1)
                isCommiting = false
            }
            repo.lastActionKeyId = null
            repo.lastActionSlot = null
            repo.lastActionChar = null
            repo.stickyChar = null
        } else {
            showPredictionNotification("No shortcut assigned to Key $keyIndex")
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        
        if (isCommiting) return

        // Fast path: if selection change was from local keyboard action in last 350ms
        if (SystemClock.uptimeMillis() - lastLocalActionTime < 350) {
            return
        }

        // Fast path: 1-char cursor move
        if ((newSelStart == oldSelStart + 1 && newSelEnd == oldSelEnd + 1 && oldSelStart == oldSelEnd) ||
            (newSelStart == oldSelStart - 1 && newSelEnd == oldSelEnd - 1 && oldSelStart == oldSelEnd)) {
            return
        }
        
        val ic = currentInputConnection ?: return
        val textBeforeCursor = try {
            ic.getTextBeforeCursor(64, 0)?.toString() ?: ""
        } catch (_: Exception) { "" }
        
        val currentLocal = synchronized(typedText) { typedText.toString() }
        
        // Only synchronize if cursor moved externally or text differs
        if (textBeforeCursor != currentLocal) {
            if (currentLocal.isNotEmpty() && isLearningAllowedForCurrentField()) {
                liveLearningManager.recordWordTyped(currentLocal)
                liveLearningManager.saveProfileIfDirty()
            }
            synchronized(typedText) {
                typedText.clear()
                typedText.append(textBeforeCursor)
            }
            
            if (::scoringEngine.isInitialized) {
                scoringEngine.resetTrieCache()
            }
            refreshLayout()
            updatePredictions()
        }
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

    private fun getFullTextBeforeCursor(appendChar: String = ""): String {
        val local = synchronized(typedText) { typedText.toString() }
        if (local.isNotEmpty()) {
            return local + appendChar
        }
        val ic = currentInputConnection
        val before = try {
            ic?.getTextBeforeCursor(64, 0)?.toString() ?: ""
        } catch (_: Exception) { "" }
        return before + appendChar
    }

    private fun commitChar(char: String, playAudio: Boolean = true) {
        lastLocalActionTime = SystemClock.uptimeMillis()
        if (playAudio) {
            if (char == " ") {
                soundHapticManager.playSpace()
            } else {
                soundHapticManager.playTap()
            }
        }
        
        // Smart Quote Normalization (P21 feature) (Task 7)
        val normalizedChar = char
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
            .replace('\u0060', '\'')

        val finalChar = languageManager.applyCase(normalizedChar)
        
        synchronized(typedText) {
            typedTextHistory.add(typedText.toString())
            typedTextRedoHistory.clear()
            typedText.append(finalChar)
        }

        val fullText = getFullTextBeforeCursor()

        val isDelimiter = finalChar == " " || isWordDelimiter(finalChar)
        if (isDelimiter && isLearningAllowedForCurrentField()) {
            repo.lastActionKeyId = null
            repo.lastActionSlot = null
            repo.lastActionChar = null
            repo.stickyChar = null
            liveLearningManager.recordWordTyped(fullText)
            liveLearningManager.saveProfileIfDirty()
            if (::scoringEngine.isInitialized) {
                scoringEngine.resetTrieCache()
            }
        }

        if (languageManager.shiftState == LanguageManager.ShiftState.OFF) {
            // Re-render if shift state auto-reset from SHIFT_ONCE
            updateShiftButtonTint()
        }

        val ic = currentInputConnection
        if (ic != null) {
            isCommiting = true
            ic.commitText(finalChar, 1)
            isCommiting = false
        }

        refreshLayout()
        updatePredictions()
    }

    private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

    private fun handleDelete() {
        lastLocalActionTime = SystemClock.uptimeMillis()
        playClick(KeyEvent.KEYCODE_DEL)
        repo.lastActionKeyId = null
        repo.lastActionSlot = null
        repo.lastActionChar = null
        repo.stickyChar = null

        val ic = currentInputConnection ?: return

        // 1. Check if user has selected / highlighted text (คลุมดำ)
        val selectedText = try {
            ic.getSelectedText(0)
        } catch (_: Exception) {
            null
        }

        if (!selectedText.isNullOrEmpty()) {
            synchronized(typedText) {
                typedTextHistory.add(typedText.toString())
                typedTextRedoHistory.clear()
                typedText.clear()
                if (::scoringEngine.isInitialized) {
                    scoringEngine.resetTrieCache()
                }
            }
            isCommiting = true
            ic.commitText("", 1)
            isCommiting = false
            refreshLayout()
            updatePredictions()
            return
        }

        // 2. Normal delete when no text is selected
        var deleteCount = 1
        synchronized(typedText) {
            typedTextHistory.add(typedText.toString())
            typedTextRedoHistory.clear()
            if (typedText.isNotEmpty()) {
                val len = typedText.length
                var i = len
                val cp = Character.codePointBefore(typedText, i)
                val charCount = Character.charCount(cp)
                var count = charCount
                i -= charCount

                while (i > 0) {
                    val prevCp = Character.codePointBefore(typedText, i)
                    val prevCharCount = Character.charCount(prevCp)
                    if (cp == 0xFE0F || cp == 0xFE0E || (cp in 0x1F3FB..0x1F3FF) || cp == 0x20E3 || prevCp == 0x200D) {
                        count += prevCharCount
                        i -= prevCharCount
                        if (prevCp == 0x200D && i > 0) {
                            val beforeZwjCp = Character.codePointBefore(typedText, i)
                            val beforeZwjCount = Character.charCount(beforeZwjCp)
                            count += beforeZwjCount
                            i -= beforeZwjCount
                        }
                    } else if (isRegionalIndicator(cp) && isRegionalIndicator(prevCp)) {
                        count += prevCharCount
                        break
                    } else {
                        break
                    }
                }

                typedText.delete(typedText.length - minOf(count, typedText.length), typedText.length)
                deleteCount = count
                if (::scoringEngine.isInitialized) {
                    scoringEngine.resetTrieCache()
                }
            }
        }

        isCommiting = true
        ic.deleteSurroundingText(deleteCount, 0)
        isCommiting = false

        refreshLayout()
        updatePredictions()
    }
    
    private fun isEnterActionApplicable(info: EditorInfo?): Boolean {
        if (info == null) return false
        val inputType = info.inputType
        val imeOptions = info.imeOptions
        val imeAction = imeOptions and EditorInfo.IME_MASK_ACTION
        val isMultiline = (inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

        // If NO_ENTER_ACTION flag is set, always perform Newline (not action)
        if (noEnterAction) return false

        // In multiline fields (Gemini, ChatGPT, Notes, Google Keep, doc editors):
        // Only explicit SEARCH, GO, or SEND (without NO_ENTER_ACTION) are treated as Action.
        // DONE, NONE, UNSPECIFIED in multiline text fields MUST be treated as Return/Newline!
        if (isMultiline) {
            return imeAction == EditorInfo.IME_ACTION_SEARCH ||
                   imeAction == EditorInfo.IME_ACTION_GO ||
                   imeAction == EditorInfo.IME_ACTION_SEND
        }

        // In single-line fields:
        // Any explicit action (SEARCH, GO, SEND, NEXT, DONE) is treated as Action.
        return imeAction != EditorInfo.IME_ACTION_NONE &&
               imeAction != EditorInfo.IME_ACTION_UNSPECIFIED
    }

    private fun updateSendButtonIcon(info: EditorInfo?) {
        val btnSendIconView = keyboardRoot?.findViewById<ImageView>(R.id.btnSendIcon) ?: return
        if (info == null) {
            btnSendIconView.setImageResource(R.drawable.ic_return)
            return
        }

        val isAction = isEnterActionApplicable(info)
        if (!isAction) {
            // Multiline / Standard Newline -> Show Return (↵) icon
            btnSendIconView.setImageResource(R.drawable.ic_return)
            return
        }

        val imeAction = info.imeOptions and EditorInfo.IME_MASK_ACTION
        when (imeAction) {
            EditorInfo.IME_ACTION_SEARCH -> btnSendIconView.setImageResource(R.drawable.ic_search)
            EditorInfo.IME_ACTION_SEND -> btnSendIconView.setImageResource(R.drawable.ic_send)
            EditorInfo.IME_ACTION_GO -> btnSendIconView.setImageResource(R.drawable.ic_arrow_right)
            EditorInfo.IME_ACTION_NEXT -> btnSendIconView.setImageResource(R.drawable.ic_arrow_right)
            EditorInfo.IME_ACTION_DONE -> btnSendIconView.setImageResource(R.drawable.ic_check)
            else -> btnSendIconView.setImageResource(R.drawable.ic_return)
        }
    }

    private fun handleSend() {
        playClick(10)
        val ic = currentInputConnection ?: return
        val editorInfo = currentInputEditorInfo

        val isAction = isEnterActionApplicable(editorInfo)
        val imeAction = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE

        if (isAction && imeAction != EditorInfo.IME_ACTION_NONE && imeAction != EditorInfo.IME_ACTION_UNSPECIFIED) {
            val handled = ic.performEditorAction(imeAction)
            if (!handled) {
                sendNewline(ic)
            }
        } else {
            // Return / Newline (e.g. Gemini, Notes, multiline fields, standard enter)
            sendNewline(ic)
        }

        val currentText = getFullTextBeforeCursor()
        if (currentText.isNotEmpty() && isLearningAllowedForCurrentField()) {
            liveLearningManager.recordWordTyped(currentText)
            liveLearningManager.saveProfileIfDirty()
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

    private fun sendNewline(ic: InputConnection) {
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
        val sentDown = ic.sendKeyEvent(down)
        val sentUp = ic.sendKeyEvent(up)
        if (!sentDown || !sentUp) {
            ic.commitText("\n", 1)
        }
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
        lastLocalActionTime = SystemClock.uptimeMillis()
        playClick(0)
        val word = textView.text.toString()
        if (word.isEmpty()) return

        val ic = currentInputConnection ?: return

        repo.lastActionKeyId = null
        repo.lastActionSlot = null
        repo.lastActionChar = null
        repo.stickyChar = null

        val fullTextBefore = getFullTextBeforeCursor()
        val activePrefix = if (::wordPredictionEngine.isInitialized) {
            wordPredictionEngine.getActivePrefix(fullTextBefore)
        } else {
            ""
        }
        val charsToDelete = activePrefix.length

        synchronized(typedText) {
            typedTextHistory.add(typedText.toString())
            typedTextRedoHistory.clear()
            if (charsToDelete > 0) {
                ic.deleteSurroundingText(charsToDelete, 0)
                if (typedText.length >= charsToDelete) {
                    typedText.delete(typedText.length - charsToDelete, typedText.length)
                }
            }
            ic.commitText("$word ", 1)
            typedText.append("$word ")

            val fullTextAfter = getFullTextBeforeCursor()
            if (isLearningAllowedForCurrentField()) {
                liveLearningManager.recordWordTyped(fullTextAfter)
                liveLearningManager.saveProfileIfDirty()
            }
        }
        if (::scoringEngine.isInitialized) {
            scoringEngine.resetTrieCache()
        }
        refreshLayout()
        updatePredictions()
    }

    private fun handleUndo() {
        soundHapticManager.playTap()
        val ic = currentInputConnection ?: return

        // 1. Try system ContextMenu Action for Undo
        val handled = try {
            ic.performContextMenuAction(android.R.id.undo)
        } catch (_: Exception) {
            false
        }

        // 2. Dispatch Ctrl+Z KeyEvent to target editor
        if (!handled) {
            val now = SystemClock.uptimeMillis()
            val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, meta))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, meta))
        }

        synchronized(typedText) {
            typedText.clear()
        }
        if (::scoringEngine.isInitialized) {
            scoringEngine.resetTrieCache()
        }
        refreshLayout()
        updatePredictions()
    }

    private fun handleRedo() {
        soundHapticManager.playTap()
        val ic = currentInputConnection ?: return

        // 1. Try system ContextMenu Action for Redo
        val handled = try {
            ic.performContextMenuAction(android.R.id.redo)
        } catch (_: Exception) {
            false
        }

        // 2. Dispatch Ctrl+Y and Ctrl+Shift+Z KeyEvents to target editor
        if (!handled) {
            val now = SystemClock.uptimeMillis()
            val metaCtrlY = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Y, 0, metaCtrlY))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Y, 0, metaCtrlY))

            val metaCtrlShiftZ = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, metaCtrlShiftZ))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, metaCtrlShiftZ))
        }

        synchronized(typedText) {
            typedText.clear()
        }
        if (::scoringEngine.isInitialized) {
            scoringEngine.resetTrieCache()
        }
        refreshLayout()
        updatePredictions()
    }

    private fun handleClearAll() {
        soundHapticManager.playTap()
        val ic = currentInputConnection ?: return
        synchronized(typedText) {
            typedTextHistory.add(typedText.toString())
            typedTextRedoHistory.clear()
            typedText.clear()
        }
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText("", 1)
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0, 0))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0, 0))
        if (::scoringEngine.isInitialized) {
            scoringEngine.resetTrieCache()
        }
        refreshLayout()
        updatePredictions()
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
            val baseLayout = if (symbolPageIndex == 0) repo.symbolPage1 else repo.symbolPage2
            val numberLayout = baseLayout.mapValues { (keyId, slots) ->
                val keyNum = keyId.removePrefix("key_").toIntOrNull() ?: 1
                val customLabel = shortcutLabels.getOrElse(keyNum) { "" }
                val downSymbol = if (customLabel.isNotEmpty()) customLabel else "⚡"
                slots.copy(down = downSymbol)
            }
            keyboardView?.isAltMode = false
            keyboardView?.updateLayout(numberLayout)
            return
        }

        val textSnapshot = getFullTextBeforeCursor()

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
        keyboardRoot?.findViewById<ImageView>(R.id.btnPeriodEmojiIcon)?.let { icon ->
            val iconSize = (14 * scale * density).toInt()
            (icon.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.width = iconSize
                lp.height = iconSize
                icon.layoutParams = lp
            }
        }
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
        if (!isShowSuggestions || (isCurrentInputPasswordField() && !liveLearningManager.isLearnPasswordsEnabled())) {
            pred1?.text = ""
            pred2?.text = ""
            pred3?.text = ""
            predictionBar?.visibility = View.GONE
            return
        }

        if (quickPasteBar?.visibility == View.VISIBLE) {
            val text = typedText.toString().trim()
            if (text.isNotEmpty()) {
                quickPasteBar?.visibility = View.GONE
                updateDeleteButtonPosition()
            } else {
                predictionBar?.visibility = View.GONE
                return
            }
        }

        if (isNumberMode || !::wordPredictionEngine.isInitialized) {
            predictionBar?.visibility = View.INVISIBLE
            return
        }

        val fullText = getFullTextBeforeCursor()
        val suggestions = wordPredictionEngine.getPredictions(fullText, 3)
        
        if (suggestions.isEmpty()) {
            pred1?.text = ""
            pred2?.text = ""
            pred3?.text = ""
            predictionBar?.visibility = View.GONE
        } else {
            pred1?.text = suggestions.getOrElse(0) { "" }
            pred2?.text = suggestions.getOrElse(1) { "" }
            pred3?.text = suggestions.getOrElse(2) { "" }

            pred1?.visibility = if (suggestions.size > 0) View.VISIBLE else View.GONE
            pred2?.visibility = if (suggestions.size > 1) View.VISIBLE else View.GONE
            pred3?.visibility = if (suggestions.size > 2) View.VISIBLE else View.GONE

            predictionBar?.visibility = View.VISIBLE
        }
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
        val followSideTools = prefs.getBoolean("delete_btn_follow_side_tools", false)
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
        when (keyCode) {
            32 -> soundHapticManager.playSpace()
            KeyEvent.KEYCODE_DEL -> soundHapticManager.playDelete()
            10, 66 -> soundHapticManager.playReturn()
            else -> soundHapticManager.playTap()
        }
    }

    private fun applySettingsAndTheme(rootView: View, themedContext: Context) {
        val prefs = getSharedPreferences("flowboard_settings", MODE_PRIVATE)
        
        // 1. Toggles (Number row, Suggestions)
        val showNumberRow = prefs.getBoolean("show_number_row", false)
        isShowSuggestions = prefs.getBoolean("show_suggestions", true)
        
        val numberRow = rootView.findViewById<View>(R.id.numberRow)
        numberRow?.visibility = if (showNumberRow) View.VISIBLE else View.GONE
        
        predictionRow?.visibility = View.VISIBLE
        updatePredictions()
        
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

        notificationBar?.backgroundTintList = keyBgTintList
        notificationText?.setTextColor(textTapColor)
        
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
        rootView.findViewById<ImageView>(R.id.btnPeriodEmojiIcon)?.imageTintList = ColorStateList.valueOf(textSwipeColor)
        
        btnSend?.backgroundTintList = accentTintList
        rootView.findViewById<ImageView>(R.id.btnSendIcon)?.imageTintList = ColorStateList.valueOf(colors.sendText)

        applyPanelsTheme(rootView, colors)

        updateEmojiCategoryHighlight(currentEmojiCategory)
        keyboardView?.refreshTheme()
    }

    private fun applyPanelsTheme(rootView: View, colors: ThemeColors) {
        val kbBgTintList = ColorStateList.valueOf(colors.keyboardBackground)
        val keyBgTintList = ColorStateList.valueOf(colors.keyBackground)
        val toolBgTintList = ColorStateList.valueOf(colors.toolBackground)
        val accentTintList = ColorStateList.valueOf(colors.accent)
        val textTapColor = colors.textTap
        val textSwipeColor = colors.textSwipe
        val textTapTintList = ColorStateList.valueOf(textTapColor)
        val textSwipeTintList = ColorStateList.valueOf(textSwipeColor)

        // 1. Quick Paste Bar
        val qpBar: View? = quickPasteBar ?: rootView.findViewById(R.id.quickPasteBar)
        qpBar?.backgroundTintList = keyBgTintList
        rootView.findViewById<ImageView>(R.id.quickPasteIcon)?.imageTintList = textTapTintList
        quickPasteText?.setTextColor(textTapColor)
        rootView.findViewById<ImageView>(R.id.quickPasteDismiss)?.imageTintList = textSwipeTintList

        // 2. Undo & Redo Panel
        val urPanel: View? = undoRedoPanel ?: rootView.findViewById(R.id.undoRedoPanel)
        urPanel?.let { panel ->
            panel.backgroundTintList = kbBgTintList
            val header = (panel as? ViewGroup)?.getChildAt(0) as? TextView
            header?.setTextColor(textTapColor)
            header?.let { TextViewCompat.setCompoundDrawableTintList(it, textTapTintList) }

            panel.findViewById<TextView>(R.id.cardUndo)?.apply {
                backgroundTintList = keyBgTintList
                setTextColor(textTapColor)
                TextViewCompat.setCompoundDrawableTintList(this, textTapTintList)
            }
            panel.findViewById<TextView>(R.id.cardRedo)?.apply {
                backgroundTintList = keyBgTintList
                setTextColor(textTapColor)
                TextViewCompat.setCompoundDrawableTintList(this, textTapTintList)
            }
            panel.findViewById<TextView>(R.id.cardClearAll)?.apply {
                backgroundTintList = keyBgTintList
                setTextColor(textTapColor)
                TextViewCompat.setCompoundDrawableTintList(this, textTapTintList)
            }
        }

        // 3. Text Editing Panel
        val tePanel: View? = textEditPanel ?: rootView.findViewById(R.id.textEditPanel)
        tePanel?.let { panel ->
            panel.backgroundTintList = kbBgTintList
            val header = (panel as? ViewGroup)?.getChildAt(0) as? TextView
            header?.setTextColor(textTapColor)
            header?.let { TextViewCompat.setCompoundDrawableTintList(it, textTapTintList) }

            val actionBtnIds = listOf(
                R.id.btnTextSelectAll, R.id.btnTextCut,
                R.id.btnTextCopy, R.id.btnTextPaste, R.id.btnTextDelete
            )
            for (btnId in actionBtnIds) {
                panel.findViewById<TextView>(btnId)?.apply {
                    backgroundTintList = keyBgTintList
                    setTextColor(textTapColor)
                }
            }

            panel.findViewById<TextView>(R.id.btnTextSelect)?.apply {
                backgroundTintList = if (isTextSelecting) accentTintList else keyBgTintList
                setTextColor(if (isTextSelecting) colors.sendText else textTapColor)
            }

            val dpadImgIds = listOf(
                R.id.btnTextArrowUp, R.id.btnTextArrowDown,
                R.id.btnTextArrowLeft, R.id.btnTextArrowRight,
                R.id.btnTextJumpStart, R.id.btnTextJumpEnd
            )
            for (imgId in dpadImgIds) {
                panel.findViewById<ImageView>(imgId)?.apply {
                    backgroundTintList = keyBgTintList
                    imageTintList = textTapTintList
                }
            }

            panel.findViewById<TextView>(R.id.btnTextCenter)?.apply {
                backgroundTintList = toolBgTintList
                setTextColor(textTapColor)
            }
        }

        // 4. Voice Input Panel
        val viPanel: View? = voiceInputPanel ?: rootView.findViewById(R.id.voiceInputPanel)
        viPanel?.let { panel ->
            panel.backgroundTintList = kbBgTintList
            val header = (panel as? ViewGroup)?.getChildAt(0) as? TextView
            header?.setTextColor(textTapColor)
            header?.let { TextViewCompat.setCompoundDrawableTintList(it, textTapTintList) }

            voiceLiveText?.setTextColor(textTapColor)
            voiceStatusText?.setTextColor(textSwipeColor)
            btnVoiceMic?.backgroundTintList = if (isListeningVoice) accentTintList else keyBgTintList
            ivVoiceMicIcon?.imageTintList = if (isListeningVoice) ColorStateList.valueOf(colors.sendText) else textTapTintList
        }

        // 5. Emoji Panel
        val emPanel: View? = emojiPanel ?: rootView.findViewById(R.id.emojiPanel)
        emPanel?.let { panel ->
            panel.backgroundTintList = kbBgTintList
            panel.findViewById<HorizontalScrollView>(R.id.emojiCategoryScroll)?.background = null
            panel.findViewById<View>(R.id.emojiCategoryDivider)?.setBackgroundColor(
                if (colors.isDark) 0x1AFFFFFF else 0x1A000000
            )
        }

        // 6. Quick Theme Panel
        val qtPanel: View? = quickThemePanel ?: rootView.findViewById(R.id.quickThemePanel)
        qtPanel?.let { panel ->
            panel.backgroundTintList = kbBgTintList
            val header = (panel as? ViewGroup)?.getChildAt(0) as? TextView
            header?.setTextColor(textTapColor)
            header?.let { TextViewCompat.setCompoundDrawableTintList(it, textTapTintList) }

            val modeLabel = (panel.findViewById<TextView>(R.id.btnThemeLight)?.parent?.parent as? ViewGroup)?.getChildAt(0) as? TextView
            modeLabel?.setTextColor(textTapColor)

            panel.findViewById<TextView>(R.id.btnOpenFullThemes)?.apply {
                backgroundTintList = keyBgTintList
                setTextColor(textTapColor)
            }

            val modeRow = panel.findViewById<TextView>(R.id.btnThemeLight)?.parent as? ViewGroup
            modeRow?.backgroundTintList = toolBgTintList

            val currentOverride = isDarkModeOverride
            val isLightActive = currentOverride == false
            val isDarkActive = currentOverride == true
            val isSystemActive = currentOverride == null

            panel.findViewById<TextView>(R.id.btnThemeLight)?.apply {
                backgroundTintList = if (isLightActive) keyBgTintList else ColorStateList.valueOf(Color.TRANSPARENT)
                setTextColor(if (isLightActive) textTapColor else textSwipeColor)
            }
            panel.findViewById<TextView>(R.id.btnThemeDark)?.apply {
                backgroundTintList = if (isDarkActive) keyBgTintList else ColorStateList.valueOf(Color.TRANSPARENT)
                setTextColor(if (isDarkActive) textTapColor else textSwipeColor)
            }
            panel.findViewById<TextView>(R.id.btnThemeSystem)?.apply {
                backgroundTintList = if (isSystemActive) keyBgTintList else ColorStateList.valueOf(Color.TRANSPARENT)
                setTextColor(if (isSystemActive) textTapColor else textSwipeColor)
            }
        }

        // 7. Clipboard Panel
        val cbPanel: View? = clipboardPanel ?: rootView.findViewById(R.id.clipboardPanel)
        cbPanel?.let { panel ->
            panel.backgroundTintList = kbBgTintList
            val cbHeader = panel.findViewById<ViewGroup>(R.id.clipboardHeader)
            cbHeader?.findViewById<TextView>(R.id.btnClearUnpinned)?.apply {
                backgroundTintList = keyBgTintList
                setTextColor(textTapColor)
            }
            (cbHeader?.getChildAt(0) as? TextView)?.setTextColor(textTapColor)
        }

        // 8. More Panel
        val mPanel: View? = rootView.findViewById(R.id.morePanel)
        mPanel?.backgroundTintList = kbBgTintList
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
