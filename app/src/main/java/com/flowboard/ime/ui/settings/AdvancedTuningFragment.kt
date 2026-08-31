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
import com.flowboard.ime.engine.LayoutManager
import com.flowboard.ime.engine.ScoringEngine
import com.flowboard.ime.util.AdvancedTuningFormatter
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

        // 3. Initialize Master Layout
        val savedLayoutJson = prefs.getString("custom_master_layout_json", null)
        if (!savedLayoutJson.isNullOrBlank()) {
            val parsed = AdvancedTuningFormatter.easyTextToLayout(savedLayoutJson, prettyJson)
            if (parsed.isNotEmpty()) {
                etMasterLayout.setText(AdvancedTuningFormatter.layoutToEasyText(parsed))
            } else {
                etMasterLayout.setText(savedLayoutJson)
            }
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
            etMasterLayout.setText(AdvancedTuningFormatter.layoutToEasyText(baseLayout))
        }

        // 4. Initialize State Weights
        val savedWeightsJson = prefs.getString("custom_state_weights_json", null)
        if (!savedWeightsJson.isNullOrBlank()) {
            val parsed = AdvancedTuningFormatter.easyTextToWeights(savedWeightsJson, prettyJson)
            if (parsed.isNotEmpty()) {
                etStateWeights.setText(AdvancedTuningFormatter.weightsToEasyText(parsed))
            } else {
                etStateWeights.setText(savedWeightsJson)
            }
        } else {
            val baseWeights = FlowboardRepository.customStateWeights ?: ScoringEngine.getDefaultStateWeights()
            etStateWeights.setText(AdvancedTuningFormatter.weightsToEasyText(baseWeights))
        }

        // 5. Layout Action Buttons (Copy, Paste with Auto-format, Reset)
        view.findViewById<View>(R.id.btnCopyLayoutJson)?.setOnClickListener {
            copyToClipboard("Flowboard Master Layout", etMasterLayout.text.toString())
        }

        view.findViewById<View>(R.id.btnPasteLayoutJson)?.setOnClickListener {
            val pasteText = getFromClipboard()
            if (!pasteText.isNullOrBlank()) {
                val parsed = AdvancedTuningFormatter.easyTextToLayout(pasteText, prettyJson)
                if (parsed.isNotEmpty()) {
                    etMasterLayout.setText(AdvancedTuningFormatter.layoutToEasyText(parsed))
                    Toast.makeText(requireContext(), "Pasted & formatted 9-Key layout", Toast.LENGTH_SHORT).show()
                } else {
                    etMasterLayout.setText(pasteText)
                    Toast.makeText(requireContext(), "Pasted from clipboard", Toast.LENGTH_SHORT).show()
                }
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
                etMasterLayout.setText(AdvancedTuningFormatter.layoutToEasyText(defaultLayout))
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
                val parsed = AdvancedTuningFormatter.easyTextToWeights(pasteText, prettyJson)
                if (parsed.isNotEmpty()) {
                    etStateWeights.setText(AdvancedTuningFormatter.weightsToEasyText(parsed))
                    Toast.makeText(requireContext(), "Pasted & formatted 6-State weights", Toast.LENGTH_SHORT).show()
                } else {
                    etStateWeights.setText(pasteText)
                    Toast.makeText(requireContext(), "Pasted from clipboard", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<View>(R.id.btnResetWeightsJson)?.setOnClickListener {
            val defaultWeights = ScoringEngine.getDefaultStateWeights()
            etStateWeights.setText(AdvancedTuningFormatter.weightsToEasyText(defaultWeights))
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

    private fun applyAllChanges(mainActivity: MainActivity) {
        val lazyVal = (etLazy.text.toString().toFloatOrNull() ?: 1.15f).coerceIn(1.0f, 10.0f)
        val partnerVal = (etPartner.text.toString().toFloatOrNull() ?: 1.35f).coerceIn(1.0f, 10.0f)

        val layoutRaw = etMasterLayout.text.toString().trim()
        val weightsRaw = etStateWeights.text.toString().trim()

        // 1. Validate and Parse Master Layout (Supports Easy Text & JSON)
        var jsonLayoutToSave: String? = null
        if (layoutRaw.isNotEmpty()) {
            val parsedLayout = AdvancedTuningFormatter.easyTextToLayout(layoutRaw, prettyJson)
            if (parsedLayout.isEmpty()) {
                Toast.makeText(requireContext(), "Layout cannot be empty. Format: Key 1: tap=j, up=l, left=_, right=#", Toast.LENGTH_LONG).show()
                return
            }

            for ((char, entry) in parsedLayout) {
                if (!validHomeKeyRegex.matches(entry.homeKey)) {
                    Toast.makeText(requireContext(), "Invalid key '${entry.homeKey}' for char '$char'. Must be key_1 to key_9.", Toast.LENGTH_LONG).show()
                    return
                }
                if (!validSlots.contains(entry.defaultSlot)) {
                    Toast.makeText(requireContext(), "Invalid slot '${entry.defaultSlot}' for char '$char'. Must be tap, up, left, right, or down.", Toast.LENGTH_LONG).show()
                    return
                }
            }

            etMasterLayout.setText(AdvancedTuningFormatter.layoutToEasyText(parsedLayout))
            jsonLayoutToSave = prettyJson.encodeToString(parsedLayout)
        }

        // 2. Validate and Parse State Weights (Supports Easy Text & JSON)
        var jsonWeightsToSave: String? = null
        if (weightsRaw.isNotEmpty()) {
            val parsedWeights = AdvancedTuningFormatter.easyTextToWeights(weightsRaw, prettyJson)
            if (parsedWeights.isEmpty()) {
                Toast.makeText(requireContext(), "Weights cannot be empty. Format: State 1: U=25, B=23, T=41, D=33, WB=38, WT=77, STC=0", Toast.LENGTH_LONG).show()
                return
            }

            for ((stateNum, weights) in parsedWeights) {
                if (stateNum < 1 || stateNum > 10) {
                    Toast.makeText(requireContext(), "Invalid state number '$stateNum'. Must be 1, 2, 3, 4, 7, or 8.", Toast.LENGTH_LONG).show()
                    return
                }
                if (weights.U < 0 || weights.B < 0 || weights.T < 0 || weights.D < 0 || weights.WB < 0 || weights.WT < 0 || weights.STC < 0) {
                    Toast.makeText(requireContext(), "Weights for State $stateNum cannot contain negative values.", Toast.LENGTH_LONG).show()
                    return
                }
            }

            etStateWeights.setText(AdvancedTuningFormatter.weightsToEasyText(parsedWeights))
            val stringKeyWeights = parsedWeights.mapKeys { it.key.toString() }
            jsonWeightsToSave = prettyJson.encodeToString(stringKeyWeights)
        }

        val prefs = requireContext().getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)
        prefs.edit {
            putFloat("lazy_tap_ratio", lazyVal)
            putFloat("partner_tap_ratio", partnerVal)
            if (jsonLayoutToSave != null) {
                putString("custom_master_layout_json", jsonLayoutToSave)
            } else {
                remove("custom_master_layout_json")
            }
            if (jsonWeightsToSave != null) {
                putString("custom_state_weights_json", jsonWeightsToSave)
            } else {
                remove("custom_state_weights_json")
            }
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
            etMasterLayout.setText(AdvancedTuningFormatter.layoutToEasyText(defaultLayout))
        }

        val defaultWeights = ScoringEngine.getDefaultStateWeights()
        etStateWeights.setText(AdvancedTuningFormatter.weightsToEasyText(defaultWeights))

        // Reload into Repository
        FlowboardRepository.reloadAdvancedTuning(requireContext())

        // Broadcast to Live IME Service
        mainActivity.notifyImeSettingsChanged("advanced_tuning_changed", true)

        Toast.makeText(requireContext(), "Reset all advanced tuning to factory defaults", Toast.LENGTH_SHORT).show()
    }
}
