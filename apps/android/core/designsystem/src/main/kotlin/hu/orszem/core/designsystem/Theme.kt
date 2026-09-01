package hu.orszem.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// --- Public App: light blue design system (DEMO_V1_SCREENS §1.1) ---
private val PublicBackground = Color(0xFFF4F8FC)
private val PublicSurface = Color(0xFFFFFFFF)
private val PublicPrimary = Color(0xFF2477B9)
private val PublicPrimaryDark = Color(0xFF0D3B66)
private val PublicSecondary = Color(0xFF6BA9D6)
private val PublicText = Color(0xFF12212F)
private val PublicTextMuted = Color(0xFF5A6B7A)
val PublicSuccess = Color(0xFF2E7D5A)
val PublicError = Color(0xFFB3261E)

private val PublicColors = lightColorScheme(
    primary = PublicPrimary,
    onPrimary = Color.White,
    primaryContainer = PublicSecondary,
    onPrimaryContainer = PublicPrimaryDark,
    secondary = PublicSecondary,
    onSecondary = Color.White,
    background = PublicBackground,
    onBackground = PublicText,
    surface = PublicSurface,
    onSurface = PublicText,
    surfaceVariant = PublicBackground,
    onSurfaceVariant = PublicTextMuted,
    error = PublicError,
    onError = Color.White,
)

// --- Service App: dark navy + restrained dark-yellow accent (DEMO_V1_SCREENS §1.2) ---
private val ServiceBackground = Color(0xFF081A2B)
private val ServiceSurface = Color(0xFF102A43)
private val ServiceSurfaceAlt = Color(0xFF173B59)
private val ServiceAccent = Color(0xFFD6B82C)
private val ServiceAccentPressed = Color(0xFFB89A1F)
private val ServiceText = Color(0xFFF4F7FA)
private val ServiceTextMuted = Color(0xFFB5C3CE)
val ServiceError = Color(0xFFFFB4AB)
val ServiceSuccess = Color(0xFF91D5B0)

private val ServiceColors = darkColorScheme(
    primary = ServiceAccent,
    onPrimary = Color(0xFF201B00),
    primaryContainer = ServiceAccentPressed,
    onPrimaryContainer = Color(0xFF201B00),
    secondary = ServiceSurfaceAlt,
    onSecondary = ServiceText,
    background = ServiceBackground,
    onBackground = ServiceText,
    surface = ServiceSurface,
    onSurface = ServiceText,
    surfaceVariant = ServiceSurfaceAlt,
    onSurfaceVariant = ServiceTextMuted,
    error = ServiceError,
    onError = Color(0xFF3B0906),
)

private val AppTypography = Typography()

@Composable
fun OrszemPublicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PublicColors, typography = AppTypography, content = content)
}

@Composable
fun OrszemServiceTheme(content: @Composable () -> Unit) {
    // The Service App is intentionally dark regardless of the system setting.
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = ServiceColors, typography = AppTypography, content = content)
}
