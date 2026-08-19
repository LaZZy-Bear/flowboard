package com.flowboard.ime.util

import android.content.Context
import android.graphics.Color
import com.flowboard.ime.ui.KeyView

data class ThemeColors(
    val keyboardBackground: Int,
    val keyBackground: Int,
    val keyActive: Int,
    val textTap: Int,
    val textSwipe: Int,
    val accent: Int,
    val toolBackground: Int,
    val zoneTopStart: Int,
    val zoneMidStart: Int,
    val zoneBotStart: Int,
    val isDark: Boolean
) {
    val sendText: Int = Color.WHITE
}

data class ZoneColors(
    val startColor: Int,
    val endColor: Int
)

object ThemeManager {
    fun getThemeColors(context: Context, themeName: String, isSystemDark: Boolean): ThemeColors {
        val resolvedTheme = when {
            themeName == "System default" -> if (isSystemDark) "Dark" else "Clean Minimal"
            themeName == "Clean Minimal" && isSystemDark -> "Dark"
            else -> themeName
        }

        return when (resolvedTheme) {
            "Dark" -> ThemeColors(
                keyboardBackground = Color.parseColor("#1C1C1E"),
                keyBackground = Color.parseColor("#2C2C2E"),
                keyActive = Color.parseColor("#3A3A3C"),
                textTap = Color.parseColor("#FFFFFF"),
                textSwipe = Color.parseColor("#98989D"),
                accent = Color.parseColor("#6B43F5"),
                toolBackground = Color.parseColor("#3A3A3C"),
                zoneTopStart = Color.parseColor("#1C2238"),
                zoneMidStart = Color.parseColor("#182820"),
                zoneBotStart = Color.parseColor("#302018"),
                isDark = true
            )
            "Pastel Mountains" -> ThemeColors(
                keyboardBackground = Color.parseColor("#F3E8EE"),
                keyBackground = Color.parseColor("#FFFFFF"),
                keyActive = Color.parseColor("#FCEBF3"),
                textTap = Color.parseColor("#4A3543"),
                textSwipe = Color.parseColor("#8A7382"),
                accent = Color.parseColor("#D8B4F8"),
                toolBackground = Color.parseColor("#EDE0E7"),
                zoneTopStart = Color.parseColor("#E8F0FE"),
                zoneMidStart = Color.parseColor("#E6F4EA"),
                zoneBotStart = Color.parseColor("#FCE8E6"),
                isDark = false
            )
            "Geo Grid" -> ThemeColors(
                keyboardBackground = Color.parseColor("#ECEFF1"),
                keyBackground = Color.parseColor("#FFFFFF"),
                keyActive = Color.parseColor("#CFD8DC"),
                textTap = Color.parseColor("#37474F"),
                textSwipe = Color.parseColor("#78909C"),
                accent = Color.parseColor("#00C853"),
                toolBackground = Color.parseColor("#E0E0E0"),
                zoneTopStart = Color.parseColor("#E1F5FE"),
                zoneMidStart = Color.parseColor("#E8F5E9"),
                zoneBotStart = Color.parseColor("#FFF3E0"),
                isDark = false
            )
            "Warm Bokeh" -> ThemeColors(
                keyboardBackground = Color.parseColor("#FAF5EC"),
                keyBackground = Color.parseColor("#FFFFFF"),
                keyActive = Color.parseColor("#F5ECD5"),
                textTap = Color.parseColor("#5D4037"),
                textSwipe = Color.parseColor("#8D6E63"),
                accent = Color.parseColor("#FBBC05"),
                toolBackground = Color.parseColor("#F1E6D2"),
                zoneTopStart = Color.parseColor("#ECEFF1"),
                zoneMidStart = Color.parseColor("#F1F8E9"),
                zoneBotStart = Color.parseColor("#FFF8E1"),
                isDark = false
            )
            "Liquid Silver" -> ThemeColors(
                keyboardBackground = Color.parseColor("#E3E8EC"),
                keyBackground = Color.parseColor("#F0F4F8"),
                keyActive = Color.parseColor("#D6E4F0"),
                textTap = Color.parseColor("#0F172A"),
                textSwipe = Color.parseColor("#64748B"),
                accent = Color.parseColor("#3B82F6"),
                toolBackground = Color.parseColor("#DCE2E7"),
                zoneTopStart = Color.parseColor("#E0F7FA"),
                zoneMidStart = Color.parseColor("#E8F5E9"),
                zoneBotStart = Color.parseColor("#FFF3E0"),
                isDark = false
            )
            "White Marble" -> ThemeColors(
                keyboardBackground = Color.parseColor("#F3F4F6"),
                keyBackground = Color.parseColor("#FFFFFF"),
                keyActive = Color.parseColor("#E5E7EB"),
                textTap = Color.parseColor("#111827"),
                textSwipe = Color.parseColor("#4B5563"),
                accent = Color.parseColor("#D4AF37"),
                toolBackground = Color.parseColor("#E5E7EB"),
                zoneTopStart = Color.parseColor("#F0F4F8"),
                zoneMidStart = Color.parseColor("#F0FFF4"),
                zoneBotStart = Color.parseColor("#FAF8F5"),
                isDark = false
            )
            "Frosted Glass" -> ThemeColors(
                keyboardBackground = Color.parseColor("#E5E7EB"),
                keyBackground = Color.parseColor("#F9FAFB"),
                keyActive = Color.parseColor("#E5E7EB"),
                textTap = Color.parseColor("#1F2937"),
                textSwipe = Color.parseColor("#6B7280"),
                accent = Color.parseColor("#2563EB"),
                toolBackground = Color.parseColor("#D1D5DB"),
                zoneTopStart = Color.parseColor("#EEF2F6"),
                zoneMidStart = Color.parseColor("#F0FDF4"),
                zoneBotStart = Color.parseColor("#FFFBEB"),
                isDark = false
            )
            "Blue" -> ThemeColors(
                keyboardBackground = Color.parseColor("#E3F2FD"),
                keyBackground = Color.parseColor("#FFFFFF"),
                keyActive = Color.parseColor("#BBDEFB"),
                textTap = Color.parseColor("#0D47A1"),
                textSwipe = Color.parseColor("#1E88E5"),
                accent = Color.parseColor("#1565C0"),
                toolBackground = Color.parseColor("#E3F2FD"),
                zoneTopStart = Color.parseColor("#E3F2FD"),
                zoneMidStart = Color.parseColor("#E8F5E9"),
                zoneBotStart = Color.parseColor("#FFF3E0"),
                isDark = false
            )
            "Red" -> ThemeColors(
                keyboardBackground = Color.parseColor("#FFEBEE"),
                keyBackground = Color.parseColor("#FFFFFF"),
                keyActive = Color.parseColor("#FFCDD2"),
                textTap = Color.parseColor("#B71C1C"),
                textSwipe = Color.parseColor("#E53935"),
                accent = Color.parseColor("#C62828"),
                toolBackground = Color.parseColor("#FFEBEE"),
                zoneTopStart = Color.parseColor("#E0F2F1"),
                zoneMidStart = Color.parseColor("#E8F5E9"),
                zoneBotStart = Color.parseColor("#FFEBEE"),
                isDark = false
            )
            "Light" -> ThemeColors(
                keyboardBackground = Color.parseColor("#F3F4F6"),
                keyBackground = Color.parseColor("#FFFFFF"),
                keyActive = Color.parseColor("#E5E7EB"),
                textTap = Color.parseColor("#111827"),
                textSwipe = Color.parseColor("#6B7280"),
                accent = Color.parseColor("#1D4ED8"),
                toolBackground = Color.parseColor("#E5E7EB"),
                zoneTopStart = Color.parseColor("#F0F4FF"),
                zoneMidStart = Color.parseColor("#F0FFF4"),
                zoneBotStart = Color.parseColor("#FFF7ED"),
                isDark = false
            )
            else -> // "Clean Minimal" or fallback
                ThemeColors(
                    keyboardBackground = Color.parseColor("#E2E2E7"),
                    keyBackground = Color.parseColor("#FFFFFF"),
                    keyActive = Color.parseColor("#CACAD0"),
                    textTap = Color.parseColor("#1D1D1F"),
                    textSwipe = Color.parseColor("#86868B"),
                    accent = Color.parseColor("#6B43F5"),
                    toolBackground = Color.parseColor("#D1D1D6"),
                    zoneTopStart = Color.parseColor("#F0F4FF"),
                    zoneMidStart = Color.parseColor("#F0FFF4"),
                    zoneBotStart = Color.parseColor("#FFF7ED"),
                    isDark = false
                )
        }
    }

    fun getZoneColors(context: Context, themeName: String, zoneType: KeyView.ZoneType, isSystemDark: Boolean): ZoneColors {
        val colors = getThemeColors(context, themeName, isSystemDark)
        val startColor = when (zoneType) {
            KeyView.ZoneType.TOP -> colors.zoneTopStart
            KeyView.ZoneType.MID -> colors.zoneMidStart
            KeyView.ZoneType.BOT -> colors.zoneBotStart
        }
        return ZoneColors(startColor, colors.keyBackground)
    }
}
