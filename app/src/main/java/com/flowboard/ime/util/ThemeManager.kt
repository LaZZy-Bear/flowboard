package com.flowboard.ime.util

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.toColorInt
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
    fun getThemeColors(@Suppress("UNUSED_PARAMETER") context: Context, themeName: String, isSystemDark: Boolean): ThemeColors {
        val resolvedTheme = when {
            themeName == "System default" -> if (isSystemDark) "Dark" else "Clean Minimal"
            themeName == "Clean Minimal" && isSystemDark -> "Dark"
            else -> themeName
        }

        return when (resolvedTheme) {
            "Dark" -> ThemeColors(
                keyboardBackground = "#1C1C1E".toColorInt(),
                keyBackground = "#2C2C2E".toColorInt(),
                keyActive = "#3A3A3C".toColorInt(),
                textTap = "#FFFFFF".toColorInt(),
                textSwipe = "#98989D".toColorInt(),
                accent = "#6B43F5".toColorInt(),
                toolBackground = "#3A3A3C".toColorInt(),
                zoneTopStart = "#1C2238".toColorInt(),
                zoneMidStart = "#182820".toColorInt(),
                zoneBotStart = "#302018".toColorInt(),
                isDark = true
            )
            "Pastel Mountains" -> ThemeColors(
                keyboardBackground = "#F3E8EE".toColorInt(),
                keyBackground = "#FFFFFF".toColorInt(),
                keyActive = "#FCEBF3".toColorInt(),
                textTap = "#4A3543".toColorInt(),
                textSwipe = "#8A7382".toColorInt(),
                accent = "#D8B4F8".toColorInt(),
                toolBackground = "#EDE0E7".toColorInt(),
                zoneTopStart = "#E8F0FE".toColorInt(),
                zoneMidStart = "#E6F4EA".toColorInt(),
                zoneBotStart = "#FCE8E6".toColorInt(),
                isDark = false
            )
            "Geo Grid" -> ThemeColors(
                keyboardBackground = "#ECEFF1".toColorInt(),
                keyBackground = "#FFFFFF".toColorInt(),
                keyActive = "#CFD8DC".toColorInt(),
                textTap = "#37474F".toColorInt(),
                textSwipe = "#78909C".toColorInt(),
                accent = "#00C853".toColorInt(),
                toolBackground = "#E0E0E0".toColorInt(),
                zoneTopStart = "#E1F5FE".toColorInt(),
                zoneMidStart = "#E8F5E9".toColorInt(),
                zoneBotStart = "#FFF3E0".toColorInt(),
                isDark = false
            )
            "Warm Bokeh" -> ThemeColors(
                keyboardBackground = "#FAF5EC".toColorInt(),
                keyBackground = "#FFFFFF".toColorInt(),
                keyActive = "#F5ECD5".toColorInt(),
                textTap = "#5D4037".toColorInt(),
                textSwipe = "#8D6E63".toColorInt(),
                accent = "#FBBC05".toColorInt(),
                toolBackground = "#F1E6D2".toColorInt(),
                zoneTopStart = "#ECEFF1".toColorInt(),
                zoneMidStart = "#F1F8E9".toColorInt(),
                zoneBotStart = "#FFF8E1".toColorInt(),
                isDark = false
            )
            "Liquid Silver" -> ThemeColors(
                keyboardBackground = "#E3E8EC".toColorInt(),
                keyBackground = "#F0F4F8".toColorInt(),
                keyActive = "#D6E4F0".toColorInt(),
                textTap = "#0F172A".toColorInt(),
                textSwipe = "#64748B".toColorInt(),
                accent = "#3B82F6".toColorInt(),
                toolBackground = "#DCE2E7".toColorInt(),
                zoneTopStart = "#E0F7FA".toColorInt(),
                zoneMidStart = "#E8F5E9".toColorInt(),
                zoneBotStart = "#FFF3E0".toColorInt(),
                isDark = false
            )
            "White Marble" -> ThemeColors(
                keyboardBackground = "#F3F4F6".toColorInt(),
                keyBackground = "#FFFFFF".toColorInt(),
                keyActive = "#E5E7EB".toColorInt(),
                textTap = "#111827".toColorInt(),
                textSwipe = "#4B5563".toColorInt(),
                accent = "#D4AF37".toColorInt(),
                toolBackground = "#E5E7EB".toColorInt(),
                zoneTopStart = "#F0F4F8".toColorInt(),
                zoneMidStart = "#F0FFF4".toColorInt(),
                zoneBotStart = "#FAF8F5".toColorInt(),
                isDark = false
            )
            "Frosted Glass" -> ThemeColors(
                keyboardBackground = "#E5E7EB".toColorInt(),
                keyBackground = "#F9FAFB".toColorInt(),
                keyActive = "#E5E7EB".toColorInt(),
                textTap = "#1F2937".toColorInt(),
                textSwipe = "#6B7280".toColorInt(),
                accent = "#2563EB".toColorInt(),
                toolBackground = "#D1D5DB".toColorInt(),
                zoneTopStart = "#EEF2F6".toColorInt(),
                zoneMidStart = "#F0FDF4".toColorInt(),
                zoneBotStart = "#FFFBEB".toColorInt(),
                isDark = false
            )
            "Blue" -> ThemeColors(
                keyboardBackground = "#E3F2FD".toColorInt(),
                keyBackground = "#FFFFFF".toColorInt(),
                keyActive = "#BBDEFB".toColorInt(),
                textTap = "#0D47A1".toColorInt(),
                textSwipe = "#1E88E5".toColorInt(),
                accent = "#1565C0".toColorInt(),
                toolBackground = "#E3F2FD".toColorInt(),
                zoneTopStart = "#E3F2FD".toColorInt(),
                zoneMidStart = "#E8F5E9".toColorInt(),
                zoneBotStart = "#FFF3E0".toColorInt(),
                isDark = false
            )
            "Red" -> ThemeColors(
                keyboardBackground = "#FFEBEE".toColorInt(),
                keyBackground = "#FFFFFF".toColorInt(),
                keyActive = "#FFCDD2".toColorInt(),
                textTap = "#B71C1C".toColorInt(),
                textSwipe = "#E53935".toColorInt(),
                accent = "#C62828".toColorInt(),
                toolBackground = "#FFEBEE".toColorInt(),
                zoneTopStart = "#E0F2F1".toColorInt(),
                zoneMidStart = "#E8F5E9".toColorInt(),
                zoneBotStart = "#FFEBEE".toColorInt(),
                isDark = false
            )
            "Light" -> ThemeColors(
                keyboardBackground = "#F3F4F6".toColorInt(),
                keyBackground = "#FFFFFF".toColorInt(),
                keyActive = "#E5E7EB".toColorInt(),
                textTap = "#111827".toColorInt(),
                textSwipe = "#6B7280".toColorInt(),
                accent = "#1D4ED8".toColorInt(),
                toolBackground = "#E5E7EB".toColorInt(),
                zoneTopStart = "#F0F4FF".toColorInt(),
                zoneMidStart = "#F0FFF4".toColorInt(),
                zoneBotStart = "#FFF7ED".toColorInt(),
                isDark = false
            )
            else -> // "Clean Minimal" or fallback
                ThemeColors(
                    keyboardBackground = "#E2E2E7".toColorInt(),
                    keyBackground = "#FFFFFF".toColorInt(),
                    keyActive = "#CACAD0".toColorInt(),
                    textTap = "#1D1D1F".toColorInt(),
                    textSwipe = "#86868B".toColorInt(),
                    accent = "#6B43F5".toColorInt(),
                    toolBackground = "#D1D1D6".toColorInt(),
                    zoneTopStart = "#F0F4FF".toColorInt(),
                    zoneMidStart = "#F0FFF4".toColorInt(),
                    zoneBotStart = "#FFF7ED".toColorInt(),
                    isDark = false
                )
        }
    }

    @Suppress("unused")
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
