package hu.orszem.serviceapp

import com.google.common.truth.Truth.assertThat
import hu.orszem.core.common.ApiError
import hu.orszem.core.common.ApiErrorCode
import hu.orszem.core.common.Outcome
import hu.orszem.core.model.EmbeddedEventType
import hu.orszem.core.model.ReportStatus
import hu.orszem.core.model.ServiceReportSummary
import hu.orszem.core.testing.MainDispatcherRule
import hu.orszem.serviceapp.data.ServiceReportRepository
import hu.orszem.serviceapp.feature.reports.ActiveReportsViewModel
import hu.orszem.serviceapp.feature.reports.DateFilter
import hu.orszem.serviceapp.feature.reports.ListErrorReason
import hu.orszem.serviceapp.feature.reports.ReportFilters
import hu.orszem.serviceapp.feature.reports.ReportSort
import hu.orszem.serviceapp.feature.reports.ReportTab
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Demo v1.1 §B/§C/§D/§E/§F — active Reports screen behaviour.
 *
 * Every assertion here runs against the *complete* dataset the ViewModel holds,
 * which is what `repository.allActiveReports()` returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveReportsViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val repository = mockk<ServiceReportRepository>()

    // 2026-09-01T12:00Z is 14:00 in Europe/Budapest, so "today" is 2026-09-01 there.
    private val clock = Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneId.of("UTC"))

    private fun report(
        id: String,
        status: ReportStatus = ReportStatus.NEW,
        eventCode: String = "FIGHT",
        eventLabel: String = "Verekedés",
        categoryCode: String = "VIOLENCE_DANGER",
        categoryLabel: String = "Erőszak és közvetlen veszély",
        train: String = "IC 123",
        settlement: String = "Budapest",
        occurredAt: String = "2026-09-01T10:00:00Z",
    ) = ServiceReportSummary(
        id = id,
        eventType = EmbeddedEventType(eventCode, eventLabel, categoryCode, categoryLabel),
        trainIdentifier = train,
        settlement = settlement,
        occurredAt = Instant.parse(occurredAt),
        receivedAt = Instant.parse(occurredAt).plusSeconds(60),
        status = status,
        acceptedAt = null,
        archivedAt = null,
    )

    private fun vmWith(vararg reports: ServiceReportSummary): ActiveReportsViewModel {
        coEvery { repository.allActiveReports(any(), any()) } returns Outcome.Success(reports.toList())
        return ActiveReportsViewModel(repository, clock)
    }

    // --- §B tabs -----------------------------------------------------------

    @Test
    fun `the UJ tab is selected by default`() = runTest {
        val vm = vmWith(report("a"), report("b", ReportStatus.IN_PROGRESS))
        advanceUntilIdle()
        assertThat(vm.state.value.tab).isEqualTo(ReportTab.NEW)
    }

    @Test
    fun `NEW and IN_PROGRESS reports are separated between the two tabs`() = runTest {
        val vm = vmWith(
            report("new-1"),
            report("new-2"),
            report("wip-1", ReportStatus.IN_PROGRESS),
        )
        advanceUntilIdle()

        assertThat(vm.state.value.visible.map { it.id }).containsExactly("new-1", "new-2")
        assertThat(vm.state.value.newCount).isEqualTo(2)
        assertThat(vm.state.value.inProgressCount).isEqualTo(1)

        vm.onTabSelected(ReportTab.IN_PROGRESS)
        assertThat(vm.state.value.visible.map { it.id }).containsExactly("wip-1")
    }

    @Test
    fun `accepting a report moves it from UJ to FOLYAMATBAN`() = runTest {
        val vm = vmWith(report("a"), report("b", ReportStatus.IN_PROGRESS))
        advanceUntilIdle()
        assertThat(vm.state.value.visible.map { it.id }).containsExactly("a")

        vm.onStatusChanged("a", ReportStatus.IN_PROGRESS)

        assertThat(vm.state.value.visible).isEmpty()
        assertThat(vm.state.value.newCount).isEqualTo(0)
        vm.onTabSelected(ReportTab.IN_PROGRESS)
        assertThat(vm.state.value.visible.map { it.id }).containsExactly("a", "b")
    }

    @Test
    fun `archiving removes the report from the active dataset entirely`() = runTest {
        val vm = vmWith(report("a", ReportStatus.IN_PROGRESS))
        advanceUntilIdle()
        vm.onStatusChanged("a", ReportStatus.ARCHIVED)
        assertThat(vm.state.value.all).isEmpty()
        assertThat(vm.state.value.inProgressCount).isEqualTo(0)
    }

    // --- §C filtering -------------------------------------------------------

    @Test
    fun `category filter narrows the list`() = runTest {
        val vm = vmWith(
            report("a"),
            report("b", categoryCode = "THEFT_PROPERTY", categoryLabel = "Lopás", eventCode = "PICKPOCKET"),
        )
        advanceUntilIdle()
        vm.onFiltersChanged(ReportFilters(categoryCode = "THEFT_PROPERTY"))
        assertThat(vm.state.value.visible.map { it.id }).containsExactly("b")
        assertThat(vm.state.value.filters.activeCount).isEqualTo(1)
    }

    @Test
    fun `incident type filter narrows the list`() = runTest {
        val vm = vmWith(report("a"), report("b", eventCode = "KNIFE_ATTACK", eventLabel = "Késelés"))
        advanceUntilIdle()
        vm.onFiltersChanged(ReportFilters(eventTypeCode = "KNIFE_ATTACK"))
        assertThat(vm.state.value.visible.map { it.id }).containsExactly("b")
    }

    @Test
    fun `settlement filter narrows the list`() = runTest {
        val vm = vmWith(report("a"), report("b", settlement = "Vác"))
        advanceUntilIdle()
        vm.onFiltersChanged(ReportFilters(settlement = "Vác"))
        assertThat(vm.state.value.visible.map { it.id }).containsExactly("b")
    }

    @Test
    fun `train filter narrows the list`() = runTest {
        val vm = vmWith(report("a"), report("b", train = "S70"))
        advanceUntilIdle()
        vm.onFiltersChanged(ReportFilters(trainIdentifier = "S70"))
        assertThat(vm.state.value.visible.map { it.id }).containsExactly("b")
    }

    @Test
    fun `date filter distinguishes today from the last seven days and everything`() = runTest {
        val vm = vmWith(
            report("today", occurredAt = "2026-09-01T06:00:00Z"),
            report("threeDaysAgo", occurredAt = "2026-08-29T06:00:00Z"),
            report("longAgo", occurredAt = "2026-07-01T06:00:00Z"),
        )
        advanceUntilIdle()

        vm.onFiltersChanged(ReportFilters(date = DateFilter.TODAY))
        assertThat(vm.state.value.visible.map { it.id }).containsExactly("today")

        vm.onFiltersChanged(ReportFilters(date = DateFilter.LAST_7_DAYS))
        assertThat(vm.state.value.visible.map { it.id }).containsExactly("today", "threeDaysAgo")

        vm.onFiltersChanged(ReportFilters(date = DateFilter.ALL))
        assertThat(vm.state.value.visible).hasSize(3)
    }

    @Test
    fun `combined filters must all match - logical AND`() = runTest {
        val vm = vmWith(
            report("match", settlement = "Budapest", train = "IC 123", occurredAt = "2026-09-01T06:00:00Z"),
            report("wrongTrain", settlement = "Budapest", train = "S70", occurredAt = "2026-09-01T06:00:00Z"),
            report("wrongDay", settlement = "Budapest", train = "IC 123", occurredAt = "2026-07-01T06:00:00Z"),
            report("wrongTown", settlement = "Vác", train = "IC 123", occurredAt = "2026-09-01T06:00:00Z"),
        )
        advanceUntilIdle()

        vm.onFiltersChanged(
            ReportFilters(settlement = "Budapest", trainIdentifier = "IC 123", date = DateFilter.TODAY),
        )

        assertThat(vm.state.value.visible.map { it.id }).containsExactly("match")
        assertThat(vm.state.value.filters.activeCount).isEqualTo(3)
    }

    @Test
    fun `clearing filters restores the full tab and keeps the chosen sort`() = runTest {
        val vm = vmWith(report("a", settlement = "Vác"), report("b", settlement = "Budapest"))
        advanceUntilIdle()
        vm.onSortSelected(ReportSort.SETTLEMENT_ASC)
        vm.onFiltersChanged(ReportFilters(settlement = "Vác"))
        assertThat(vm.state.value.visible).hasSize(1)

        vm.clearFilters()

        assertThat(vm.state.value.visible).hasSize(2)
        assertThat(vm.state.value.filters.isActive).isFalse()
        assertThat(vm.state.value.sort).isEqualTo(ReportSort.SETTLEMENT_ASC)
    }

    @Test
    fun `filters and sort survive a tab switch while the screen stays alive`() = runTest {
        val vm = vmWith(
            report("new-bp", settlement = "Budapest"),
            report("wip-bp", ReportStatus.IN_PROGRESS, settlement = "Budapest"),
            report("wip-vac", ReportStatus.IN_PROGRESS, settlement = "Vác"),
        )
        advanceUntilIdle()
        vm.onSortSelected(ReportSort.OLDEST_FIRST)
        vm.onFiltersChanged(ReportFilters(settlement = "Budapest"))

        vm.onTabSelected(ReportTab.IN_PROGRESS)

        assertThat(vm.state.value.filters.settlement).isEqualTo("Budapest")
        assertThat(vm.state.value.sort).isEqualTo(ReportSort.OLDEST_FIRST)
        assertThat(vm.state.value.visible.map { it.id }).containsExactly("wip-bp")
    }

    // --- §D sorting ---------------------------------------------------------

    @Test
    fun `newest and oldest sorting order the whole tab by incident time`() = runTest {
        val vm = vmWith(
            report("mid", occurredAt = "2026-08-20T10:00:00Z"),
            report("newest", occurredAt = "2026-09-01T10:00:00Z"),
            report("oldest", occurredAt = "2026-07-01T10:00:00Z"),
        )
        advanceUntilIdle()

        assertThat(vm.state.value.sort).isEqualTo(ReportSort.NEWEST_FIRST)
        assertThat(vm.state.value.visible.map { it.id }).containsExactly("newest", "mid", "oldest").inOrder()

        vm.onSortSelected(ReportSort.OLDEST_FIRST)
        assertThat(vm.state.value.visible.map { it.id }).containsExactly("oldest", "mid", "newest").inOrder()
    }

    @Test
    fun `alphabetical sorting uses Hungarian collation`() = runTest {
        val vm = vmWith(
            report("z", settlement = "Zalaegerszeg"),
            report("o", settlement = "Ózd"),
            report("b", settlement = "Budapest"),
        )
        advanceUntilIdle()
        vm.onSortSelected(ReportSort.SETTLEMENT_ASC)
        // Ó collates with O, before Z — a plain code-point sort would put it last.
        assertThat(vm.state.value.visible.map { it.id }).containsExactly("b", "o", "z").inOrder()
    }

    @Test
    fun `sorting is deterministic for ties - newest incident then id`() = runTest {
        val vm = vmWith(
            report("b-id", settlement = "Budapest", occurredAt = "2026-08-01T10:00:00Z"),
            report("a-id", settlement = "Budapest", occurredAt = "2026-09-01T10:00:00Z"),
            report("c-id", settlement = "Budapest", occurredAt = "2026-09-01T10:00:00Z"),
        )
        advanceUntilIdle()
        vm.onSortSelected(ReportSort.SETTLEMENT_ASC)
        assertThat(vm.state.value.visible.map { it.id }).containsExactly("a-id", "c-id", "b-id").inOrder()
    }

    @Test
    fun `the backend NEW-first ordering never leaks into a user-selected sort`() = runTest {
        // Arrives in the backend's own order: NEW first, then IN_PROGRESS.
        val vm = vmWith(
            report("new-late", ReportStatus.NEW, occurredAt = "2026-09-01T10:00:00Z"),
            report("new-early", ReportStatus.NEW, occurredAt = "2026-07-01T10:00:00Z"),
        )
        advanceUntilIdle()
        vm.onSortSelected(ReportSort.OLDEST_FIRST)
        assertThat(vm.state.value.visible.map { it.id }).containsExactly("new-early", "new-late").inOrder()
    }

    @Test
    fun `filtering and sorting compose`() = runTest {
        val vm = vmWith(
            report("bp-old", settlement = "Budapest", occurredAt = "2026-08-01T10:00:00Z"),
            report("bp-new", settlement = "Budapest", occurredAt = "2026-09-01T10:00:00Z"),
            report("vac", settlement = "Vác", occurredAt = "2026-09-01T11:00:00Z"),
        )
        advanceUntilIdle()
        vm.onFiltersChanged(ReportFilters(settlement = "Budapest"))
        vm.onSortSelected(ReportSort.OLDEST_FIRST)
        assertThat(vm.state.value.visible.map { it.id }).containsExactly("bp-old", "bp-new").inOrder()
    }

    // --- §E deletion --------------------------------------------------------

    @Test
    fun `a successful deletion removes the report and updates the counts`() = runTest {
        coEvery { repository.delete("a") } returns Outcome.Success(Unit)
        val vm = vmWith(report("a"), report("b"))
        advanceUntilIdle()
        assertThat(vm.state.value.newCount).isEqualTo(2)

        var reported: Boolean? = null
        vm.deleteReport("a") { reported = it }
        advanceUntilIdle()

        assertThat(reported).isTrue()
        assertThat(vm.state.value.all.map { it.id }).containsExactly("b")
        assertThat(vm.state.value.visible.map { it.id }).containsExactly("b")
        assertThat(vm.state.value.newCount).isEqualTo(1)
    }

    @Test
    fun `a failed deletion leaves the list untouched and surfaces a retryable error`() = runTest {
        coEvery { repository.delete("a") } returns Outcome.Failure(ApiError(ApiErrorCode.NETWORK, null))
        val vm = vmWith(report("a"), report("b"))
        advanceUntilIdle()

        var reported: Boolean? = null
        vm.deleteReport("a") { reported = it }
        advanceUntilIdle()

        assertThat(reported).isFalse()
        assertThat(vm.state.value.all.map { it.id }).containsExactly("a", "b")
        assertThat(vm.state.value.newCount).isEqualTo(2)
        assertThat(vm.state.value.error).isEqualTo(ListErrorReason.NETWORK)
    }

    // --- §F error handling --------------------------------------------------

    @Test
    fun `a refresh failure keeps the already loaded data on screen`() = runTest {
        val vm = vmWith(report("a"))
        advanceUntilIdle()
        assertThat(vm.state.value.all).hasSize(1)

        coEvery { repository.allActiveReports(any(), any()) } returns
            Outcome.Failure(ApiError(ApiErrorCode.TIMEOUT, null))
        vm.refresh()
        advanceUntilIdle()

        assertThat(vm.state.value.error).isEqualTo(ListErrorReason.TIMEOUT)
        assertThat(vm.state.value.all.map { it.id }).containsExactly("a")
        assertThat(vm.state.value.loading).isFalse()
    }

    @Test
    fun `a first-load failure with no data surfaces a full-screen error`() = runTest {
        coEvery { repository.allActiveReports(any(), any()) } returns
            Outcome.Failure(ApiError(ApiErrorCode.NETWORK, null))
        val vm = ActiveReportsViewModel(repository, clock)
        advanceUntilIdle()

        assertThat(vm.state.value.error).isEqualTo(ListErrorReason.NETWORK)
        assertThat(vm.state.value.hasContent).isFalse()
        assertThat(vm.state.value.loading).isFalse()
    }

    @Test
    fun `the view model asks for the complete dataset, not a single page`() = runTest {
        val vm = vmWith(report("a"))
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.allActiveReports(any(), any()) }
        // The single-page entry point is never used by this screen.
        coVerify(exactly = 0) { repository.activeReports(any(), any()) }
        assertThat(vm.state.value.all).hasSize(1)
    }
}
