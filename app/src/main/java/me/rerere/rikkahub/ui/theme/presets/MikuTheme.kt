package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetTheme

const val MIKU_THEME_ID = "miku"
const val MIKU_TEAL_ARGB = 0xFF39C5BB

val MikuThemePreset by lazy {
    val palette = CustomTheme(primaryColorArgb = MIKU_TEAL_ARGB)
    PresetTheme(
        id = MIKU_THEME_ID,
        name = { Text("初音青绿") },
        standardLight = palette.generateColorScheme(dark = false).copy(
            // Darker teal keeps text/icons legible on light surfaces; containers retain the hair color.
            primary = Color(0xFF007A73),
            onPrimary = Color.White,
            primaryContainer = Color(MIKU_TEAL_ARGB),
            onPrimaryContainer = Color(0xFF003733),
            surfaceTint = Color(0xFF007A73),
        ),
        standardDark = palette.generateColorScheme(dark = true).copy(
            primary = Color(MIKU_TEAL_ARGB),
            onPrimary = Color(0xFF003733),
            surfaceTint = Color(MIKU_TEAL_ARGB),
        ),
    )
}
