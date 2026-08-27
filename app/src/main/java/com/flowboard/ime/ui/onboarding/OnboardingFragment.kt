package com.flowboard.ime.ui.onboarding

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import com.flowboard.ime.MainActivity
import com.flowboard.ime.R
import com.flowboard.ime.ui.settings.SettingsFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class OnboardingFragment : Fragment() {

    private lateinit var prefs: SharedPreferences
    private var currentStep = 1
    private val totalSteps = 2

    // Layout containers
    private lateinit var progressIndicator: LinearProgressIndicator
    private lateinit var tvStepCounter: TextView
    private lateinit var btnSkip: MaterialButton
    private lateinit var btnBack: MaterialButton
    private lateinit var btnContinue: MaterialButton

    private lateinit var step1Layout: View
    private lateinit var step2Layout: View

    // Step 1 Views
    private lateinit var tvEnableBadge: TextView
    private lateinit var tvSelectBadge: TextView
    private lateinit var btnEnableKeyboard: MaterialButton
    private lateinit var btnSelectKeyboard: MaterialButton

    // Step 2 Views
    private lateinit var switchPersonalization: MaterialSwitch

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_onboarding, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mainActivity = activity as? MainActivity ?: return
        prefs = requireContext().getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)

        // Clean status bar and navigation bar insets so onboarding doesn't overlap system bars
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(0, statusBarInsets.top, 0, navBarInsets.bottom)
            insets
        }

        // Hide main chrome for clean fullscreen setup
        mainActivity.setMainChromeVisible(false)

        initViews(view)
        setupStep1(mainActivity)
        setupStep2(mainActivity)
        setupNavigation(mainActivity)

        updateStepUI(mainActivity)
    }

    private fun initViews(view: View) {
        progressIndicator = view.findViewById(R.id.onboardingProgress)
        tvStepCounter = view.findViewById(R.id.tvStepCounter)
        btnSkip = view.findViewById(R.id.btnSkip)
        btnBack = view.findViewById(R.id.btnBack)
        btnContinue = view.findViewById(R.id.btnContinue)

        step1Layout = view.findViewById(R.id.step1Layout)
        step2Layout = view.findViewById(R.id.step2Layout)

        // Step 1
        tvEnableBadge = view.findViewById(R.id.tvEnableBadge)
        tvSelectBadge = view.findViewById(R.id.tvSelectBadge)
        btnEnableKeyboard = view.findViewById(R.id.btnEnableKeyboard)
        btnSelectKeyboard = view.findViewById(R.id.btnSelectKeyboard)

        // Step 2
        switchPersonalization = view.findViewById(R.id.switchPersonalization)
    }

    private fun setupStep1(mainActivity: MainActivity) {
        btnEnableKeyboard.setOnClickListener {
            mainActivity.enableKeyboard()
        }

        btnSelectKeyboard.setOnClickListener {
            mainActivity.selectKeyboard()
        }

        mainActivity.onKeyboardStatusChanged = {
            if (isAdded && !isDetached) {
                checkActivationStatus(mainActivity)
            }
        }
        checkActivationStatus(mainActivity)
    }

    private fun checkActivationStatus(mainActivity: MainActivity) {
        val isEnabled = mainActivity.isKeyboardEnabled()
        val isSelected = mainActivity.isKeyboardSelected()

        val greenColor = ContextCompat.getColor(requireContext(), R.color.green_active)
        val redColor = ContextCompat.getColor(requireContext(), R.color.red_inactive)

        if (isEnabled) {
            tvEnableBadge.text = "✓ Enabled"
            tvEnableBadge.setTextColor(greenColor)
            btnEnableKeyboard.text = "Enabled"
            btnEnableKeyboard.isEnabled = false
        } else {
            tvEnableBadge.text = "Required"
            tvEnableBadge.setTextColor(redColor)
            btnEnableKeyboard.text = "Enable in Settings"
            btnEnableKeyboard.isEnabled = true
        }

        if (isSelected) {
            tvSelectBadge.text = "✓ Selected"
            tvSelectBadge.setTextColor(greenColor)
            btnSelectKeyboard.text = "Active Keyboard"
            btnSelectKeyboard.isEnabled = false
        } else {
            tvSelectBadge.text = "Required"
            tvSelectBadge.setTextColor(redColor)
            btnSelectKeyboard.text = "Switch to Flowboard"
            btnSelectKeyboard.isEnabled = true
        }
    }

    private fun setupStep2(mainActivity: MainActivity) {
        switchPersonalization.isChecked = prefs.getBoolean("personalization_enabled", true)
        switchPersonalization.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("personalization_enabled", isChecked) }
            mainActivity.notifyImeSettingsChanged("personalization_enabled", isChecked)
        }
    }

    private fun setupNavigation(mainActivity: MainActivity) {
        btnSkip.setOnClickListener {
            finishOnboarding(mainActivity)
        }

        btnBack.setOnClickListener {
            if (currentStep > 1) {
                currentStep--
                updateStepUI(mainActivity)
            }
        }

        btnContinue.setOnClickListener {
            if (currentStep < totalSteps) {
                currentStep++
                updateStepUI(mainActivity)
            } else {
                finishOnboarding(mainActivity)
            }
        }
    }

    private fun updateStepUI(mainActivity: MainActivity) {
        progressIndicator.progress = currentStep
        tvStepCounter.text = "Step $currentStep of $totalSteps"

        step1Layout.visibility = if (currentStep == 1) View.VISIBLE else View.GONE
        step2Layout.visibility = if (currentStep == 2) View.VISIBLE else View.GONE

        btnBack.visibility = if (currentStep > 1) View.VISIBLE else View.GONE
        btnContinue.text = if (currentStep == totalSteps) "Finish Setup & Start Typing" else "Continue"

        if (currentStep == 1) {
            checkActivationStatus(mainActivity)
        }
    }

    private fun finishOnboarding(mainActivity: MainActivity) {
        val isPersonalizationOn = switchPersonalization.isChecked

        prefs.edit {
            putBoolean("onboarding_completed", true)
            // Ensure system defaults are explicitly saved if not present
            if (!prefs.contains("docked_keyboard_scale")) putFloat("docked_keyboard_scale", 1.25f)
            if (!prefs.contains("active_theme")) putString("active_theme", "System default")
            if (!prefs.contains("docked_side_tools_left")) putBoolean("docked_side_tools_left", true)
            if (!prefs.contains("delete_btn_follow_side_tools")) putBoolean("delete_btn_follow_side_tools", false)
            if (!prefs.contains("delete_btn_fixed_side")) putString("delete_btn_fixed_side", "right")
            if (!prefs.contains("sound_on_keypress")) putBoolean("sound_on_keypress", true)
            if (!prefs.contains("vibration_on_keypress")) putBoolean("vibration_on_keypress", true)
            putBoolean("personalization_enabled", isPersonalizationOn)
        }

        // Notify IME Service with final settings
        mainActivity.notifyImeSettingsChanged("personalization_enabled", isPersonalizationOn)
        mainActivity.notifyImeSettingsChanged("docked_side_tools_left", true)
        mainActivity.notifyImeSettingsChanged("delete_btn_fixed_side", "right")

        mainActivity.setMainChromeVisible(true)
        mainActivity.replaceRootFragment(SettingsFragment())
    }
}
