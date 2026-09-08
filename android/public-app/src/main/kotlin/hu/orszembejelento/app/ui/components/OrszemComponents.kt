package hu.orszembejelento.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hu.orszembejelento.app.ui.PublicPalette

/**
 * Shared presentation-only building blocks used to align every Public screen with the
 * approved UI mockup (orszem-public-ui-concept-v1-corrected.html, 2026-09-08): rounded
 * "soft" cards, a status pill (icon + text + colour, never colour alone - Phase 5 owner
 * requirement that PENDING/CONFLICT/ACCESS_LOST stay distinguishable without colour vision),
 * a badge chip and a two-step progress indicator. No business logic lives here.
 */

/** The mockup's rounded white card with a soft shadow, used everywhere a `.card` appears on Web. */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    containerColor: Color = PublicPalette.Surface,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, PublicPalette.Border),
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.padding(18.dp), content = content)
    }
}

/** A small rounded chip, e.g. the Home screen's "Gyors műveletek" badge. */
@Composable
fun BadgeChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(PublicPalette.PrimarySoft)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = PublicPalette.Primary, style = MaterialTheme.typography.labelLarge)
    }
}

enum class PillTone { INFO, WARNING, SUCCESS, ERROR }

/**
 * A status pill that differentiates states by icon + text + colour together (not colour
 * alone), for report states such as SUBMITTED/PENDING/CONFLICT/ACCESS_LOST.
 */
@Composable
fun StatusPill(text: String, tone: PillTone, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val (bg, fg) = when (tone) {
        PillTone.INFO -> PublicPalette.InfoBg to PublicPalette.InfoText
        PillTone.WARNING -> PublicPalette.WarningBg to PublicPalette.WarningText
        PillTone.SUCCESS -> PublicPalette.SuccessBg to PublicPalette.SuccessText
        PillTone.ERROR -> PublicPalette.ErrorBg to PublicPalette.ErrorText
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

/** A soft, informational callout box - the mockup's `.help-card`, e.g. the auto-identified railway line. */
@Composable
fun HelpCard(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFEEF6FF))
            .border(1.dp, Color(0xFFD7E8FB), RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Text(text, color = PublicPalette.Primary, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The two-cell "1. Alapadatok / 2. Esemény" progress indicator shown above the report form. */
@Composable
fun StepIndicator(steps: List<String>, activeIndex: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        steps.forEachIndexed { index, label ->
            val active = index == activeIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) PublicPalette.Primary else PublicPalette.Surface)
                    .border(1.dp, if (active) Color.Transparent else PublicPalette.Border, RoundedCornerShape(14.dp))
                    .padding(vertical = 12.dp),
            ) {
                Text(
                    label,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = if (active) PublicPalette.OnPrimary else PublicPalette.TextMuted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** A round icon button matching the mockup's `.icon-button` (close/back affordance on the report screen). */
@Composable
fun RoundIconButton(icon: ImageVector, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(PublicPalette.Surface)
            .border(1.dp, PublicPalette.Border, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = PublicPalette.Primary, modifier = Modifier.size(20.dp))
    }
}

/** A green circular checkmark badge for the success screen. */
@Composable
fun SuccessCheckBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(PublicPalette.SuccessText),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(32.dp),
        )
    }
}

/** Equal-width two-up row, e.g. Home's "Új bejelentés" / "Előzmények" quick-action cards. */
@Composable
fun QuickGrid(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}
