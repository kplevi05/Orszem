package hu.orszembejelento.app.report

import android.content.Context
import hu.orszembejelento.app.location.LocationAssist
import hu.orszembejelento.app.report.data.CatalogRepository
import hu.orszembejelento.app.report.data.ReferenceRepository
import hu.orszembejelento.app.report.data.ReportRepository
import hu.orszembejelento.app.ui.newreport.LocateStatus
import hu.orszembejelento.app.ui.newreport.NewReportViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Field-test fix (§3): a GPS fix that never calls back must not leave
 * [LocateStatus.LOCATING] spinning forever - it is bounded to 15 seconds, cancels the
 * in-flight platform request, and leaves manual settlement search available either way.
 */
class NewReportViewModelLocateTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Enabled services, and a [currentLocation] that never resolves on its own - only cancellation ends it. */
    private class NeverResolvingLocationAssist(context: Context) : LocationAssist(context) {
        var cancelled = false
            private set

        override fun locationServicesEnabled(): Boolean = true

        override suspend fun currentLocation(): android.location.Location? {
            val neverCompletes = CompletableDeferred<android.location.Location?>()
            try {
                return neverCompletes.await()
            } finally {
                // await() only returns via cancellation here (nothing ever completes the
                // deferred) - reaching this block is exactly the "in-flight request was
                // genuinely cancelled, not merely ignored" assertion below.
                cancelled = true
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a GPS fix that never arrives times out after 15 seconds, cancels the request and stops the spinner`() = runTest(dispatcher.scheduler) {
        val locationAssist = NeverResolvingLocationAssist(mock(Context::class.java))
        val api = FakePublicApi()
        val viewModel = NewReportViewModel(
            ReportRepository(FakeReportHistoryDao(), FakeCryptoBox(), api),
            CatalogRepository(api),
            ReferenceRepository(api),
            locationAssist,
        )

        viewModel.onLocateMeRequested()
        assertEquals(LocateStatus.LOCATING, viewModel.state.value.locateStatus)

        // Just under the bound: still locating, nothing has given up yet.
        dispatcher.scheduler.advanceTimeBy(14_999)
        assertEquals(LocateStatus.LOCATING, viewModel.state.value.locateStatus)
        assertTrue("not cancelled before the bound", !locationAssist.cancelled)

        // Crossing the 15s bound: the loading indicator stops with a distinct, retryable
        // TIMEOUT state - never silently stuck, and never conflated with an ordinary FAILED.
        dispatcher.scheduler.advanceTimeBy(2)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(LocateStatus.TIMEOUT, viewModel.state.value.locateStatus)
        assertTrue("the in-flight platform request was cancelled, not merely ignored", locationAssist.cancelled)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `after a GPS timeout, requesting again cancels the stale wait and retries cleanly`() = runTest(dispatcher.scheduler) {
        val locationAssist = NeverResolvingLocationAssist(mock(Context::class.java))
        val api = FakePublicApi()
        val viewModel = NewReportViewModel(
            ReportRepository(FakeReportHistoryDao(), FakeCryptoBox(), api),
            CatalogRepository(api),
            ReferenceRepository(api),
            locationAssist,
        )

        viewModel.onLocateMeRequested()
        dispatcher.scheduler.advanceTimeBy(15_001)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(LocateStatus.TIMEOUT, viewModel.state.value.locateStatus)

        // Retry (brief §3: "keeping manual settlement selection available" implies the
        // retry itself must also work, not just present a retryable label).
        viewModel.onLocateMeRequested()
        assertEquals(LocateStatus.LOCATING, viewModel.state.value.locateStatus)
        dispatcher.scheduler.advanceTimeBy(15_001)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(LocateStatus.TIMEOUT, viewModel.state.value.locateStatus)
    }
}
