package hu.orszembejelento.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val PublicColorScheme = lightColorScheme(
    primary = PublicPalette.Primary,
    onPrimary = PublicPalette.OnPrimary,
    primaryContainer = PublicPalette.PrimarySoft,
    onPrimaryContainer = PublicPalette.PrimaryDark,
    secondary = PublicPalette.PrimaryContainer,
    background = PublicPalette.Background,
    onBackground = PublicPalette.Text,
    surface = PublicPalette.Surface,
    onSurface = PublicPalette.Text,
    surfaceVariant = PublicPalette.SurfaceTint,
    onSurfaceVariant = PublicPalette.TextMuted,
    outline = PublicPalette.Border,
    outlineVariant = PublicPalette.Border,
    error = PublicPalette.ErrorText,
    onError = PublicPalette.OnError,
    errorContainer = PublicPalette.ErrorBg,
    onErrorContainer = PublicPalette.ErrorText,
)

/**
 * Bolder, tighter headline weights than Material3's defaults - deliberately aligned to the
 * approved mockup's "Mit szeretne tenni?" / "Bejelentés elküldve" treatment (heavy display
 * type over calm body copy), not Material's default Roboto text scale.
 */
private val PublicTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.3).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.2).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold),
    )
}

internal val StatusPillTextStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp)

/**
 * The Public app is deliberately light-only: a citizen-facing reporting tool that should
 * look identical to every user, rather than following the device theme.
 */
@Composable
fun OrszemPublicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PublicColorScheme,
        typography = PublicTypography,
        content = content,
    )
}
