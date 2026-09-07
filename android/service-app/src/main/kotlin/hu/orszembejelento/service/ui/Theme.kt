package hu.orszembejelento.service.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ServiceColorScheme = darkColorScheme(
    primary = ServicePalette.Accent,
    onPrimary = ServicePalette.OnAccent,
    primaryContainer = ServicePalette.AccentPressed,
    onPrimaryContainer = ServicePalette.OnAccent,
    secondary = ServicePalette.SurfaceAlt,
    onSecondary = ServicePalette.Text,
    background = ServicePalette.Background,
    onBackground = ServicePalette.Text,
    surface = ServicePalette.Surface,
    onSurface = ServicePalette.Text,
    surfaceVariant = ServicePalette.SurfaceAlt,
    onSurfaceVariant = ServicePalette.TextMuted,
    error = ServicePalette.Error,
    onError = ServicePalette.OnError,
)

/**
 * The Service app is always dark, regardless of the device setting: it is an operational
 * tool used trackside and at night, and a consistent high-contrast surface matters more
 * than matching the user's phone theme.
 */
@Composable
fun OrszemServiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ServiceColorScheme,
        typography = Typography(),
        content = content,
    )
}
