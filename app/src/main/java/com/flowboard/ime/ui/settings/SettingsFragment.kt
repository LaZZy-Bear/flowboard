package com.flowboard.ime.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
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
            val languages = arrayOf("English (US) (Active)", "ภาษาไทย (เร็วๆ นี้ / Coming Soon)")
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Languages")
                .setSingleChoiceItems(languages, 0) { dialog, which ->
                    if (which == 1) {
                        Toast.makeText(requireContext(), "ภาษาไทยกำลังพัฒนาอยู่ในเวอร์ชันถัดไปครับ", Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Close", null)
                .show()
        }

        // Setup Switch Toggles
        val switchSound = view.findViewById<MaterialSwitch>(R.id.switchSound)
        val switchShowSuggestions = view.findViewById<MaterialSwitch>(R.id.switchShowSuggestions)

        switchSound?.isChecked = prefs.getBoolean("sound_on_keypress", false)
        switchShowSuggestions?.isChecked = prefs.getBoolean("show_suggestions", true)

        switchSound?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("sound_on_keypress", isChecked) }
            mainActivity.notifyImeSettingsChanged("sound_on_keypress", isChecked)
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
    }

    override fun onResume() {
        super.onResume()
        val mainActivity = activity as? MainActivity ?: return
        view?.let { setupActivationCard(it, mainActivity) }
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
            btnAction?.setOnClickListener { mainActivity.enableKeyboard() }
        } else if (!isSelected) {
            cardWarning.visibility = View.VISIBLE
            tvTitle?.setText(R.string.keyboard_not_active_title)
            tvDesc?.setText(R.string.keyboard_not_active_desc)
            btnAction?.setText(R.string.select_flowboard)
            btnAction?.setOnClickListener { mainActivity.selectKeyboard() }
        } else {
            cardWarning.visibility = View.GONE
        }
    }
}
