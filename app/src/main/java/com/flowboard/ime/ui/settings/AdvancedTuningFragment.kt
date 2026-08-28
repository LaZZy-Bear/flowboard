package com.flowboard.ime.ui.settings

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import com.flowboard.ime.MainActivity
import com.flowboard.ime.R
import com.flowboard.ime.data.FlowboardRepository
import com.flowboard.ime.data.models.EngineWeights
import com.flowboard.ime.data.models.MasterLayoutEntry
import com.flowboard.ime.engine.LayoutManager
import com.flowboard.ime.engine.ScoringEngine
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import kotlinx.serialization.json.Json
import java.util.Locale

class AdvancedTuningFragment : Fragment() {

    private val prettyJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private lateinit var sliderLazy: Slider
    private lateinit var etLazy: TextInputEditText
    private lateinit var sliderPartner: Slider
    private lateinit var etPartner: TextInputEditText

    private lateinit var etMasterLayout: TextInputEditText
    private lateinit var etStateWeights: TextInputEditText

    private var isUpdatingUi = false

    private val validSlots = setOf("tap", "up", "left", "right", "down")
    private val validHomeKeyRegex = Regex("^key_[1-9]$")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_advanced_tuning, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mainActivity = activity as? MainActivity ?: return

        mainActivity.setToolbarTitle("Advanced Engine Tuner", true)

        val prefs = requireContext().getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)

        sliderLazy = view.findViewById(R.id.sliderLazyTapRatio)
        etLazy = view.findViewById(R.id.etLazyTapRatio)
        sliderPartner = view.findViewById(R.id.sliderPartnerTapRatio)
        etPartner = view.findViewById(R.id.etPartnerTapRatio)
        etMasterLayout = view.findViewById(R.id.etMasterLayoutJson)
        etStateWeights = view.findViewById(R.id.etStateWeightsJson)

        // Enable natural inner touch scrolling inside multi-line JSON editors
        enableInnerScroll(etMasterLayout)
        enableInnerScroll(etStateWeights)

        // 1. Initialize Ratios (1.0 to 10.0)
        val currentLazy = prefs.getFloat("lazy_tap_ratio", LayoutManager.DEFAULT_LAZY_TAP_RATIO.toFloat())
        val currentPartner = prefs.getFloat("partner_tap_ratio", LayoutManager.DEFAULT_PARTNER_TAP_RATIO.toFloat())

        setLazyRatioUi(currentLazy)
        setPartnerRatioUi(currentPartner)

        setupRatioListeners()

        // 2. Setup Presets
        view.findViewById<View>(R.id.btnPresetStable)?.setOnClickListener {
            setLazyRatioUi(1.25f)
            setPartnerRatioUi(1.50f)
            Toast.makeText(requireContext(), "Stable Preset Applied (1.25 / 1.50)", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<View>(R.id.btnPresetDefault)?.setOnClickListener {
            setLazyRatioUi(LayoutManager.DEFAULT_LAZY_TAP_RATIO.toFloat())
            setPartnerRatioUi(LayoutManager.DEFAULT_PARTNER_TAP_RATIO.toFloat())
            Toast.makeText(requireContext(), "Default Preset Applied (1.15 / 1.35)", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<View>(R.id.btnPresetAggressive)?.setOnClickListener {
            setLazyRatioUi(1.05f)
            setPartnerRatioUi(1.15f)
            Toast.makeText(requireContext(), "Aggressive Preset Applied (1.05 / 1.15)", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<View>(R.id.btnPresetLock)?.setOnClickListener {
            setLazyRatioUi(10.0f)
            setPartnerRatioUi(10.0f)
            Toast.makeText(requireContext(), "Lock Preset Applied (10.0x / 10.0x - Swapping Disabled)", Toast.LENGTH_SHORT).show()
        }

        // 3. Initialize Master Layout JSON
        val savedLayoutJson = prefs.getString("custom_master_layout_json", null)
        if (!savedLayoutJson.isNullOrBlank()) {
            etMasterLayout.setText(formatJsonString(savedLayoutJson))
        } else {
            val baseLayout = if (FlowboardRepository.masterLayout.isNotEmpty()) {
                FlowboardRepository.masterLayout
            } else if (FlowboardRepository.defaultMasterLayout.isNotEmpty()) {
                FlowboardRepository.defaultMasterLayout
            } else {
                try {
                    com.flowboard.ime.data.AssetLoader(requireContext()).loadMasterLayout().also {
                        FlowboardRepository.defaultMasterLayout = it
                        FlowboardRepository.masterLayout = it
                    }
                } catch (_: Exception) {
                    emptyMap()
                }
            }
            try {
                etMasterLayout.setText(prettyJson.encodeToString(baseLayout))
            } catch (_: Exception) {
                etMasterLayout.setText("{}")
            }
        }

        // 4. Initialize State Weights JSON
        val savedWeightsJson = prefs.getString("custom_state_weights_json", null)
        if (!savedWeightsJson.isNullOrBlank()) {
            etStateWeights.setText(formatJsonString(savedWeightsJson))
        } else {
            val baseWeights = FlowboardRepository.customStateWeights ?: ScoringEngine.getDefaultStateWeights()
            val stringKeyWeights = baseWeights.mapKeys { it.key.toString() }
            try {
                etStateWeights.setText(prettyJson.encodeToString(stringKeyWeights))
            } catch (_: Exception) {
                etStateWeights.setText("{}")
            }
        }

        // 5. Layout Action Buttons (Copy, Paste with Auto-format, Reset)
        view.findViewById<View>(R.id.btnCopyLayoutJson)?.setOnClickListener {
            copyToClipboard("Flowboard Master Layout", etMasterLayout.text.toString())
        }

        view.findViewById<View>(R.id.btnPasteLayoutJson)?.setOnClickListener {
            val pasteText = getFromClipboard()
            if (!pasteText.isNullOrBlank()) {
                val formatted = formatMasterLayoutJson(pasteText)
                etMasterLayout.setText(formatted)
                Toast.makeText(requireContext(), "Pasted & auto-formatted from clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<View>(R.id.btnResetLayoutJson)?.setOnClickListener {
            val defaultLayout = if (FlowboardRepository.defaultMasterLayout.isNotEmpty()) {
                FlowboardRepository.defaultMasterLayout
            } else {
                try {
                    com.flowboard.ime.data.AssetLoader(requireContext()).loadMasterLayout().also {
                        FlowboardRepository.defaultMasterLayout = it
                    }
                } catch (_: Exception) {
                    emptyMap()
                }
            }
            if (defaultLayout.isNotEmpty()) {
                etMasterLayout.setText(prettyJson.encodeToString(defaultLayout))
                Toast.makeText(requireContext(), "Layout reset to factory defaults", Toast.LENGTH_SHORT).show()
            }
        }

        // 6. Weights Action Buttons (Copy, Paste with Auto-format, Reset)
        view.findViewById<View>(R.id.btnCopyWeightsJson)?.setOnClickListener {
            copyToClipboard("Flowboard State Weights", etStateWeights.text.toString())
        }

        view.findViewById<View>(R.id.btnPasteWeightsJson)?.setOnClickListener {
            val pasteText = getFromClipboard()
            if (!pasteText.isNullOrBlank()) {
                val formatted = formatJsonString(pasteText)
                etStateWeights.setText(formatted)
                Toast.makeText(requireContext(), "Pasted & auto-formatted from clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<View>(R.id.btnResetWeightsJson)?.setOnClickListener {
            val defaultWeights = ScoringEngine.getDefaultStateWeights().mapKeys { it.key.toString() }
            etStateWeights.setText(prettyJson.encodeToString(defaultWeights))
            Toast.makeText(requireContext(), "Weights reset to factory defaults", Toast.LENGTH_SHORT).show()
        }

        // 7. Master Apply Action (with Auto-Formatting)
        view.findViewById<MaterialButton>(R.id.btnApplyAdvancedChanges)?.setOnClickListener {
            applyAllChanges(mainActivity)
        }

        // 8. Reset Everything Action
        view.findViewById<MaterialButton>(R.id.btnResetAllToFactory)?.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Reset to Factory Defaults")
                .setMessage("Are you sure you want to reset all tap ratios, custom layout, and engine weights back to standard defaults?")
                .setPositiveButton("Reset") { _, _ ->
                    resetAllToFactory(mainActivity)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableInnerScroll(editText: TextInputEditText) {
        editText.setOnTouchListener { v, event ->
            if (editText.canScrollVertically(-1) || editText.canScrollVertically(1)) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }
    }

    private fun setLazyRatioUi(value: Float) {
        isUpdatingUi = true
        val clamped = (Math.round(value * 100f) / 100f).coerceIn(1.0f, 10.0f)
        sliderLazy.value = clamped.coerceIn(sliderLazy.valueFrom, sliderLazy.valueTo)
        etLazy.setText(String.format(Locale.US, "%.2f", clamped))
        isUpdatingUi = false
    }

    private fun setPartnerRatioUi(value: Float) {
        isUpdatingUi = true
        val clamped = (Math.round(value * 100f) / 100f).coerceIn(1.0f, 10.0f)
        sliderPartner.value = clamped.coerceIn(sliderPartner.valueFrom, sliderPartner.valueTo)
        etPartner.setText(String.format(Locale.US, "%.2f", clamped))
        isUpdatingUi = false
    }

    private fun setupRatioListeners() {
        sliderLazy.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isUpdatingUi) {
                isUpdatingUi = true
                val rounded = Math.round(value * 100f) / 100f
                etLazy.setText(String.format(Locale.US, "%.2f", rounded))
                isUpdatingUi = false
            }
        }

        sliderPartner.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isUpdatingUi) {
                isUpdatingUi = true
                val rounded = Math.round(value * 100f) / 100f
                etPartner.setText(String.format(Locale.US, "%.2f", rounded))
                isUpdatingUi = false
            }
        }

        etLazy.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingUi) {
                    val num = s?.toString()?.toFloatOrNull()
                    if (num != null && num in 1.0f..10.0f) {
                        isUpdatingUi = true
                        val rounded = (Math.round(num * 100f) / 100f).coerceIn(sliderLazy.valueFrom, sliderLazy.valueTo)
                        sliderLazy.value = rounded
                        isUpdatingUi = false
                    }
                }
            }
        })

        etPartner.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingUi) {
                    val num = s?.toString()?.toFloatOrNull()
                    if (num != null && num in 1.0f..10.0f) {
                        isUpdatingUi = true
                        val rounded = (Math.round(num * 100f) / 100f).coerceIn(sliderPartner.valueFrom, sliderPartner.valueTo)
                        sliderPartner.value = rounded
                        isUpdatingUi = false
                    }
                }
            }
        })
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun getFromClipboard(): String? {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            return clip.getItemAt(0).coerceToText(requireContext()).toString()
        }
        return null
    }

    private fun sanitizeSingleCharKey(rawKey: String): String {
        if (rawKey.isEmpty()) return ""
        val codePoints = rawKey.codePoints().toArray()
        return if (codePoints.isNotEmpty()) {
            String(codePoints, 0, 1)
        } else {
            rawKey.take(1)
        }
    }

    private fun formatMasterLayoutJson(raw: String): String {
        return try {
            val parsed = prettyJson.decodeFromString<Map<String, MasterLayoutEntry>>(raw)
            val sanitized = mutableMapOf<String, MasterLayoutEntry>()
            for ((rawChar, entry) in parsed) {
                val singleChar = sanitizeSingleCharKey(rawChar.trim())
                if (singleChar.isNotEmpty() && !sanitized.containsKey(singleChar)) {
                    sanitized[singleChar] = entry
                }
            }
            prettyJson.encodeToString(sanitized)
        } catch (_: Exception) {
            formatJsonString(raw)
        }
    }

    private fun formatJsonString(raw: String): String {
        return try {
            val element = prettyJson.parseToJsonElement(raw)
            prettyJson.encodeToString(element)
        } catch (_: Exception) {
            raw
        }
    }

    private fun applyAllChanges(mainActivity: MainActivity) {
        val lazyVal = (etLazy.text.toString().toFloatOrNull() ?: 1.15f).coerceIn(1.0f, 10.0f)
        val partnerVal = (etPartner.text.toString().toFloatOrNull() ?: 1.35f).coerceIn(1.0f, 10.0f)

        val layoutRaw = etMasterLayout.text.toString().trim()
        val weightsRaw = etStateWeights.text.toString().trim()

        // 1. Deep Validate Master Layout JSON
        val formattedLayout = if (layoutRaw.isNotEmpty() && layoutRaw != "{}") {
            try {
                val parsedLayout = prettyJson.decodeFromString<Map<String, MasterLayoutEntry>>(layoutRaw)
                if (parsedLayout.isEmpty()) {
                    Toast.makeText(requireContext(), "Layout JSON cannot be empty", Toast.LENGTH_SHORT).show()
                    return
                }

                // Sanitize multi-character keys to single character (e.g. "ab" -> "a", "hello" -> "h")
                val sanitizedLayout = mutableMapOf<String, MasterLayoutEntry>()
                for ((rawChar, entry) in parsedLayout) {
                    val singleChar = sanitizeSingleCharKey(rawChar.trim())
                    if (singleChar.isEmpty()) {
                        Toast.makeText(requireContext(), "Layout contains empty character key", Toast.LENGTH_LONG).show()
                        return
                    }
                    if (!validHomeKeyRegex.matches(entry.homeKey)) {
                        Toast.makeText(requireContext(), "Invalid homeKey '${entry.homeKey}' for char '$singleChar'. Must be key_1 to key_9.", Toast.LENGTH_LONG).show()
                        return
                    }
                    if (!validSlots.contains(entry.defaultSlot)) {
                        Toast.makeText(requireContext(), "Invalid defaultSlot '${entry.defaultSlot}' for char '$singleChar'. Must be tap, up, left, right, or down.", Toast.LENGTH_LONG).show()
                        return
                    }
                    if (!sanitizedLayout.containsKey(singleChar)) {
                        sanitizedLayout[singleChar] = entry
                    }
                }
                val formatted = prettyJson.encodeToString(sanitizedLayout)
                etMasterLayout.setText(formatted)
                formatted
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Invalid Master Layout JSON syntax: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                return
            }
        } else {
            layoutRaw
        }

        // 2. Deep Validate State Weights JSON
        val formattedWeights = if (weightsRaw.isNotEmpty() && weightsRaw != "{}") {
            try {
                val parsedWeights = prettyJson.decodeFromString<Map<String, EngineWeights>>(weightsRaw)
                if (parsedWeights.isEmpty()) {
                    Toast.makeText(requireContext(), "Weights JSON cannot be empty", Toast.LENGTH_SHORT).show()
                    return
                }

                for ((stateStr, weights) in parsedWeights) {
                    val stateNum = stateStr.toIntOrNull()
                    if (stateNum == null || stateNum < 1 || stateNum > 10) {
                        Toast.makeText(requireContext(), "Invalid state number '$stateStr'. Must be 1, 2, 3, 4, 7, or 8.", Toast.LENGTH_LONG).show()
                        return
                    }
                    if (weights.U < 0 || weights.B < 0 || weights.T < 0 || weights.D < 0 || weights.WB < 0 || weights.WT < 0 || weights.STC < 0) {
                        Toast.makeText(requireContext(), "Weights for State $stateNum cannot contain negative values.", Toast.LENGTH_LONG).show()
                        return
                    }
                }
                val formatted = prettyJson.encodeToString(parsedWeights)
                etStateWeights.setText(formatted)
                formatted
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Invalid State Weights JSON syntax: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                return
            }
        } else {
            weightsRaw
        }

        val prefs = requireContext().getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)
        prefs.edit {
            putFloat("lazy_tap_ratio", lazyVal)
            putFloat("partner_tap_ratio", partnerVal)
            putString("custom_master_layout_json", formattedLayout)
            putString("custom_state_weights_json", formattedWeights)
        }

        // Reload into Repository in-memory
        FlowboardRepository.reloadAdvancedTuning(requireContext())

        // Broadcast to Live IME Service
        mainActivity.notifyImeSettingsChanged("advanced_tuning_changed", true)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Tuning Applied Successfully!")
            .setMessage("All ratios, custom layout mapping, and state engine weights have been formatted and applied to the live keyboard.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun resetAllToFactory(mainActivity: MainActivity) {
        val prefs = requireContext().getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)
        prefs.edit {
            remove("lazy_tap_ratio")
            remove("partner_tap_ratio")
            remove("custom_master_layout_json")
            remove("custom_state_weights_json")
        }

        // Reload UI
        setLazyRatioUi(LayoutManager.DEFAULT_LAZY_TAP_RATIO.toFloat())
        setPartnerRatioUi(LayoutManager.DEFAULT_PARTNER_TAP_RATIO.toFloat())

        val defaultLayout = if (FlowboardRepository.defaultMasterLayout.isNotEmpty()) {
            FlowboardRepository.defaultMasterLayout
        } else {
            try {
                com.flowboard.ime.data.AssetLoader(requireContext()).loadMasterLayout().also {
                    FlowboardRepository.defaultMasterLayout = it
                }
            } catch (_: Exception) {
                emptyMap()
            }
        }
        if (defaultLayout.isNotEmpty()) {
            etMasterLayout.setText(prettyJson.encodeToString(defaultLayout))
        }

        val defaultWeights = ScoringEngine.getDefaultStateWeights().mapKeys { it.key.toString() }
        etStateWeights.setText(prettyJson.encodeToString(defaultWeights))

        // Reload into Repository
        FlowboardRepository.reloadAdvancedTuning(requireContext())

        // Broadcast to Live IME Service
        mainActivity.notifyImeSettingsChanged("advanced_tuning_changed", true)

        Toast.makeText(requireContext(), "Reset all advanced tuning to factory defaults", Toast.LENGTH_SHORT).show()
    }
}
