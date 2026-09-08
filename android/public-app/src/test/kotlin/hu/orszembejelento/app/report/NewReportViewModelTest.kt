package hu.orszembejelento.app.report

import android.content.Context
import hu.orszembejelento.app.location.LocationAssist
import hu.orszembejelento.app.report.data.CatalogRepository
import hu.orszembejelento.app.report.data.ReferenceRepository
import hu.orszembejelento.app.report.data.ReportRepository
import hu.orszembejelento.app.report.data.SettlementOption
import hu.orszembejelento.app.report.data.network.RailwayLineItemBody
import hu.orszembejelento.app.report.data.network.RailwayLinesForSettlementResponseBody
import hu.orszembejelento.app.report.domain.LineCoverage
import hu.orszembejelento.app.report.domain.RailwayLineStep
import hu.orszembejelento.app.ui.newreport.NewReportViewModel
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import retrofit2.Response

/**
 * §57: settlement-change clears the railway-line decision, and a stale async
 * settlement/line response is ignored once a newer request has started.
 *
 * `LocationAssist` is constructed with a mocked [Context] purely to satisfy the
 * constructor - neither test exercises any locate/GPS path.
 */
class NewReportViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `selecting a new settlement after choosing a railway line clears the line decision`() = runTest(dispatcher.scheduler) {
        val api = FakePublicApi().apply {
            railwayLinesHandler = { Response.success(RailwayLinesForSettlementResponseBody("COMPLETE", emptyList())) }
        }
        val viewModel = NewReportViewModel(
            ReportRepository(FakeReportHistoryDao(), FakeCryptoBox(), api),
            CatalogRepository(api),
            ReferenceRepository(api),
            LocationAssist(mock(Context::class.java)),
        )

        val settlementA = SettlementOption(UUID.randomUUID(), "Alfaváros", null)
        viewModel.onSettlementSelected(settlementA)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(RailwayLineStep.NoVerifiedCandidate(LineCoverage.COMPLETE), viewModel.state.value.lineStep)

        // Editing the settlement text (not merely selecting a new one) must clear both the
        // selection and the line decision, per §40.
        viewModel.onSettlementQueryChanged("Something else entirely")
        assertNull(viewModel.state.value.selectedSettlement)
        assertEquals(RailwayLineStep.NotApplicable, viewModel.state.value.lineStep)
    }

    @Test
    fun `a stale railway-line response for a previously-selected settlement is ignored`() = runTest(dispatcher.scheduler) {
        val settlementAId = UUID.randomUUID()
        val settlementBId = UUID.randomUUID()

        val api = FakePublicApi().apply {
            railwayLinesHandler = { id ->
                if (id == settlementAId.toString()) {
                    // Slow response for A - must not win even though it's requested first.
                    delay(1_000)
                    Response.success(RailwayLinesForSettlementResponseBody("COMPLETE", emptyList()))
                } else {
                    delay(10)
                    Response.success(
                        RailwayLinesForSettlementResponseBody(
                            "PARTIAL",
                            listOf(RailwayLineItemBody(UUID.randomUUID().toString(), "1", "Line 1")),
                        ),
                    )
                }
            }
        }
        val viewModel = NewReportViewModel(
            ReportRepository(FakeReportHistoryDao(), FakeCryptoBox(), api),
            CatalogRepository(api),
            ReferenceRepository(api),
            LocationAssist(mock(Context::class.java)),
        )

        viewModel.onSettlementSelected(SettlementOption(settlementAId, "Alfaváros", null))
        // Immediately switch to B before A's slow response has resolved.
        viewModel.onSettlementSelected(SettlementOption(settlementBId, "Beta", null))

        dispatcher.scheduler.advanceUntilIdle()

        val finalStep = viewModel.state.value.lineStep
        assertEquals(LineCoverage.PARTIAL, (finalStep as RailwayLineStep.RequiresChoice).coverage)
        assertEquals(settlementBId, viewModel.state.value.selectedSettlement?.id)
    }
}
