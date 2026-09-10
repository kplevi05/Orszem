package hu.orszembejelento.service.reports.domain

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import hu.orszembejelento.service.R
import hu.orszembejelento.service.reports.data.ReportDetailResponse
import hu.orszembejelento.service.reports.data.ReportListItemResponse
import hu.orszembejelento.service.ui.ServicePalette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Which of the two operational tabs on the Reports screen is active (brief §15). */
enum class ReportTab { NEW, IN_PROGRESS }

/**
 * The backend's `status` value, translated for display (brief §83) - never the raw enum
 * name. `UNCLASSIFIED` is not a real workflow status; it names the routing classification a
 * NEW/ARCHIVED report can carry (brief §37).
 */
@StringRes
fun statusLabelRes(status: String): Int = when (status) {
    "NEW" -> R.string.status_new
    "IN_PROGRESS" -> R.string.status_in_progress
    "ARCHIVED" -> R.string.status_archived
    else -> R.string.status_new
}

fun statusColor(status: String): Color = when (status) {
    "NEW" -> ServicePalette.StatusNew
    "IN_PROGRESS" -> ServicePalette.StatusInProgress
    "ARCHIVED" -> ServicePalette.StatusArchived
    else -> ServicePalette.StatusNew
}

/** True when a report's routing classification is UNCLASSIFIED (brief §37/§38). */
fun ReportListItemResponse.isUnclassified(): Boolean = routingClassification == "UNCLASSIFIED"
fun ReportDetailResponse.isUnclassified(): Boolean = routingClassification == "UNCLASSIFIED"

/**
 * Formats a backend ISO-8601 instant for human display.
 *
 * The backend timestamp itself is always authoritative for ordering/bucketing (brief §84) -
 * this only affects how the *same* value is shown, never what it means. Falls back to the
 * raw string if it cannot be parsed, so a display quirk never hides real data.
 */
fun formatInstant(raw: String): String = runCatching {
    val instant = Instant.parse(raw)
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(java.util.Locale.forLanguageTag("hu-HU"))
        .withZone(ZoneId.systemDefault())
        .format(instant)
}.getOrDefault(raw)

/** A short, presentational shortened form of a report's public id (brief §19) - never sent to the backend, never assumed unique. */
fun shortReportId(publicReportId: String): String =
    "#" + publicReportId.take(8).uppercase(java.util.Locale.ROOT)
