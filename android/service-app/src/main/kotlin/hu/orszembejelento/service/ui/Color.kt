package hu.orszembejelento.service.ui

import androidx.compose.ui.graphics.Color

/**
 * Service Őrszem identity: dark navy ground, restrained gold accent, operational and
 * readable in poor light and on low-quality screens.
 *
 * These tokens are the single source of truth for the palette. `themes.xml` mirrors only
 * the two values Android needs before Compose starts and must be kept in step with them.
 */
internal object ServicePalette {
    val Background = Color(0xFF081A2B)
    val Surface = Color(0xFF102A43)
    val SurfaceAlt = Color(0xFF173B59)
    val Accent = Color(0xFFD6B82C)
    val AccentPressed = Color(0xFFB89A1F)
    val OnAccent = Color(0xFF201B00)
    val Text = Color(0xFFF4F7FA)
    val TextMuted = Color(0xFFB5C3CE)
    val Success = Color(0xFF91D5B0)
    val Error = Color(0xFFFFB4AB)
    val OnError = Color(0xFF3B0906)

    // Status-chip semantics (brief §83) - distinct from the primary accent so gold stays
    // reserved for primary actions/highlight, not full-screen or all-status decoration.
    val StatusNew = Color(0xFFF0CF4B)
    val StatusInProgress = Color(0xFF8DC5EF)
    val StatusArchived = Color(0xFFAEBDCA)
    val StatusUnclassified = Color(0xFFF0CF4B)
}
