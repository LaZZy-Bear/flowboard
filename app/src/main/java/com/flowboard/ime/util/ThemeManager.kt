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
            themeName == "System default" -> if (isSystemDark) "Dark" else "Light"
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
                accent = "#60A5FA".toColorInt(),
                toolBackground = "#3A3A3C".toColorInt(),
                zoneTopStart = "#1C2238".toColorInt(),
                zoneMidStart = "#182820".toColorInt(),
                zoneBotStart = "#302018".toColorInt(),
                isDark = true
            )
            "Ocean Blue", "Blue" -> ThemeColors(
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
            "Mint Teal", "Geo Grid", "Teal" -> ThemeColors(
                keyboardBackground = "#E0F2F1".toColorInt(),
                keyBackground = "#FFFFFF".toColorInt(),
                keyActive = "#B2DFDB".toColorInt(),
                textTap = "#004D40".toColorInt(),
                textSwipe = "#00897B".toColorInt(),
                accent = "#00796B".toColorInt(),
                toolBackground = "#E0F2F1".toColorInt(),
                zoneTopStart = "#E0F2F1".toColorInt(),
                zoneMidStart = "#E8F5E9".toColorInt(),
                zoneBotStart = "#FFF3E0".toColorInt(),
                isDark = false
            )
            "Sunset Coral", "Warm Bokeh", "Coral" -> ThemeColors(
                keyboardBackground = "#FBE9E7".toColorInt(),
                keyBackground = "#FFFFFF".toColorInt(),
                keyActive = "#FFCCBC".toColorInt(),
                textTap = "#BF360C".toColorInt(),
                textSwipe = "#F4511E".toColorInt(),
                accent = "#E64A19".toColorInt(),
                toolBackground = "#FBE9E7".toColorInt(),
                zoneTopStart = "#FBE9E7".toColorInt(),
                zoneMidStart = "#FFF3E0".toColorInt(),
                zoneBotStart = "#FFEBEE".toColorInt(),
                isDark = false
            )
            "Sakura Bloom", "Sakura Pink", "Sakura" -> ThemeColors(
                keyboardBackground = "#FDF2F4".toColorInt(),
                keyBackground = "#FFFFFF".toColorInt(),
                keyActive = "#FCE7EC".toColorInt(),
                textTap = "#831843".toColorInt(),
                textSwipe = "#DB2777".toColorInt(),
                accent = "#EC4899".toColorInt(),
                toolBackground = "#FCE7EC".toColorInt(),
                zoneTopStart = "#FFF1F2".toColorInt(),
                zoneMidStart = "#FDF2F8".toColorInt(),
                zoneBotStart = "#FFF0F5".toColorInt(),
                isDark = false
            )
            "Light", "Clean Minimal" -> ThemeColors(
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
            else -> // Fallback to Clean Minimal Light
                ThemeColors(
                    keyboardBackground = "#E2E2E7".toColorInt(),
                    keyBackground = "#FFFFFF".toColorInt(),
                    keyActive = "#CACAD0".toColorInt(),
                    textTap = "#1D1D1F".toColorInt(),
                    textSwipe = "#86868B".toColorInt(),
                    accent = "#2563EB".toColorInt(),
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
