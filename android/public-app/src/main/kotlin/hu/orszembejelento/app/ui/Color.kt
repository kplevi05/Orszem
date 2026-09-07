package hu.orszembejelento.app.ui

import androidx.compose.ui.graphics.Color

/**
 * Public Őrszem identity: calm, light, civilian-facing.
 *
 * These tokens are the single source of truth for the palette. `themes.xml` mirrors only
 * the two values Android needs before Compose starts (status bar and window background)
 * and must be kept in step with them.
 */
internal object PublicPalette {
    val Background = Color(0xFFF4F8FC)
    val Surface = Color(0xFFFFFFFF)
    val Primary = Color(0xFF2477B9)
    val PrimaryDark = Color(0xFF0D3B66)
    val PrimaryContainer = Color(0xFF6BA9D6)
    val OnPrimary = Color(0xFFFFFFFF)
    val Text = Color(0xFF12212F)
    val TextMuted = Color(0xFF5A6B7A)
    val Success = Color(0xFF2E7D5A)
    val Error = Color(0xFFB3261E)
    val OnError = Color(0xFFFFFFFF)
}
