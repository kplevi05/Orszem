package hu.orszem.serviceapp.feature.reports

import hu.orszem.core.model.ReportStatus
import hu.orszem.core.model.ServiceReportSummary
import java.text.Collator
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Client-side filtering and sorting for the active Reports screen (Demo v1.1 §C, §D).
 *
 * ARCHITECTURAL NOTE — why this is client-side:
 * the `/service/reports` cursor contract is deliberately left untouched. The
 * ViewModel loads the *complete* active dataset by following the existing opaque
 * cursor until `nextCursor` is null, and every operation below then runs over
 * that whole dataset — never over a single page. The backend keeps its
 * documented default order (NEW first, then receivedAt DESC) and its keyset
 * semantics; that order is discarded once the data is partitioned by tab, so it
 * cannot leak into a user-selected sort.
 *
 * Everything here is a pure function of its inputs so it can be unit tested
 * without Android or the network.
 */

/** Workflow tab. Archived cases live on their own screen and are never in this set. */
enum class ReportTab { NEW, IN_PROGRESS }

val ReportTab.status: ReportStatus
    get() = when (this) {
        ReportTab.NEW -> ReportStatus.NEW
        ReportTab.IN_PROGRESS -> ReportStatus.IN_PROGRESS
    }

enum class DateFilter { ALL, TODAY, LAST_7_DAYS }

enum class ReportSort {
    NEWEST_FIRST,
    OLDEST_FIRST,
    CATEGORY_ASC,
    EVENT_TYPE_ASC,
    SETTLEMENT_ASC,
    TRAIN_ASC,
    ;

    companion object {
        val DEFAULT = NEWEST_FIRST
    }
}

/**
 * The active filter set. All non-null / non-ALL criteria combine with logical AND.
 *
 * Category and incident type come from the reports' own embedded catalog values,
 * so there is exactly one taxonomy — the server-side event catalog.
 */
data class ReportFilters(
    val categoryCode: String? = null,
    val eventTypeCode: String? = null,
    val settlement: String? = null,
    val trainIdentifier: String? = null,
    val date: DateFilter = DateFilter.ALL,
) {
    val activeCount: Int
        get() = listOfNotNull(categoryCode, eventTypeCode, settlement, trainIdentifier).size +
            if (date != DateFilter.ALL) 1 else 0

    val isActive: Boolean get() = activeCount > 0

    companion object {
        val NONE = ReportFilters()
    }
}

/**
 * The backend's business calendar zone. Mirrors `orszem.business-time-zone`, so
 * "Ma" on this screen means the same calendar day as `todayReports` in the
 * statistics tab.
 */
val BUSINESS_ZONE: ZoneId = ZoneId.of("Europe/Budapest")

/** Hungarian-aware alphabetical comparison (á/é/í/ó/ö/ő/ú/ü/ű sort correctly). */
private val hungarianCollator: Collator = Collator.getInstance(Locale("hu", "HU")).apply {
    strength = Collator.SECONDARY
}

private fun collate(a: String, b: String): Int = hungarianCollator.compare(a, b)

/**
 * Analytics counts "today" by `occurredAt` in the business zone, so the date
 * filter does the same — the two views can never disagree.
 */
fun ServiceReportSummary.businessDate(zone: ZoneId = BUSINESS_ZONE): LocalDate =
    occurredAt.atZone(zone).toLocalDate()

fun List<ServiceReportSummary>.applyFilters(
    filters: ReportFilters,
    clock: Clock,
    zone: ZoneId = BUSINESS_ZONE,
): List<ServiceReportSummary> {
    if (!filters.isActive) return this
    val today = LocalDate.now(clock.withZone(zone))
    // "Last 7 days" is the seven calendar days ending today, inclusive.
    val earliest = today.minusDays(6)
    return filter { report ->
        (filters.categoryCode == null || report.eventType.categoryCode == filters.categoryCode) &&
            (filters.eventTypeCode == null || report.eventType.code == filters.eventTypeCode) &&
            (filters.settlement == null || report.settlement == filters.settlement) &&
            (filters.trainIdentifier == null || report.trainIdentifier == filters.trainIdentifier) &&
            when (filters.date) {
                DateFilter.ALL -> true
                DateFilter.TODAY -> report.businessDate(zone) == today
                DateFilter.LAST_7_DAYS -> report.businessDate(zone) >= earliest
            }
    }
}

/**
 * Deterministic ordering.
 *
 * Every sort mode ends with the same stable tie-break — incident timestamp
 * descending, then id — so equal primary keys never produce a shuffling list
 * between recompositions or refreshes.
 */
fun List<ServiceReportSummary>.applySort(sort: ReportSort): List<ServiceReportSummary> {
    val tieBreak = compareByDescending<ServiceReportSummary> { it.occurredAt }.thenBy { it.id }
    val comparator = when (sort) {
        ReportSort.NEWEST_FIRST -> compareByDescending<ServiceReportSummary> { it.occurredAt }.thenBy { it.id }
        ReportSort.OLDEST_FIRST -> compareBy<ServiceReportSummary> { it.occurredAt }.thenBy { it.id }
        ReportSort.CATEGORY_ASC ->
            Comparator<ServiceReportSummary> { a, b -> collate(a.eventType.categoryLabel, b.eventType.categoryLabel) }
                .then(tieBreak)
        ReportSort.EVENT_TYPE_ASC ->
            Comparator<ServiceReportSummary> { a, b -> collate(a.eventType.label, b.eventType.label) }
                .then(tieBreak)
        ReportSort.SETTLEMENT_ASC ->
            Comparator<ServiceReportSummary> { a, b -> collate(a.settlement, b.settlement) }.then(tieBreak)
        ReportSort.TRAIN_ASC ->
            Comparator<ServiceReportSummary> { a, b -> collate(a.trainIdentifier, b.trainIdentifier) }.then(tieBreak)
    }
    return sortedWith(comparator)
}

/** Distinct filter choices, derived from the loaded dataset and collated for display. */
data class FilterOptions(
    val categories: List<Pair<String, String>> = emptyList(),
    val eventTypes: List<EventTypeOption> = emptyList(),
    val settlements: List<String> = emptyList(),
    val trains: List<String> = emptyList(),
)

data class EventTypeOption(val code: String, val label: String, val categoryCode: String)

/**
 * Builds the picker contents from the complete dataset, so the options offered
 * are exactly the values that can actually match something.
 */
fun List<ServiceReportSummary>.filterOptions(): FilterOptions = FilterOptions(
    categories = map { it.eventType.categoryCode to it.eventType.categoryLabel }
        .distinct()
        .sortedWith { a, b -> collate(a.second, b.second) },
    eventTypes = map { EventTypeOption(it.eventType.code, it.eventType.label, it.eventType.categoryCode) }
        .distinctBy { it.code }
        .sortedWith { a, b -> collate(a.label, b.label) },
    settlements = map { it.settlement }.distinct().sortedWith(::collate),
    trains = map { it.trainIdentifier }.distinct().sortedWith(::collate),
)
