package hu.orszembejelento.service.analytics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import hu.orszembejelento.service.R
import hu.orszembejelento.service.analytics.data.AnalyticsTrendPointResponse
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy. MMMM d.", Locale.forLanguageTag("hu"))

/**
 * The daily trend, as native Compose bars (brief §42 - no charting library for one simple
 * chart). Never communicates by shape/color alone (brief §43): every bar carries its own
 * merged semantics node naming the exact local date and count, e.g. "2026. szeptember 11.: 7
 * bejelentés" - a screen-reader user gets the same information a sighted user reads from bar
 * height, and the surrounding KPI cards/status note already carry the period/total in plain
 * text regardless of the chart at all.
 */
@Composable
fun TrendChart(trend: List<AnalyticsTrendPointResponse>, modifier: Modifier = Modifier) {
    val maxCount = (trend.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
    val scrollState = rememberScrollState()
    // The 90-day period in particular starts mostly at zero (a newly-onboarded area has no
    // history yet) - defaulting the scroll position to the chart's *start* would show a long
    // flat run of "nincs bejelentés" bars and hide the one range a user actually opened the
    // screen to see: recent activity. Scroll to the end (today) once the row's real content
    // width is known, on every new [trend] (a period/filter change resets it the same way).
    LaunchedEffect(trend) { scrollState.scrollTo(scrollState.maxValue) }
    Row(
        modifier = modifier.fillMaxWidth().height(120.dp).horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        trend.forEach { point ->
            val date = runCatching { LocalDate.parse(point.localDate) }.getOrNull()
            val dateLabel = date?.format(DATE_FORMATTER) ?: point.localDate
            val description = if (point.count > 0) {
                stringResource(R.string.analytics_trend_bar_description, dateLabel, point.count)
            } else {
                stringResource(R.string.analytics_trend_empty_bar_description, dateLabel)
            }
            val heightFraction = (point.count.toFloat() / maxCount).coerceIn(0.03f, 1f)
            Column(
                modifier = Modifier.width(16.dp).fillMaxHeight().clearAndSetSemantics { contentDescription = description },
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .width(10.dp)
                        .fillMaxHeight(heightFraction)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}
