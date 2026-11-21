package org.updater.android.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shape and type carry hierarchy alongside the wallpaper-derived colour scheme. */
private val AppShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = RoundedCornerShape(36.dp),
    )

private val BaseType = Typography()
private val AppTypography =
    Typography(
        displaySmall =
            BaseType.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        headlineLarge =
            BaseType.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.75).sp),
        headlineMedium = BaseType.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = BaseType.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = BaseType.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = BaseType.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = BaseType.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = BaseType.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )

// Used where the system has no palette of its own to lend, below Android 12.
private val Indigo = Color(0xFF4F5DDA)
private val IndigoDark = Color(0xFFBEC2FF)
private val Teal = Color(0xFF2E7D6B)
private val TealDark = Color(0xFF7FD7C2)
private val Amber = Color(0xFF8A5A00)
private val AmberDark = Color(0xFFF4BF63)

private val LightScheme =
    lightColorScheme(
        primary = Indigo,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE1E0FF),
        onPrimaryContainer = Color(0xFF0B0A5C),
        secondary = Teal,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFB9F0E0),
        onSecondaryContainer = Color(0xFF00201A),
        tertiary = Amber,
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFDEA6),
        onTertiaryContainer = Color(0xFF2B1700),
        background = Color(0xFFFBF8FF),
        surface = Color(0xFFFBF8FF),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF5F2FC),
        surfaceContainer = Color(0xFFEFEDF7),
        surfaceContainerHigh = Color(0xFFE9E7F1),
        surfaceContainerHighest = Color(0xFFE3E1EC),
        onSurface = Color(0xFF1B1B21),
        onSurfaceVariant = Color(0xFF47464F),
        outlineVariant = Color(0xFFC8C5D0),
    )

private val DarkScheme =
    darkColorScheme(
        primary = IndigoDark,
        onPrimary = Color(0xFF1D2578),
        primaryContainer = Color(0xFF353D91),
        onPrimaryContainer = Color(0xFFE1E0FF),
        secondary = TealDark,
        onSecondary = Color(0xFF00382E),
        secondaryContainer = Color(0xFF115043),
        onSecondaryContainer = Color(0xFFB9F0E0),
        tertiary = AmberDark,
        onTertiary = Color(0xFF472A00),
        tertiaryContainer = Color(0xFF663D00),
        onTertiaryContainer = Color(0xFFFFDEA6),
        background = Color(0xFF121318),
        surface = Color(0xFF121318),
        surfaceContainerLowest = Color(0xFF0D0E13),
        surfaceContainerLow = Color(0xFF1A1B21),
        surfaceContainer = Color(0xFF1E1F25),
        surfaceContainerHigh = Color(0xFF292A30),
        surfaceContainerHighest = Color(0xFF34353B),
        onSurface = Color(0xFFE4E1E9),
        onSurfaceVariant = Color(0xFFC8C5D0),
        outlineVariant = Color(0xFF47464F),
    )

/** Colours that carry a meaning the palette does not, and so are not themed away. */
object StatusColors {
    val resolved: Color
        @Composable get() = if (isDarkPalette()) Color(0xFF7BD88F) else Color(0xFF1E7B34)

    val ambiguous: Color
        @Composable get() = if (isDarkPalette()) Color(0xFFF4BF63) else Color(0xFF8A5A00)

    val failed: Color
        @Composable get() = if (isDarkPalette()) Color(0xFFFF8A80) else Color(0xFFB3261E)
}

@Composable
private fun isDarkPalette(): Boolean =
    androidx.compose.material3.MaterialTheme.colorScheme.surface.luminance() < 0.5f

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdaterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme =
        when {
            // Android 12 onwards lends the app the wallpaper's palette.
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            darkTheme -> DarkScheme
            else -> LightScheme
        }

    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
