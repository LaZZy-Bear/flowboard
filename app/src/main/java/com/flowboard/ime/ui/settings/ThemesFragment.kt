package com.flowboard.ime.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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

        cardAuto?.setOnClickListener { selectTheme(view, mainActivity, "System default") }
        cardLight?.setOnClickListener { selectTheme(view, mainActivity, "Light") }
        cardDark?.setOnClickListener { selectTheme(view, mainActivity, "Dark") }

        cardBlue?.setOnClickListener { selectTheme(view, mainActivity, "Blue") }
        cardTeal?.setOnClickListener { selectTheme(view, mainActivity, "Geo Grid") }
        cardCoral?.setOnClickListener { selectTheme(view, mainActivity, "Warm Bokeh") }
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

        val cards = mapOf(
            "System default" to (view.findViewById<MaterialCardView>(R.id.cardThemeAuto) to view.findViewById<ImageView>(R.id.checkThemeAuto)),
            "Light" to (view.findViewById<MaterialCardView>(R.id.cardThemeLight) to view.findViewById<ImageView>(R.id.checkThemeLight)),
            "Clean Minimal" to (view.findViewById<MaterialCardView>(R.id.cardThemeLight) to view.findViewById<ImageView>(R.id.checkThemeLight)),
            "Dark" to (view.findViewById<MaterialCardView>(R.id.cardThemeDark) to view.findViewById<ImageView>(R.id.checkThemeDark)),
            "Blue" to (view.findViewById<MaterialCardView>(R.id.cardColorBlue) to view.findViewById<ImageView>(R.id.checkColorBlue)),
            "Geo Grid" to (view.findViewById<MaterialCardView>(R.id.cardColorTeal) to view.findViewById<ImageView>(R.id.checkColorTeal)),
            "Warm Bokeh" to (view.findViewById<MaterialCardView>(R.id.cardColorCoral) to view.findViewById<ImageView>(R.id.checkColorCoral))
        )

        cards.forEach { (id, pair) ->
            val (card, check) = pair
            val isSelected = (id == activeTheme)
            if (isSelected) {
                card?.strokeWidth = dp2
                check?.visibility = View.VISIBLE
            } else if (activeTheme !in listOf("Light", "Clean Minimal") || (id != "Light" && id != "Clean Minimal")) {
                card?.strokeWidth = 0
                check?.visibility = View.GONE
            }
        }
    }
}
