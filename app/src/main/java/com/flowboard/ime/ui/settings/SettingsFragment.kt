package com.flowboard.ime.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import com.flowboard.ime.MainActivity
import com.flowboard.ime.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsFragment : Fragment() {

    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())
    private var statusCheckerRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mainActivity = activity as? MainActivity ?: return
        prefs = requireContext().getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)

        // Setup Main Toolbar title
        mainActivity.setToolbarTitle("Keyboard Settings", false)

        // Setup Activation Warning Card
        setupActivationCard(view, mainActivity)

        // Languages click
        view.findViewById<View>(R.id.itemLanguage)?.setOnClickListener {
            val languages = arrayOf("English (US) (Active)", "More Languages (Coming Soon)")
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Languages")
                .setSingleChoiceItems(languages, 0) { dialog, which ->
                    if (which == 1) {
                        Toast.makeText(requireContext(), "More language packs will be supported soon", Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Close", null)
                .show()
        }

        // Setup Switch Toggles
        val switchSound = view.findViewById<MaterialSwitch>(R.id.switchSound)
        val switchVibration = view.findViewById<MaterialSwitch>(R.id.switchVibration)
        val switchShowSuggestions = view.findViewById<MaterialSwitch>(R.id.switchShowSuggestions)

        switchSound?.isChecked = prefs.getBoolean("sound_on_keypress", false)
        switchVibration?.isChecked = prefs.getBoolean("vibration_on_keypress", false)
        switchShowSuggestions?.isChecked = prefs.getBoolean("show_suggestions", true)

        switchSound?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("sound_on_keypress", isChecked) }
            mainActivity.notifyImeSettingsChanged("sound_on_keypress", isChecked)
        }

        switchVibration?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("vibration_on_keypress", isChecked) }
            mainActivity.notifyImeSettingsChanged("vibration_on_keypress", isChecked)
        }

        switchShowSuggestions?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("show_suggestions", isChecked) }
            mainActivity.notifyImeSettingsChanged("show_suggestions", isChecked)
        }

        // Subpage Navigation
        view.findViewById<View>(R.id.btnNavPersonalization)?.setOnClickListener {
            mainActivity.navigateToFragment(PersonalizationFragment())
        }

        view.findViewById<View>(R.id.btnNavSidebar)?.setOnClickListener {
            mainActivity.navigateToFragment(SidebarSettingsFragment())
        }

        view.findViewById<View>(R.id.btnNavShortcuts)?.setOnClickListener {
            mainActivity.navigateToFragment(ShortcutsFragment())
        }

        mainActivity.onKeyboardStatusChanged = {
            if (isAdded && !isDetached) {
                setupActivationCard(view, mainActivity)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val mainActivity = activity as? MainActivity ?: return
        view?.let { setupActivationCard(it, mainActivity) }
        startStatusChecker()
    }

    override fun onPause() {
        super.onPause()
        stopStatusChecker()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopStatusChecker()
        (activity as? MainActivity)?.let {
            if (it.onKeyboardStatusChanged != null) {
                it.onKeyboardStatusChanged = null
            }
        }
    }

    private fun startStatusChecker() {
        stopStatusChecker()
        val runnable = object : Runnable {
            override fun run() {
                if (isAdded && !isDetached) {
                    val act = activity as? MainActivity
                    val currentView = view
                    if (act != null && currentView != null) {
                        val isEnabled = act.isKeyboardEnabled()
                        val isSelected = act.isKeyboardSelected()
                        val cardWarning = currentView.findViewById<MaterialCardView>(R.id.cardActivationWarning)
                        if (isEnabled && isSelected) {
                            if (cardWarning?.visibility != View.GONE) {
                                cardWarning?.visibility = View.GONE
                            }
                            stopStatusChecker()
                            return
                        } else {
                            setupActivationCard(currentView, act)
                        }
                    }
                }
                mainHandler.postDelayed(this, 300L)
            }
        }
        statusCheckerRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopStatusChecker() {
        statusCheckerRunnable?.let { mainHandler.removeCallbacks(it) }
        statusCheckerRunnable = null
    }

    private fun setupActivationCard(view: View, mainActivity: MainActivity) {
        val cardWarning = view.findViewById<MaterialCardView>(R.id.cardActivationWarning) ?: return
        val tvTitle = view.findViewById<TextView>(R.id.tvActivationTitle)
        val tvDesc = view.findViewById<TextView>(R.id.tvActivationDesc)
        val btnAction = view.findViewById<MaterialButton>(R.id.btnActivationAction)

        val isEnabled = mainActivity.isKeyboardEnabled()
        val isSelected = mainActivity.isKeyboardSelected()

        if (!isEnabled) {
            cardWarning.visibility = View.VISIBLE
            tvTitle?.setText(R.string.keyboard_not_activated_title)
            tvDesc?.setText(R.string.keyboard_not_activated_desc)
            btnAction?.setText(R.string.turn_on_flowboard)
            btnAction?.setOnClickListener {
                mainActivity.enableKeyboard()
                startStatusChecker()
            }
            startStatusChecker()
        } else if (!isSelected) {
            cardWarning.visibility = View.VISIBLE
            tvTitle?.setText(R.string.keyboard_not_active_title)
            tvDesc?.setText(R.string.keyboard_not_active_desc)
            btnAction?.setText(R.string.select_flowboard)
            btnAction?.setOnClickListener {
                mainActivity.selectKeyboard()
                startStatusChecker()
            }
            startStatusChecker()
        } else {
            cardWarning.visibility = View.GONE
            stopStatusChecker()
        }
    }
}
