package hu.orszem.publicapp

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import hu.orszem.core.common.ApiError
import hu.orszem.core.common.ApiErrorCode
import hu.orszem.core.common.Outcome
import hu.orszem.core.model.EventType
import hu.orszem.core.model.ReportStatus
import hu.orszem.core.model.SubmittedReport
import hu.orszem.core.testing.MainDispatcherRule
import hu.orszem.publicapp.data.CatalogRepository
import hu.orszem.publicapp.data.ReportRepository
import hu.orszem.publicapp.feature.reportcreate.FormErrorReason
import hu.orszem.publicapp.feature.reportcreate.LocationAction
import hu.orszem.publicapp.feature.reportcreate.LocationMessage
import hu.orszem.publicapp.feature.reportcreate.ReportFormViewModel
import hu.orszem.publicapp.location.SettlementLocationProvider
import hu.orszem.publicapp.location.SettlementLookupResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ReportFormViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val now = Instant.parse("2026-09-01T18:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val knife = EventType("KNIFE_ATTACK", "Késelés", null, 20, "VIOLENCE_DANGER", "Erőszak és közvetlen veszély", 10)

    private val catalog = mockk<CatalogRepository>()
    private val reports = mockk<ReportRepository>()
    private val location = mockk<SettlementLocationProvider>()

    private fun viewModel(): ReportFormViewModel {
        coEvery { catalog.loadEventTypes() } returns Outcome.Success(listOf(knife))
        return ReportFormViewModel(catalog, reports, location, clock)
    }

    @Test
    fun `AT-011 submit is blocked until every required field is valid`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onSubmit()
        vm.state.test {
            val state = expectMostRecentItem()
            assertThat(state.trainError).isTrue()
            assertThat(state.settlementError).isTrue()
            assertThat(state.eventError).isTrue()
            assertThat(state.submitted).isFalse()
        }
        coVerify(exactly = 0) { reports.submit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `AT-012 a complete form submits and marks the flow done`() = runTest {
        coEvery { reports.submit(any(), any(), any(), any(), any()) } returns
            Outcome.Success(SubmittedReport("id", ReportStatus.NEW, now))
        val vm = viewModel()
        advanceUntilIdle()
        vm.onTrainChanged(" IC 123 ")
        vm.onSettlementChanged("Budapest")
        vm.onEventTypeSelected(knife)
        vm.onSubmit()
        advanceUntilIdle()
        assertThat(vm.state.value.submitted).isTrue()
        coVerify { reports.submit(any(), eq("KNIFE_ATTACK"), eq("IC 123"), eq("Budapest"), any()) }
    }

    @Test
    fun `AT-015 a network failure keeps the form and allows retry with the same id`() = runTest {
        val idSlot = slot<java.util.UUID>()
        coEvery { reports.submit(capture(idSlot), any(), any(), any(), any()) } returns
            Outcome.Failure(ApiError(ApiErrorCode.NETWORK, "boom"))
        val vm = viewModel()
        advanceUntilIdle()
        vm.onTrainChanged("IC 123")
        vm.onSettlementChanged("Budapest")
        vm.onEventTypeSelected(knife)

        vm.onSubmit(); advanceUntilIdle()
        val firstId = idSlot.captured
        assertThat(vm.state.value.submitError).isEqualTo(FormErrorReason.NETWORK)
        assertThat(vm.state.value.trainIdentifier).isEqualTo("IC 123")
        assertThat(vm.state.value.submitted).isFalse()

        vm.onRetry(); advanceUntilIdle()
        assertThat(idSlot.captured).isEqualTo(firstId) // idempotent retry
    }

    @Test
    fun `a report-id conflict starts a fresh id on retry`() = runTest {
        val idSlot = mutableListOf<java.util.UUID>()
        coEvery { reports.submit(capture(idSlot), any(), any(), any(), any()) } returns
            Outcome.Failure(ApiError(ApiErrorCode.REPORT_ID_CONFLICT, "conflict"))
        val vm = viewModel()
        advanceUntilIdle()
        vm.onTrainChanged("IC 123"); vm.onSettlementChanged("Budapest"); vm.onEventTypeSelected(knife)
        vm.onSubmit(); advanceUntilIdle()
        vm.onRetry(); advanceUntilIdle()
        assertThat(idSlot).hasSize(2)
        assertThat(idSlot[0]).isNotEqualTo(idSlot[1])
    }

    @Test
    fun `time more than five minutes in the future is rejected`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onTrainChanged("IC 123"); vm.onSettlementChanged("Budapest"); vm.onEventTypeSelected(knife)
        vm.onOccurredAtChanged(now.plus(10, ChronoUnit.MINUTES))
        vm.onSubmit()
        assertThat(vm.state.value.timeError).isTrue()
    }

    // --- Demo v1.1 §A: every location failure is its own explainable state ---

    @Test
    fun `AT-016 missing GPS permission still leaves the settlement editable`() = runTest {
        coEvery { location.currentSettlement() } returns SettlementLookupResult.PermissionMissing
        val vm = viewModel()
        advanceUntilIdle()
        vm.onLocateRequested()
        advanceUntilIdle()
        assertThat(vm.state.value.locationMessage).isEqualTo(LocationMessage.PERMISSION_REQUIRED)
        assertThat(vm.state.value.locating).isFalse()
        vm.onSettlementChanged("Vác")
        assertThat(vm.state.value.settlement).isEqualTo("Vác")
    }

    @Test
    fun `a permanently denied permission points at the app settings`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onLocationPermissionDenied(permanently = true)
        assertThat(vm.state.value.locationMessage).isEqualTo(LocationMessage.PERMISSION_DENIED_FOREVER)
        assertThat(vm.state.value.locationMessage!!.action).isEqualTo(LocationAction.OPEN_APP_SETTINGS)
    }

    @Test
    fun `a denial that can be asked again offers the permission prompt`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onLocationPermissionDenied(permanently = false)
        assertThat(vm.state.value.locationMessage).isEqualTo(LocationMessage.PERMISSION_REQUIRED)
        assertThat(vm.state.value.locationMessage!!.action).isEqualTo(LocationAction.REQUEST_PERMISSION)
    }

    @Test
    fun `disabled device location services is its own state and offers the settings screen`() = runTest {
        coEvery { location.currentSettlement() } returns SettlementLookupResult.LocationServicesDisabled
        val vm = viewModel()
        advanceUntilIdle()
        vm.onLocateRequested()
        advanceUntilIdle()
        assertThat(vm.state.value.locationMessage).isEqualTo(LocationMessage.SERVICES_DISABLED)
        assertThat(vm.state.value.locationMessage!!.action).isEqualTo(LocationAction.OPEN_LOCATION_SETTINGS)
    }

    @Test
    fun `a temporarily unavailable position offers a retry`() = runTest {
        coEvery { location.currentSettlement() } returns SettlementLookupResult.Unavailable
        val vm = viewModel()
        advanceUntilIdle()
        vm.onLocateRequested()
        advanceUntilIdle()
        assertThat(vm.state.value.locationMessage).isEqualTo(LocationMessage.UNAVAILABLE)
        assertThat(vm.state.value.locationMessage!!.action).isEqualTo(LocationAction.RETRY)
    }

    @Test
    fun `a reverse-geocoding failure asks for the settlement manually`() = runTest {
        coEvery { location.currentSettlement() } returns SettlementLookupResult.GeocodingFailed
        val vm = viewModel()
        advanceUntilIdle()
        vm.onLocateRequested()
        advanceUntilIdle()
        assertThat(vm.state.value.locationMessage).isEqualTo(LocationMessage.GEOCODING_FAILED)
        assertThat(vm.state.value.locationMessage!!.action).isEqualTo(LocationAction.NONE)
    }

    @Test
    fun `no location failure ever blocks submission with a manually typed settlement`() = runTest {
        coEvery { location.currentSettlement() } returns SettlementLookupResult.LocationServicesDisabled
        coEvery { reports.submit(any(), any(), any(), any(), any()) } returns
            Outcome.Success(SubmittedReport("id", ReportStatus.NEW, now))
        val vm = viewModel()
        advanceUntilIdle()
        vm.onLocateRequested()
        advanceUntilIdle()

        vm.onTrainChanged("IC 123")
        vm.onSettlementChanged("Gödöllő")
        vm.onEventTypeSelected(knife)
        assertThat(vm.state.value.canSubmit).isTrue()
        vm.onSubmit()
        advanceUntilIdle()
        assertThat(vm.state.value.submitted).isTrue()
    }

    @Test
    fun `GPS success fills the settlement field`() = runTest {
        coEvery { location.currentSettlement() } returns SettlementLookupResult.Success("Gödöllő")
        val vm = viewModel()
        advanceUntilIdle()
        vm.onLocateRequested()
        advanceUntilIdle()
        assertThat(vm.state.value.settlement).isEqualTo("Gödöllő")
    }

    @Test
    fun `AT-010 catalog is grouped by category in canonical order`() = runTest {
        val loud = EventType("LOUD_BEHAVIOR", "Hangoskodás", null, 10, "DISTURBANCE_HARASSMENT", "Rendzavarás és zaklatás", 20)
        coEvery { catalog.loadEventTypes() } returns Outcome.Success(listOf(loud, knife))
        val vm = ReportFormViewModel(catalog, reports, location, clock)
        advanceUntilIdle()
        val categories = vm.state.value.categories
        assertThat(categories.map { it.code }).containsExactly("VIOLENCE_DANGER", "DISTURBANCE_HARASSMENT").inOrder()
    }

    // --- Demo v1.1 §F: transport failures map to friendly, retryable states ---

    @Test
    fun `each transport failure maps to its own user-facing reason`() = runTest {
        val cases = mapOf(
            ApiErrorCode.NETWORK to FormErrorReason.NETWORK,
            ApiErrorCode.TIMEOUT to FormErrorReason.TIMEOUT,
            ApiErrorCode.INTERNAL_ERROR to FormErrorReason.SERVER,
            ApiErrorCode.RATE_LIMITED to FormErrorReason.RATE_LIMITED,
            ApiErrorCode.REPORT_ID_CONFLICT to FormErrorReason.CONFLICT,
            ApiErrorCode.VALIDATION_ERROR to FormErrorReason.VALIDATION,
            ApiErrorCode.UNKNOWN to FormErrorReason.GENERIC,
        )
        for ((code, expected) in cases) {
            coEvery { reports.submit(any(), any(), any(), any(), any()) } returns
                Outcome.Failure(ApiError(code, "raw backend text that must never be shown"))
            val vm = viewModel()
            advanceUntilIdle()
            vm.onTrainChanged("IC 123"); vm.onSettlementChanged("Budapest"); vm.onEventTypeSelected(knife)
            vm.onSubmit(); advanceUntilIdle()
            assertThat(vm.state.value.submitError).isEqualTo(expected)
        }
    }

    @Test
    fun `the failure state carries no raw exception or backend text - only a fixed reason`() = runTest {
        coEvery { reports.submit(any(), any(), any(), any(), any()) } returns Outcome.Failure(
            ApiError(
                ApiErrorCode.NETWORK,
                "java.net.UnknownHostException: 129-159-31-175.sslip.io",
                cause = java.net.UnknownHostException("129-159-31-175.sslip.io"),
            ),
        )
        val vm = viewModel()
        advanceUntilIdle()
        vm.onTrainChanged("IC 123"); vm.onSettlementChanged("Budapest"); vm.onEventTypeSelected(knife)
        vm.onSubmit(); advanceUntilIdle()

        // The UI renders a string resource chosen by this enum; the hostname and the
        // exception name in the ApiError never reach the state the screen reads.
        val state = vm.state.value
        assertThat(state.submitError).isEqualTo(FormErrorReason.NETWORK)
        assertThat(state.toString()).doesNotContain("UnknownHostException")
        assertThat(state.toString()).doesNotContain("sslip.io")
    }

    @Test
    fun `validation failures are not offered a pointless retry`() = runTest {
        assertThat(FormErrorReason.VALIDATION.retryable).isFalse()
        assertThat(FormErrorReason.NETWORK.retryable).isTrue()
        assertThat(FormErrorReason.TIMEOUT.retryable).isTrue()
        assertThat(FormErrorReason.SERVER.retryable).isTrue()
    }

    @Test
    fun `a server failure keeps the form contents and the idempotency id for retry`() = runTest {
        val ids = mutableListOf<java.util.UUID>()
        coEvery { reports.submit(capture(ids), any(), any(), any(), any()) } returns
            Outcome.Failure(ApiError(ApiErrorCode.INTERNAL_ERROR, null))
        val vm = viewModel()
        advanceUntilIdle()
        vm.onTrainChanged("IC 123"); vm.onSettlementChanged("Budapest"); vm.onEventTypeSelected(knife)

        vm.onSubmit(); advanceUntilIdle()
        vm.onRetry(); advanceUntilIdle()

        assertThat(vm.state.value.submitError).isEqualTo(FormErrorReason.SERVER)
        assertThat(vm.state.value.trainIdentifier).isEqualTo("IC 123")
        assertThat(vm.state.value.settlement).isEqualTo("Budapest")
        assertThat(vm.state.value.selectedEventType).isEqualTo(knife)
        // Same id on retry: the backend can de-duplicate, so no duplicate report.
        assertThat(ids).hasSize(2)
        assertThat(ids[0]).isEqualTo(ids[1])
    }
}
