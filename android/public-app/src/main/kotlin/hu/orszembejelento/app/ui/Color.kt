package hu.orszembejelento.app.ui

import androidx.compose.ui.graphics.Color

/**
 * Public Őrszem identity: calm, light, civilian-facing.
 *
 * These tokens are the single source of truth for the palette, deliberately aligned to the
 * approved UI mockup (orszem-public-ui-concept-v1-corrected.html, 2026-09-08) - the same
 * values the Public Web client's `styles.css` uses, so the two clients read as one product.
 * `themes.xml` mirrors only the two values Android needs before Compose starts (status bar
 * and window background) and must be kept in step with them.
 */
internal object PublicPalette {
    val Background = Color(0xFFEEF3F8)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceTint = Color(0xFFF8FBFF)
    val Primary = Color(0xFF1F5B93)
    val PrimaryDark = Color(0xFF123E69)
    val PrimarySoft = Color(0xFFEDF4FB)
    val PrimarySoftBorder = Color(0xFFD5E4F3)
    val PrimaryContainer = Color(0xFF6BA9D6)
    val OnPrimary = Color(0xFFFFFFFF)
    val Text = Color(0xFF16202F)
    val TextMuted = Color(0xFF6F7B8A)
    val Border = Color(0xFFDBE4EE)

    val SuccessBg = Color(0xFFE8F6ED)
    val SuccessText = Color(0xFF2D8B5A)
    val WarningBg = Color(0xFFFFF6DD)
    val WarningText = Color(0xFF9C6C00)
    val InfoBg = Color(0xFFE8F2FF)
    val InfoText = Color(0xFF175AA0)
    val ErrorBg = Color(0xFFFDECEA)
    val ErrorText = Color(0xFFB3261E)

    /** Kept as aliases so existing call sites (MaterialTheme.colorScheme.error, etc.) resolve. */
    val Success = SuccessText
    val Error = ErrorText
    val OnError = Color(0xFFFFFFFF)
}
