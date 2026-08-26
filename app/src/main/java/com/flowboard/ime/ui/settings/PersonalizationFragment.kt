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
import com.flowboard.ime.engine.LiveLearningManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class PersonalizationFragment : Fragment() {

    private lateinit var prefs: SharedPreferences

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_personalization, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mainActivity = activity as? MainActivity ?: return
        prefs = requireContext().getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)

        mainActivity.setToolbarTitle("Personalization & Learning", true)

        setupHeroAndSwitches(view, mainActivity)
        setupTuningDialogs(view, mainActivity)
        setupCapacityDialogs(view, mainActivity)
        setupDataAndPrivacy(view, mainActivity)

        loadStats(view)
    }

    override fun onResume() {
        super.onResume()
        view?.let { loadStats(it) }
    }

    private fun updateOptionsEnabledState(view: View, isEnabled: Boolean) {
        val container = view.findViewById<ViewGroup>(R.id.containerPersonalizationOptions) ?: return
        container.alpha = if (isEnabled) 1.0f else 0.38f
        setViewGroupEnabled(container, isEnabled)
    }

    private fun setViewGroupEnabled(viewGroup: ViewGroup, isEnabled: Boolean) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            child.isEnabled = isEnabled
            if (child is ViewGroup) {
                setViewGroupEnabled(child, isEnabled)
            }
        }
    }

    private fun bindDropdownRow(
        row: View?,
        tv: TextView?,
        title: String,
        options: Array<String>,
        prefKey: String,
        defaultVal: String,
        mainActivity: MainActivity
    ) {
        val currentVal = prefs.getString(prefKey, defaultVal) ?: defaultVal
        tv?.text = currentVal

        val onClick = View.OnClickListener {
            if (!prefs.getBoolean("personalization_enabled", true)) return@OnClickListener
            showSingleChoiceDialog(title, options, tv?.text?.toString()) { selected ->
                tv?.text = selected
                prefs.edit { putString(prefKey, selected) }
                mainActivity.notifyImeSettingsChanged(prefKey, selected)
            }
        }

        row?.setOnClickListener(onClick)
        tv?.setOnClickListener(onClick)
    }

    private fun setupHeroAndSwitches(view: View, mainActivity: MainActivity) {
        val switchPersonalization = view.findViewById<MaterialSwitch>(R.id.switchPersonalization)
        val switchPairs = view.findViewById<MaterialSwitch>(R.id.switchPairs)
        val switchFreq = view.findViewById<MaterialSwitch>(R.id.switchFreq)
        val switchAlphanumeric = view.findViewById<MaterialSwitch>(R.id.switchAlphanumeric)
        val switchLearnPasswords = view.findViewById<MaterialSwitch>(R.id.switchLearnPasswords)

        val isMasterEnabled = prefs.getBoolean("personalization_enabled", true)
        switchPersonalization?.isChecked = isMasterEnabled
        switchPairs?.isChecked = prefs.getBoolean("personalization_pairs_enabled", true)
        switchFreq?.isChecked = prefs.getBoolean("personalization_freq_enabled", true)
        switchAlphanumeric?.isChecked = prefs.getBoolean("personalization_alphanumeric_enabled", true)
        switchLearnPasswords?.isChecked = prefs.getBoolean("personalization_learn_passwords", false)

        updateOptionsEnabledState(view, isMasterEnabled)

        switchPersonalization?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("personalization_enabled", isChecked) }
            updateOptionsEnabledState(view, isChecked)
            mainActivity.notifyImeSettingsChanged("personalization_enabled", isChecked)
        }

        switchPairs?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("personalization_pairs_enabled", isChecked) }
            mainActivity.notifyImeSettingsChanged("personalization_pairs_enabled", isChecked)
        }

        switchFreq?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("personalization_freq_enabled", isChecked) }
            mainActivity.notifyImeSettingsChanged("personalization_freq_enabled", isChecked)
        }

        switchAlphanumeric?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("personalization_alphanumeric_enabled", isChecked) }
            mainActivity.notifyImeSettingsChanged("personalization_alphanumeric_enabled", isChecked)
        }

        switchLearnPasswords?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("personalization_learn_passwords", isChecked) }
            mainActivity.notifyImeSettingsChanged("personalization_learn_passwords", isChecked)
        }
    }

    private fun setupTuningDialogs(view: View, mainActivity: MainActivity) {
        // Boost Intensity
        bindDropdownRow(
            row = view.findViewById(R.id.rowBoostIntensity),
            tv = view.findViewById(R.id.tvBoostIntensity),
            title = "Boost Intensity",
            options = arrayOf("0.8x", "1.0x (Default)", "1.2x", "1.5x", "2.0x"),
            prefKey = "personalization_boost_multiplier",
            defaultVal = "1.0x (Default)",
            mainActivity = mainActivity
        )

        // Learned Word Priority
        bindDropdownRow(
            row = view.findViewById(R.id.rowLearnedPriority),
            tv = view.findViewById(R.id.tvLearnedPriority),
            title = "Learned Word Priority",
            options = arrayOf("1.0x", "1.3x (Default)", "1.5x", "1.8x", "2.0x"),
            prefKey = "personalization_oov_multiplier",
            defaultVal = "1.3x (Default)",
            mainActivity = mainActivity
        )

        // First-Type Bonus
        bindDropdownRow(
            row = view.findViewById(R.id.rowFirstTypeBonus),
            tv = view.findViewById(R.id.tvFirstTypeBonus),
            title = "First-Type Bonus",
            options = arrayOf("+10", "+20", "+30 (Default)", "+40", "+50"),
            prefKey = "personalization_first_type_bonus",
            defaultVal = "+30 (Default)",
            mainActivity = mainActivity
        )

        // Uncertainty Gap
        bindDropdownRow(
            row = view.findViewById(R.id.rowUncertaintyGap),
            tv = view.findViewById(R.id.tvUncertaintyGap),
            title = "Uncertainty Gap",
            options = arrayOf("5.0", "10.0", "15.0 (Default)", "20.0", "25.0"),
            prefKey = "personalization_uncertainty_gap",
            defaultVal = "15.0 (Default)",
            mainActivity = mainActivity
        )
    }

    private fun setupCapacityDialogs(view: View, mainActivity: MainActivity) {
        // Max Frequent Words
        bindDropdownRow(
            row = view.findViewById(R.id.rowMaxFrequentWords),
            tv = view.findViewById(R.id.tvMaxFrequentWords),
            title = "Max Frequent Words",
            options = arrayOf("500", "1,000 (Default)", "2,000", "5,000"),
            prefKey = "personalization_max_word_freq",
            defaultVal = "1,000 (Default)",
            mainActivity = mainActivity
        )

        // Max Word Pairs
        bindDropdownRow(
            row = view.findViewById(R.id.rowMaxWordPairs),
            tv = view.findViewById(R.id.tvMaxWordPairs),
            title = "Max Word Pairs",
            options = arrayOf("500", "1,000 (Default)", "2,000", "5,000"),
            prefKey = "personalization_max_pairs",
            defaultVal = "1,000 (Default)",
            mainActivity = mainActivity
        )

        // Max Custom Words
        bindDropdownRow(
            row = view.findViewById(R.id.rowMaxCustomWords),
            tv = view.findViewById(R.id.tvMaxCustomWords),
            title = "Max Custom Words",
            options = arrayOf("200", "500 (Default)", "1,000", "2,000"),
            prefKey = "personalization_max_oov",
            defaultVal = "500 (Default)",
            mainActivity = mainActivity
        )
    }

    private fun setupDataAndPrivacy(view: View, mainActivity: MainActivity) {
        val btnClear = view.findViewById<MaterialButton>(R.id.btnClearPersonalization)
        btnClear?.setOnClickListener {
            if (!prefs.getBoolean("personalization_enabled", true)) return@setOnClickListener
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear Learned Data")
                .setMessage("Are you sure you want to delete all learned words, frequency counts, and custom phrase patterns?")
                .setPositiveButton("Clear All") { _, _ ->
                    clearPersonalizationData(view, mainActivity)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showSingleChoiceDialog(
        title: String,
        options: Array<String>,
        current: String?,
        onSelected: (String) -> Unit
    ) {
        val checkedItem = options.indexOf(current).takeIf { it >= 0 }
            ?: options.indexOfFirst { it.contains("Default") }.takeIf { it >= 0 }
            ?: 0
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                onSelected(options[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadStats(view: View) {
        val tvTracked = view.findViewById<TextView>(R.id.tvStatTracked)
        val tvPairs = view.findViewById<TextView>(R.id.tvStatPairs)
        val tvCustom = view.findViewById<TextView>(R.id.tvStatCustom)

        try {
            val liveMgr = LiveLearningManager(requireContext())
            if (com.flowboard.ime.data.FlowboardRepository.personalProfile.isEmpty) {
                liveMgr.loadProfile()
            }
            val stats = liveMgr.getStats()
            tvTracked?.text = (stats["wordFreqCount"] ?: 0).toString()
            tvPairs?.text = (stats["totalPairsCount"] ?: 0).toString()
            tvCustom?.text = (stats["oovCount"] ?: 0).toString()
        } catch (_: Exception) {
            tvTracked?.text = "0"
            tvPairs?.text = "0"
            tvCustom?.text = "0"
        }
    }

    private fun clearPersonalizationData(view: View, mainActivity: MainActivity) {
        try {
            val liveMgr = LiveLearningManager(requireContext())
            liveMgr.clearProfile()
        } catch (_: Exception) {}

        loadStats(view)
        mainActivity.notifyImeSettingsChanged("clear_personalization", true)
        Toast.makeText(requireContext(), "Learned data cleared successfully", Toast.LENGTH_SHORT).show()
    }
}
