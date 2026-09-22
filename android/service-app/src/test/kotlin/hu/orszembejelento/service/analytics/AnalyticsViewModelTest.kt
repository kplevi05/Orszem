package hu.orszembejelento.service.analytics

import hu.orszembejelento.service.analytics.data.AnalyticsAreaOptionResponse
import hu.orszembejelento.service.analytics.data.AnalyticsAreaOptionsResponse
import hu.orszembejelento.service.analytics.data.AnalyticsFilter
import hu.orszembejelento.service.analytics.data.AnalyticsPeriod
import hu.orszembejelento.service.analytics.data.AnalyticsRepository
import hu.orszembejelento.service.analytics.ui.AnalyticsViewModel
import hu.orszembejelento.service.common.data.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Phase 11 brief §60 - period/area/category filter behavior, error handling, session isolation. */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModelTest {

    @Before
    fun setUpMainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    @After
    fun tearDownMainDispatcher() { Dispatchers.resetMain() }

    @Test
    fun `initial load fetches both summary and area options`() = runTest {
        val fake = FakeAnalyticsRepository(
            summaryResult = ApiResult.Success(FakeAnalyticsRepository.defaultSummary(total = 5, new = 5)),
        )
        val vm = AnalyticsViewModel(fake, onSessionEnded = {})
        assertEquals(1, fake.summaryCalls)
        assertEquals(1, fake.areaOptionsCalls)
        assertEquals(5, vm.state.value.summary?.totalReports)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `updateFilter reloads with the new filter and defaults to LAST_30_DAYS`() = runTest {
        val fake = FakeAnalyticsRepository()
        val vm = AnalyticsViewModel(fake, onSessionEnded = {})
        assertEquals(AnalyticsPeriod.LAST_30_DAYS, fake.seenFilters.first().period)

        vm.updateFilter(AnalyticsFilter(period = AnalyticsPeriod.TODAY, areaId = "area-1"))
        assertEquals(AnalyticsPeriod.TODAY, fake.seenFilters.last().period)
        assertEquals("area-1", fake.seenFilters.last().areaId)
        assertEquals(2, fake.summaryCalls)
    }

    @Test
    fun `a summary failure surfaces as an error but keeps previously shown data`() = runTest {
        val fake = FakeAnalyticsRepository(summaryResult = ApiResult.Success(FakeAnalyticsRepository.defaultSummary(total = 3)))
        val vm = AnalyticsViewModel(fake, onSessionEnded = {})
        assertEquals(3, vm.state.value.summary?.totalReports)

        fake.summaryResult = ApiResult.Failure(code = null, httpStatus = 500)
        vm.refresh()
        assertTrue(vm.state.value.error is ApiResult.Failure)
        // Stale-but-present data, not blanked - the error banner communicates the failed refresh.
        assertEquals(3, vm.state.value.summary?.totalReports)
    }

    @Test
    fun `a network error on first load surfaces as an error with no summary yet`() = runTest {
        val fake = FakeAnalyticsRepository(summaryResult = ApiResult.NetworkError)
        val vm = AnalyticsViewModel(fake, onSessionEnded = {})
        assertTrue(vm.state.value.error is ApiResult.NetworkError)
        assertNull(vm.state.value.summary)
    }

    @Test
    fun `SessionEnded from the summary call invokes the callback exactly once`() = runTest {
        var sessionEndedCalls = 0
        val fake = FakeAnalyticsRepository(summaryResult = ApiResult.SessionEnded)
        AnalyticsViewModel(fake, onSessionEnded = { sessionEndedCalls++ })
        assertEquals(1, sessionEndedCalls)
    }

    @Test
    fun `SessionEnded from the area-options call invokes the callback without ever calling summary`() = runTest {
        var sessionEndedCalls = 0
        val fake = FakeAnalyticsRepository(areaOptionsResult = ApiResult.SessionEnded)
        val vm = AnalyticsViewModel(fake, onSessionEnded = { sessionEndedCalls++ })
        assertEquals(1, sessionEndedCalls)
        assertEquals(0, fake.summaryCalls)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `retrying after a failure does not accumulate stale filters`() = runTest {
        val fake = FakeAnalyticsRepository(summaryResult = ApiResult.NetworkError)
        val vm = AnalyticsViewModel(fake, onSessionEnded = {})
        assertEquals(1, fake.summaryCalls)

        fake.summaryResult = ApiResult.Success(FakeAnalyticsRepository.defaultSummary(total = 1, new = 1))
        vm.refresh()
        assertEquals(2, fake.summaryCalls)
        assertEquals(1, vm.state.value.summary?.totalReports)
        assertEquals(null, vm.state.value.error)
    }

    @Test
    fun `area options with canViewUnclassified false is carried into state unchanged`() = runTest {
        val fake = FakeAnalyticsRepository(
            areaOptionsResult = ApiResult.Success(
                AnalyticsAreaOptionsResponse(listOf(AnalyticsAreaOptionResponse("a1", "Terulet A", true)), canViewUnclassified = false),
            ),
        )
        val vm = AnalyticsViewModel(fake, onSessionEnded = {})
        assertEquals(false, vm.state.value.areaOptions?.canViewUnclassified)
        assertEquals(1, vm.state.value.areaOptions?.areas?.size)
    }

    /** Field-test fix (§4): see `ReportQueueViewModelTest`'s equivalent test for the full rationale. */
    @Test
    fun `refresh cancels an in-flight refresh so a slower stale response can never overwrite the fresher one`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            var summaryCalls = 0
            val repo = object : AnalyticsRepository {
                override suspend fun summary(filter: AnalyticsFilter): ApiResult<hu.orszembejelento.service.analytics.data.AnalyticsSummaryResponse> {
                    summaryCalls++
                    return if (summaryCalls == 1) {
                        delay(1_000)
                        ApiResult.Success(FakeAnalyticsRepository.defaultSummary(total = 1))
                    } else {
                        delay(10)
                        ApiResult.Success(FakeAnalyticsRepository.defaultSummary(total = 2))
                    }
                }
                override suspend fun areaOptions(): ApiResult<AnalyticsAreaOptionsResponse> =
                    ApiResult.Success(AnalyticsAreaOptionsResponse(emptyList(), false))
            }
            val vm = AnalyticsViewModel(repo, onSessionEnded = {})
            dispatcher.scheduler.runCurrent() // let init{}'s own call #1 actually start (past areaOptions() and into summary()'s delay) first
            vm.refresh() // fired while that first call is still genuinely in flight
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(2, vm.state.value.summary?.totalReports)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `AnalyticsFilter mutual exclusion helper never counts both areaId and unclassifiedOnly redundantly`() {
        val filter = AnalyticsFilter(areaId = "a1")
        assertEquals(1, filter.activeFacetCount)
        val unclassified = AnalyticsFilter(unclassifiedOnly = true)
        assertEquals(1, unclassified.activeFacetCount)
        val withCategory = AnalyticsFilter(areaId = "a1", categoryCode = "VIOLENCE_DANGER")
        assertEquals(2, withCategory.activeFacetCount)
    }
}
