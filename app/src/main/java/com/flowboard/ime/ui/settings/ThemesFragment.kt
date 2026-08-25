package com.flowboard.ime.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import com.flowboard.ime.MainActivity
import com.flowboard.ime.R
import com.google.android.material.card.MaterialCardView

class ThemesFragment : Fragment() {

    private lateinit var prefs: SharedPreferences

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_themes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mainActivity = activity as? MainActivity ?: return
        prefs = requireContext().getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)

        mainActivity.setToolbarTitle("Keyboard Themes", false)

        val currentTheme = prefs.getString("active_theme", "System default") ?: "System default"
        updateSelectionUI(view, currentTheme)

        // Bind clicks
        val cardAuto = view.findViewById<MaterialCardView>(R.id.cardThemeAuto)
        val cardLight = view.findViewById<MaterialCardView>(R.id.cardThemeLight)
        val cardDark = view.findViewById<MaterialCardView>(R.id.cardThemeDark)

        val cardBlue = view.findViewById<MaterialCardView>(R.id.cardColorBlue)
        val cardTeal = view.findViewById<MaterialCardView>(R.id.cardColorTeal)
        val cardCoral = view.findViewById<MaterialCardView>(R.id.cardColorCoral)
        val cardSakura = view.findViewById<MaterialCardView>(R.id.cardColorSakura)

        cardAuto?.setOnClickListener { selectTheme(view, mainActivity, "System default") }
        cardLight?.setOnClickListener { selectTheme(view, mainActivity, "Light") }
        cardDark?.setOnClickListener { selectTheme(view, mainActivity, "Dark") }

        cardBlue?.setOnClickListener { selectTheme(view, mainActivity, "Ocean Blue") }
        cardTeal?.setOnClickListener { selectTheme(view, mainActivity, "Mint Teal") }
        cardCoral?.setOnClickListener { selectTheme(view, mainActivity, "Sunset Coral") }
        cardSakura?.setOnClickListener { selectTheme(view, mainActivity, "Sakura Pink") }
    }

    private fun selectTheme(view: View, mainActivity: MainActivity, themeName: String) {
        prefs.edit {
            putString("active_theme", themeName)
            when (themeName) {
                "Dark" -> putBoolean("dark_mode_override", true)
                "Light" -> putBoolean("dark_mode_override", false)
                "System default" -> remove("dark_mode_override")
                else -> remove("dark_mode_override")
            }
        }
        updateSelectionUI(view, themeName)
        mainActivity.notifyImeSettingsChanged("active_theme", themeName)
    }

    private fun updateSelectionUI(view: View, activeTheme: String) {
        val dp2 = (2 * resources.displayMetrics.density).toInt()
        val dp1 = (1 * resources.displayMetrics.density).toInt()
        val colorAccent = ContextCompat.getColor(requireContext(), R.color.accent)
        val colorStroke = ContextCompat.getColor(requireContext(), R.color.card_stroke)

        val cardMap = mapOf(
            "System default" to (view.findViewById<MaterialCardView>(R.id.cardThemeAuto) to view.findViewById<ImageView>(R.id.checkThemeAuto)),
            "Light" to (view.findViewById<MaterialCardView>(R.id.cardThemeLight) to view.findViewById<ImageView>(R.id.checkThemeLight)),
            "Dark" to (view.findViewById<MaterialCardView>(R.id.cardThemeDark) to view.findViewById<ImageView>(R.id.checkThemeDark)),
            "Ocean Blue" to (view.findViewById<MaterialCardView>(R.id.cardColorBlue) to view.findViewById<ImageView>(R.id.checkColorBlue)),
            "Mint Teal" to (view.findViewById<MaterialCardView>(R.id.cardColorTeal) to view.findViewById<ImageView>(R.id.checkColorTeal)),
            "Sunset Coral" to (view.findViewById<MaterialCardView>(R.id.cardColorCoral) to view.findViewById<ImageView>(R.id.checkColorCoral)),
            "Sakura Pink" to (view.findViewById<MaterialCardView>(R.id.cardColorSakura) to view.findViewById<ImageView>(R.id.checkColorSakura))
        )

        // Normalize aliases
        val normalizedActive = when (activeTheme) {
            "Clean Minimal" -> "Light"
            "Blue" -> "Ocean Blue"
            "Geo Grid", "Teal" -> "Mint Teal"
            "Warm Bokeh", "Coral" -> "Sunset Coral"
            "Sakura" -> "Sakura Pink"
            else -> activeTheme
        }

        cardMap.forEach { (id, pair) ->
            val (card, check) = pair
            val isSelected = (id == normalizedActive)
            if (isSelected) {
                card?.strokeWidth = dp2
                card?.strokeColor = colorAccent
                check?.visibility = View.VISIBLE
            } else {
                card?.strokeWidth = dp1
                card?.strokeColor = colorStroke
                check?.visibility = View.GONE
            }
        }
    }
}
