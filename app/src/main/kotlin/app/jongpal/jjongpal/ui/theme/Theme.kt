package app.jongpal.jjongpal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// 쫑팔이삼촌 디자인 시스템 (ds.css 포팅)
// 따뜻한 종이 톤 + 테라코타 포인트. 라이트/다크 한 쌍.

data class JpColors(
    val bg: Color,
    val bg2: Color,
    val surface: Color,
    val surface2: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val lineStrong: Color,
    val accent: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val sage: Color,
    val sageSoft: Color,
    val danger: Color,
    val isDark: Boolean,
)

private val LightJpColors = JpColors(
    bg = Color(0xFFECE3D2),
    bg2 = Color(0xFFE3D8C2),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF6EFE2),
    ink = Color(0xFF2A2620),
    ink2 = Color(0xFF756B5B),
    ink3 = Color(0xFFA99D89),
    line = Color(0xFFE7DDCA),
    lineStrong = Color(0xFFD9CCB3),
    accent = Color(0xFFCC6B2C),
    accentInk = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFF6E6DB),
    sage = Color(0xFF4E7C5A),
    sageSoft = Color(0xFFE1ECDF),
    danger = Color(0xFFC0492F),
    isDark = false,
)

private val DarkJpColors = JpColors(
    bg = Color(0xFF17120C),
    bg2 = Color(0xFF120E09),
    surface = Color(0xFF241D14),
    surface2 = Color(0xFF2D2517),
    ink = Color(0xFFF1E9DA),
    ink2 = Color(0xFFAC9F89),
    ink3 = Color(0xFF7C7160),
    line = Color(0xFF322817),
    lineStrong = Color(0xFF443823),
    accent = Color(0xFFCC6B2C),
    accentInk = Color(0xFFFFFFFF),
    accentSoft = Color(0xFF3F2613),
    sage = Color(0xFF84B68E),
    sageSoft = Color(0xFF20301F),
    danger = Color(0xFFE0816C),
    isDark = true,
)

val LocalJpColors = staticCompositionLocalOf { LightJpColors }

private fun JpColors.toMaterialScheme() = if (isDark) {
    darkColorScheme(
        primary = accent,
        onPrimary = accentInk,
        background = bg,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = surface2,
        onSurfaceVariant = ink2,
        outline = line,
        error = danger,
    )
} else {
    lightColorScheme(
        primary = accent,
        onPrimary = accentInk,
        background = bg,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = surface2,
        onSurfaceVariant = ink2,
        outline = line,
        error = danger,
    )
}

object JpTheme {
    val colors: JpColors
        @Composable @ReadOnlyComposable
        get() = LocalJpColors.current
}

@Composable
fun JjongpalTheme(useDarkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val jp = if (useDarkTheme) DarkJpColors else LightJpColors
    CompositionLocalProvider(LocalJpColors provides jp) {
        MaterialTheme(
            colorScheme = jp.toMaterialScheme(),
            typography = JpTypography,
            content = content,
        )
    }
}
