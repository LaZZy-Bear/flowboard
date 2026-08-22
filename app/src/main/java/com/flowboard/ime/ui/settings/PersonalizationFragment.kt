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

    private fun setupHeroAndSwitches(view: View, mainActivity: MainActivity) {
        val switchPersonalization = view.findViewById<MaterialSwitch>(R.id.switchPersonalization)
        val switchPairs = view.findViewById<MaterialSwitch>(R.id.switchPairs)
        val switchFreq = view.findViewById<MaterialSwitch>(R.id.switchFreq)
        val switchAlphanumeric = view.findViewById<MaterialSwitch>(R.id.switchAlphanumeric)

        switchPersonalization?.isChecked = prefs.getBoolean("personalization_enabled", true)
        switchPairs?.isChecked = prefs.getBoolean("personalization_pairs_enabled", true)
        switchFreq?.isChecked = prefs.getBoolean("personalization_freq_enabled", true)
        switchAlphanumeric?.isChecked = prefs.getBoolean("personalization_alphanumeric_enabled", true)

        switchPersonalization?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("personalization_enabled", isChecked) }
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
    }

    private fun setupTuningDialogs(view: View, mainActivity: MainActivity) {
        // Boost Intensity
        val tvBoost = view.findViewById<TextView>(R.id.tvBoostIntensity)
        val boostOptions = arrayOf("0.8x", "1.0x (Default)", "1.2x", "1.5x", "2.0x")
        val currentBoost = prefs.getString("personalization_boost_multiplier", "1.0x (Default)") ?: "1.0x (Default)"
        tvBoost?.text = currentBoost
        view.findViewById<View>(R.id.rowBoostIntensity)?.setOnClickListener {
            showSingleChoiceDialog("Boost Intensity", boostOptions, tvBoost?.text?.toString()) { selected ->
                tvBoost?.text = selected
                prefs.edit { putString("personalization_boost_multiplier", selected) }
                mainActivity.notifyImeSettingsChanged("personalization_boost_multiplier", selected)
            }
        }

        // Learned Word Priority
        val tvPriority = view.findViewById<TextView>(R.id.tvLearnedPriority)
        val priorityOptions = arrayOf("1.0x", "1.3x (Default)", "1.5x", "1.8x", "2.0x")
        val currentPriority = prefs.getString("personalization_oov_multiplier", "1.3x (Default)") ?: "1.3x (Default)"
        tvPriority?.text = currentPriority
        view.findViewById<View>(R.id.rowLearnedPriority)?.setOnClickListener {
            showSingleChoiceDialog("Learned Word Priority", priorityOptions, tvPriority?.text?.toString()) { selected ->
                tvPriority?.text = selected
                prefs.edit { putString("personalization_oov_multiplier", selected) }
                mainActivity.notifyImeSettingsChanged("personalization_oov_multiplier", selected)
            }
        }

        // First-Type Bonus
        val tvBonus = view.findViewById<TextView>(R.id.tvFirstTypeBonus)
        val bonusOptions = arrayOf("+10", "+20", "+30 (Default)", "+40", "+50")
        val currentBonus = prefs.getString("personalization_first_type_bonus", "+30 (Default)") ?: "+30 (Default)"
        tvBonus?.text = currentBonus
        view.findViewById<View>(R.id.rowFirstTypeBonus)?.setOnClickListener {
            showSingleChoiceDialog("First-Type Bonus", bonusOptions, tvBonus?.text?.toString()) { selected ->
                tvBonus?.text = selected
                prefs.edit { putString("personalization_first_type_bonus", selected) }
                mainActivity.notifyImeSettingsChanged("personalization_first_type_bonus", selected)
            }
        }

        // Uncertainty Gap
        val tvGap = view.findViewById<TextView>(R.id.tvUncertaintyGap)
        val gapOptions = arrayOf("5.0", "10.0", "15.0 (Default)", "20.0", "25.0")
        val currentGap = prefs.getString("personalization_uncertainty_gap", "15.0 (Default)") ?: "15.0 (Default)"
        tvGap?.text = currentGap
        view.findViewById<View>(R.id.rowUncertaintyGap)?.setOnClickListener {
            showSingleChoiceDialog("Uncertainty Gap", gapOptions, tvGap?.text?.toString()) { selected ->
                tvGap?.text = selected
                prefs.edit { putString("personalization_uncertainty_gap", selected) }
                mainActivity.notifyImeSettingsChanged("personalization_uncertainty_gap", selected)
            }
        }
    }

    private fun setupCapacityDialogs(view: View, mainActivity: MainActivity) {
        // Max Frequent Words
        val tvMaxFreq = view.findViewById<TextView>(R.id.tvMaxFrequentWords)
        val freqOptions = arrayOf("500", "1,000 (Default)", "2,000", "5,000")
        val currentMaxFreq = prefs.getString("personalization_max_word_freq", "1,000 (Default)") ?: "1,000 (Default)"
        tvMaxFreq?.text = currentMaxFreq
        view.findViewById<View>(R.id.rowMaxFrequentWords)?.setOnClickListener {
            showSingleChoiceDialog("Max Frequent Words", freqOptions, tvMaxFreq?.text?.toString()) { selected ->
                tvMaxFreq?.text = selected
                prefs.edit { putString("personalization_max_word_freq", selected) }
                mainActivity.notifyImeSettingsChanged("personalization_max_word_freq", selected)
            }
        }

        // Max Word Pairs
        val tvMaxPairs = view.findViewById<TextView>(R.id.tvMaxWordPairs)
        val pairsOptions = arrayOf("500", "1,000 (Default)", "2,000", "5,000")
        val currentMaxPairs = prefs.getString("personalization_max_pairs", "1,000 (Default)") ?: "1,000 (Default)"
        tvMaxPairs?.text = currentMaxPairs
        view.findViewById<View>(R.id.rowMaxWordPairs)?.setOnClickListener {
            showSingleChoiceDialog("Max Word Pairs", pairsOptions, tvMaxPairs?.text?.toString()) { selected ->
                tvMaxPairs?.text = selected
                prefs.edit { putString("personalization_max_pairs", selected) }
                mainActivity.notifyImeSettingsChanged("personalization_max_pairs", selected)
            }
        }

        // Max Custom Words
        val tvMaxCustom = view.findViewById<TextView>(R.id.tvMaxCustomWords)
        val customOptions = arrayOf("200", "500 (Default)", "1,000", "2,000")
        val currentMaxCustom = prefs.getString("personalization_max_oov", "500 (Default)") ?: "500 (Default)"
        tvMaxCustom?.text = currentMaxCustom
        view.findViewById<View>(R.id.rowMaxCustomWords)?.setOnClickListener {
            showSingleChoiceDialog("Max Custom Words", customOptions, tvMaxCustom?.text?.toString()) { selected ->
                tvMaxCustom?.text = selected
                prefs.edit { putString("personalization_max_oov", selected) }
                mainActivity.notifyImeSettingsChanged("personalization_max_oov", selected)
            }
        }
    }

    private fun setupDataAndPrivacy(view: View, mainActivity: MainActivity) {
        val btnClear = view.findViewById<MaterialButton>(R.id.btnClearPersonalization)
        btnClear?.setOnClickListener {
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
        val checkedItem = options.indexOf(current).takeIf { it >= 0 } ?: 1
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
            liveMgr.loadProfile()
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
