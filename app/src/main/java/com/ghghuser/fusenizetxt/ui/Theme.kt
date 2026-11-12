package com.ghghuser.fusenizetxt.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

@Composable
fun FusenTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 指定した背景色に連動する配色（暗い背景→明るい文字、明るい背景→濃い文字。チップや枠線の色も背景に合わせる） */
fun tintedScheme(bg: Color): ColorScheme {
    val dark = bg.luminance() < 0.4f
    val fg = if (dark) Color(0xFFF2F2F2) else Color(0xFF161616)
    return (if (dark) darkColorScheme() else lightColorScheme()).copy(
        surface = bg, background = bg, onSurface = fg, onBackground = fg,
        onSurfaceVariant = fg.copy(alpha = 0.85f), surfaceVariant = bg,
        primary = if (dark) Color(0xFF9FC2FF) else Color(0xFF0B4DB0),
        onPrimary = if (dark) Color(0xFF10131A) else Color.White,
        secondaryContainer = lerp(bg, fg, 0.22f), onSecondaryContainer = fg,
        outline = fg.copy(alpha = 0.55f), outlineVariant = fg.copy(alpha = 0.28f),
    )
}
