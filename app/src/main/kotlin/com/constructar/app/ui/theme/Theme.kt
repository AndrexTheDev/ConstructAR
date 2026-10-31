package com.constructar.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ConstructAR theme: high contrast, oversized type, gloves-first ergonomics.
 *
 * Design constraints that come straight from the job site:
 *  * the phone is often used one-handed with work gloves on — every interactive element is at
 *    least 64 dp tall (Material's 48 dp is too small with gloves), and the primary actions sit
 *    in the bottom third of the screen where a thumb reaches without regripping;
 *  * the screen is read in direct sunlight or in a dark basement — the palette is dark with
 *    saturated accents, never mid-grey on mid-grey;
 *  * numbers matter more than labels, so measurements are set in a large tabular style.
 */
private val SiteOrange = Color(0xFFFF6B00)
private val SiteOrangeDim = Color(0xFFB34A00)
private val PipeBlue = Color(0xFF2E9BFF)
private val DuctTeal = Color(0xFF15C39A)
private val SurfaceDark = Color(0xFF121418)
private val SurfaceDarkElevated = Color(0xFF1C1F25)
private val OnDark = Color(0xFFF2F4F7)
private val DangerRed = Color(0xFFFF5252)
private val WarningAmber = Color(0xFFFFC107)

private val DarkScheme = darkColorScheme(
    primary = SiteOrange,
    onPrimary = Color.Black,
    primaryContainer = SiteOrangeDim,
    onPrimaryContainer = Color.White,
    secondary = PipeBlue,
    onSecondary = Color.Black,
    tertiary = DuctTeal,
    onTertiary = Color.Black,
    background = SurfaceDark,
    onBackground = OnDark,
    surface = SurfaceDarkElevated,
    onSurface = OnDark,
    surfaceVariant = Color(0xFF2A2F36),
    onSurfaceVariant = Color(0xFFC6CBD3),
    error = DangerRed,
    onError = Color.Black,
)

private val LightScheme = lightColorScheme(
    primary = SiteOrangeDim,
    onPrimary = Color.White,
    secondary = PipeBlue,
    tertiary = DuctTeal,
    error = DangerRed,
)

/** Type scale: roughly 1.4× Material's default, which is what gloves and sunlight need. */
private val GloveTypography = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
)

/** Extra semantic colours the Material scheme does not carry. */
object SiteColors {
    val warning: Color = WarningAmber
    val danger: Color = DangerRed
    val success: Color = DuctTeal
    val cable: Color = Color(0xFFFF8A00)
    val pipe: Color = PipeBlue
    val duct: Color = DuctTeal

    /** Minimum touch target for a gloved finger. */
    val gloveTouchTarget = 64.dp
}

@Composable
fun ConstructArTheme(
    darkTheme: Boolean = isSystemInDarkTheme() || true, // the AR screen always needs dark chrome
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = GloveTypography,
        content = content,
    )
}
