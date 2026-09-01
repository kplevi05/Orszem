package hu.orszem.serviceapp

import com.google.common.truth.Truth.assertThat
import hu.orszem.core.common.ApiError
import hu.orszem.core.common.ApiErrorCode
import hu.orszem.core.common.Outcome
import hu.orszem.core.model.EmbeddedEventType
import hu.orszem.core.model.ReportPage
import hu.orszem.core.model.ReportStatus
import hu.orszem.core.model.ServiceReportSummary
import hu.orszem.core.testing.MainDispatcherRule
import hu.orszem.serviceapp.data.ServiceReportRepository
import hu.orszem.serviceapp.feature.common.ListUiState
import hu.orszem.serviceapp.feature.reports.ActiveReportsViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveReportsViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val repository = mockk<ServiceReportRepository>()

    private fun report(id: String, status: ReportStatus) = ServiceReportSummary(
        id = id,
        eventType = EmbeddedEventType("FIGHT", "Verekedés", "VIOLENCE_DANGER", "Erőszak és közvetlen veszély"),
        trainIdentifier = "IC 123",
        settlement = "Budapest",
        occurredAt = Instant.parse("2026-09-01T10:00:00Z"),
        receivedAt = Instant.parse("2026-09-01T10:01:00Z"),
        status = status,
        acceptedAt = null,
        archivedAt = null,
    )

    @Test
    fun `AT-020 loads active reports into content`() = runTest {
        coEvery { repository.activeReports(any(), any()) } returns Outcome.Success(
            ReportPage(listOf(report("a", ReportStatus.NEW), report("b", ReportStatus.IN_PROGRESS)), null),
        )
        val vm = ActiveReportsViewModel(repository)
        advanceUntilIdle()
        val state = vm.state.value
        assertThat(state).isInstanceOf(ListUiState.Content::class.java)
        assertThat((state as ListUiState.Content).items).hasSize(2)
    }

    @Test
    fun `an empty list is surfaced as the empty state`() = runTest {
        coEvery { repository.activeReports(any(), any()) } returns Outcome.Success(ReportPage(emptyList(), null))
        val vm = ActiveReportsViewModel(repository)
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(ListUiState.Empty)
    }

    @Test
    fun `a network failure yields a recoverable error state`() = runTest {
        coEvery { repository.activeReports(any(), any()) } returns Outcome.Failure(ApiError(ApiErrorCode.NETWORK, "io"))
        val vm = ActiveReportsViewModel(repository)
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(ListUiState.Error(recoverable = true))
    }
}
