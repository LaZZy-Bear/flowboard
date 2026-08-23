package com.flowboard.ime.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import com.flowboard.ime.MainActivity
import com.flowboard.ime.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText

class ShortcutsFragment : Fragment() {

    private lateinit var prefs: SharedPreferences
    private var selectedKey = 1

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_shortcuts, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mainActivity = activity as? MainActivity ?: return
        prefs = requireContext().getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)

        mainActivity.setToolbarTitle("Quick Text Shortcuts", true)

        // Setup Key Cards
        for (i in 1..9) {
            val card = getKeyCard(view, i)
            card?.setOnClickListener {
                selectKey(view, i)
            }
        }

        // Render all tiles
        renderAllTiles(view)

        // Select key 1 by default
        selectKey(view, 1)

        // Save Button
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSaveShortcut)
        btnSave?.setOnClickListener {
            saveCurrentKey(view, mainActivity)
        }

        // Clear Button
        val btnClear = view.findViewById<MaterialButton>(R.id.btnClearShortcut)
        btnClear?.setOnClickListener {
            clearCurrentKey(view, mainActivity)
        }
    }

    private fun getKeyCard(view: View, keyNum: Int): MaterialCardView? {
        val id = when (keyNum) {
            1 -> R.id.cardKey1
            2 -> R.id.cardKey2
            3 -> R.id.cardKey3
            4 -> R.id.cardKey4
            5 -> R.id.cardKey5
            6 -> R.id.cardKey6
            7 -> R.id.cardKey7
            8 -> R.id.cardKey8
            9 -> R.id.cardKey9
            else -> null
        } ?: return null
        return view.findViewById(id)
    }

    private fun getLabelTextView(view: View, keyNum: Int): TextView? {
        val id = when (keyNum) {
            1 -> R.id.tvLabelKey1
            2 -> R.id.tvLabelKey2
            3 -> R.id.tvLabelKey3
            4 -> R.id.tvLabelKey4
            5 -> R.id.tvLabelKey5
            6 -> R.id.tvLabelKey6
            7 -> R.id.tvLabelKey7
            8 -> R.id.tvLabelKey8
            9 -> R.id.tvLabelKey9
            else -> null
        } ?: return null
        return view.findViewById(id)
    }

    private fun getPreviewTextView(view: View, keyNum: Int): TextView? {
        val id = when (keyNum) {
            1 -> R.id.tvPreviewKey1
            2 -> R.id.tvPreviewKey2
            3 -> R.id.tvPreviewKey3
            4 -> R.id.tvPreviewKey4
            5 -> R.id.tvPreviewKey5
            6 -> R.id.tvPreviewKey6
            7 -> R.id.tvPreviewKey7
            8 -> R.id.tvPreviewKey8
            9 -> R.id.tvPreviewKey9
            else -> null
        } ?: return null
        return view.findViewById(id)
    }

    private fun renderAllTiles(view: View) {
        for (i in 1..9) {
            val label = prefs.getString("shortcut_label_$i", "")?.trim() ?: ""
            val text = prefs.getString("shortcut_text_$i", "")?.trim() ?: ""

            val tvLabel = getLabelTextView(view, i)
            val tvPreview = getPreviewTextView(view, i)

            tvLabel?.text = if (label.isNotEmpty()) label else "⚡"
            tvPreview?.text = if (text.isNotEmpty()) text else "Empty"
        }
    }

    private fun selectKey(view: View, keyNum: Int) {
        selectedKey = keyNum
        val dp2 = (2 * resources.displayMetrics.density).toInt()
        val dp1 = (1 * resources.displayMetrics.density).toInt()

        for (i in 1..9) {
            val card = getKeyCard(view, i)
            if (i == keyNum) {
                card?.strokeWidth = dp2
                card?.strokeColor = ContextCompat.getColor(requireContext(), R.color.accent)
            } else {
                card?.strokeWidth = dp1
                card?.strokeColor = ContextCompat.getColor(requireContext(), R.color.card_stroke)
            }
        }

        val tvTitle = view.findViewById<TextView>(R.id.tvConfigTitle)
        val etLabel = view.findViewById<TextInputEditText>(R.id.etShortcutLabel)
        val etText = view.findViewById<TextInputEditText>(R.id.etShortcutText)

        tvTitle?.text = getString(R.string.configure_key_format, keyNum)
        val label = prefs.getString("shortcut_label_$keyNum", "") ?: ""
        val text = prefs.getString("shortcut_text_$keyNum", "") ?: ""

        etLabel?.setText(label)
        etText?.setText(text)
    }

    private fun saveCurrentKey(view: View, mainActivity: MainActivity) {
        val etLabel = view.findViewById<TextInputEditText>(R.id.etShortcutLabel)
        val etText = view.findViewById<TextInputEditText>(R.id.etShortcutText)

        val label = etLabel?.text?.toString()?.trim() ?: ""
        val text = etText?.text?.toString()?.trim() ?: ""

        prefs.edit {
            putString("shortcut_label_$selectedKey", label)
            putString("shortcut_text_$selectedKey", text)
        }

        renderAllTiles(view)
        mainActivity.notifyShortcutChanged(selectedKey, label, text)
        Toast.makeText(requireContext(), "Key $selectedKey shortcut saved", Toast.LENGTH_SHORT).show()
    }

    private fun clearCurrentKey(view: View, mainActivity: MainActivity) {
        val etLabel = view.findViewById<TextInputEditText>(R.id.etShortcutLabel)
        val etText = view.findViewById<TextInputEditText>(R.id.etShortcutText)

        etLabel?.setText("")
        etText?.setText("")

        prefs.edit {
            remove("shortcut_label_$selectedKey")
            remove("shortcut_text_$selectedKey")
        }

        renderAllTiles(view)
        mainActivity.notifyShortcutChanged(selectedKey, "", "")
        Toast.makeText(requireContext(), "Key $selectedKey shortcut cleared", Toast.LENGTH_SHORT).show()
    }
}
