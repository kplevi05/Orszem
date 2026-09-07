package hu.orszembejelento.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val PublicColorScheme = lightColorScheme(
    primary = PublicPalette.Primary,
    onPrimary = PublicPalette.OnPrimary,
    primaryContainer = PublicPalette.PrimaryContainer,
    onPrimaryContainer = PublicPalette.PrimaryDark,
    secondary = PublicPalette.PrimaryContainer,
    background = PublicPalette.Background,
    onBackground = PublicPalette.Text,
    surface = PublicPalette.Surface,
    onSurface = PublicPalette.Text,
    onSurfaceVariant = PublicPalette.TextMuted,
    error = PublicPalette.Error,
    onError = PublicPalette.OnError,
)

/**
 * The Public app is deliberately light-only: a citizen-facing reporting tool that should
 * look identical to every user, rather than following the device theme.
 */
@Composable
fun OrszemPublicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PublicColorScheme,
        typography = Typography(),
        content = content,
    )
}
