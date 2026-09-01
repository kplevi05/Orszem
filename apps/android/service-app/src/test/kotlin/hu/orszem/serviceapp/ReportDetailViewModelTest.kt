package hu.orszem.serviceapp

import com.google.common.truth.Truth.assertThat
import hu.orszem.core.common.ApiError
import hu.orszem.core.common.ApiErrorCode
import hu.orszem.core.common.Outcome
import hu.orszem.core.model.EmbeddedEventType
import hu.orszem.core.model.ReportStatus
import hu.orszem.core.model.ServiceReportDetail
import hu.orszem.core.testing.MainDispatcherRule
import hu.orszem.serviceapp.data.ServiceReportRepository
import hu.orszem.serviceapp.feature.detail.ReportDetailViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ReportDetailViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val repository = mockk<ServiceReportRepository>()
    private val id = "11111111-1111-1111-1111-111111111111"

    private fun detail(status: ReportStatus) = ServiceReportDetail(
        id = id,
        eventType = EmbeddedEventType("KNIFE_ATTACK", "Késelés", "VIOLENCE_DANGER", "Erőszak és közvetlen veszély"),
        trainIdentifier = "IC 123",
        settlement = "Budapest",
        occurredAt = Instant.parse("2026-09-01T10:00:00Z"),
        receivedAt = Instant.parse("2026-09-01T10:01:00Z"),
        status = status,
        acceptedAt = if (status != ReportStatus.NEW) Instant.parse("2026-09-01T10:05:00Z") else null,
        archivedAt = if (status == ReportStatus.ARCHIVED) Instant.parse("2026-09-01T10:30:00Z") else null,
        acceptedBy = null,
        archivedBy = null,
    )

    @Test
    fun `AT-022 loads all detail fields`() = runTest {
        coEvery { repository.detail(id) } returns Outcome.Success(detail(ReportStatus.NEW))
        val vm = ReportDetailViewModel(repository)
        vm.load(id)
        advanceUntilIdle()
        assertThat(vm.state.value.report?.eventType?.label).isEqualTo("Késelés")
        assertThat(vm.state.value.canAccept).isTrue()
        assertThat(vm.state.value.canArchive).isFalse()
    }

    @Test
    fun `AT-023 accept moves the report to IN_PROGRESS`() = runTest {
        coEvery { repository.detail(id) } returns Outcome.Success(detail(ReportStatus.NEW))
        coEvery { repository.accept(id) } returns Outcome.Success(detail(ReportStatus.IN_PROGRESS))
        val vm = ReportDetailViewModel(repository)
        vm.load(id); advanceUntilIdle()
        vm.accept(); advanceUntilIdle()
        assertThat(vm.state.value.report?.status).isEqualTo(ReportStatus.IN_PROGRESS)
        assertThat(vm.state.value.canArchive).isTrue()
    }

    @Test
    fun `AT-024 a 409 shows the stale-conflict prompt and reloads`() = runTest {
        coEvery { repository.detail(id) } returnsMany listOf(
            Outcome.Success(detail(ReportStatus.NEW)),
            Outcome.Success(detail(ReportStatus.IN_PROGRESS)),
        )
        coEvery { repository.accept(id) } returns
            Outcome.Failure(ApiError(ApiErrorCode.REPORT_NOT_ACCEPTABLE, "changed"))
        val vm = ReportDetailViewModel(repository)
        vm.load(id); advanceUntilIdle()
        vm.accept(); advanceUntilIdle()
        assertThat(vm.state.value.staleConflict).isTrue()
        assertThat(vm.state.value.report?.status).isEqualTo(ReportStatus.IN_PROGRESS)
    }

    @Test
    fun `AT-026 archive moves the report to ARCHIVED`() = runTest {
        coEvery { repository.detail(id) } returns Outcome.Success(detail(ReportStatus.IN_PROGRESS))
        coEvery { repository.archive(id) } returns Outcome.Success(detail(ReportStatus.ARCHIVED))
        val vm = ReportDetailViewModel(repository)
        vm.load(id); advanceUntilIdle()
        vm.archive(); advanceUntilIdle()
        assertThat(vm.state.value.report?.status).isEqualTo(ReportStatus.ARCHIVED)
        assertThat(vm.state.value.canAccept).isFalse()
        assertThat(vm.state.value.canArchive).isFalse()
    }
}
