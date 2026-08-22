package com.flowboard.ime.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import com.flowboard.ime.MainActivity
import com.flowboard.ime.R

class SidebarSettingsFragment : Fragment() {

    private lateinit var prefs: SharedPreferences

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_sidebar_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mainActivity = activity as? MainActivity ?: return
        prefs = requireContext().getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)

        mainActivity.setToolbarTitle("Side Bar & Delete Button", true)

        // Docked Sidebar
        val rgDocked = view.findViewById<RadioGroup>(R.id.rgDockedSidebar)
        val isDockedLeft = prefs.getBoolean("docked_side_tools_left", false)
        if (isDockedLeft) {
            view.findViewById<RadioButton>(R.id.rbDockedLeft)?.isChecked = true
        } else {
            view.findViewById<RadioButton>(R.id.rbDockedRight)?.isChecked = true
        }

        rgDocked?.setOnCheckedChangeListener { _, checkedId ->
            val left = (checkedId == R.id.rbDockedLeft)
            prefs.edit { putBoolean("docked_side_tools_left", left) }
            mainActivity.notifyImeSettingsChanged("docked_side_tools_left", left)
        }

        // Floating Sidebar
        val rgFloating = view.findViewById<RadioGroup>(R.id.rgFloatingSidebar)
        val isFloatingLeft = prefs.getBoolean("floating_side_tools_left", false)
        if (isFloatingLeft) {
            view.findViewById<RadioButton>(R.id.rbFloatingLeft)?.isChecked = true
        } else {
            view.findViewById<RadioButton>(R.id.rbFloatingRight)?.isChecked = true
        }

        rgFloating?.setOnCheckedChangeListener { _, checkedId ->
            val left = (checkedId == R.id.rbFloatingLeft)
            prefs.edit { putBoolean("floating_side_tools_left", left) }
            mainActivity.notifyImeSettingsChanged("floating_side_tools_left", left)
        }

        // Delete Button Position
        val rgDelete = view.findViewById<RadioGroup>(R.id.rgDeleteButton)
        val followSide = prefs.getBoolean("delete_btn_follow_side_tools", true)
        val fixedSide = prefs.getString("delete_btn_fixed_side", "right") ?: "right"

        if (followSide) {
            view.findViewById<RadioButton>(R.id.rbDeleteFollow)?.isChecked = true
        } else if (fixedSide == "left") {
            view.findViewById<RadioButton>(R.id.rbDeleteFixedLeft)?.isChecked = true
        } else {
            view.findViewById<RadioButton>(R.id.rbDeleteFixedRight)?.isChecked = true
        }

        rgDelete?.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbDeleteFollow -> {
                    prefs.edit {
                        putBoolean("delete_btn_follow_side_tools", true)
                    }
                    mainActivity.notifyImeSettingsChanged("delete_btn_follow_side_tools", true)
                }
                R.id.rbDeleteFixedRight -> {
                    prefs.edit {
                        putBoolean("delete_btn_follow_side_tools", false)
                        putString("delete_btn_fixed_side", "right")
                    }
                    mainActivity.notifyImeSettingsChanged("delete_btn_fixed_side", "right")
                }
                R.id.rbDeleteFixedLeft -> {
                    prefs.edit {
                        putBoolean("delete_btn_follow_side_tools", false)
                        putString("delete_btn_fixed_side", "left")
                    }
                    mainActivity.notifyImeSettingsChanged("delete_btn_fixed_side", "left")
                }
            }
        }
    }
}
