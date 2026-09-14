package hu.orszembejelento.service.audit

import hu.orszembejelento.service.audit.data.AuditFilter
import hu.orszembejelento.service.audit.data.AuditPeriod
import hu.orszembejelento.service.audit.ui.AuditListViewModel
import hu.orszembejelento.service.common.data.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Phase 12 brief §70 - default period, pagination append/reset, filter reset, error/retry, session isolation. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuditListViewModelTest {

    @Before
    fun setUpMainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    @After
    fun tearDownMainDispatcher() { Dispatchers.resetMain() }

    @Test
    fun `initial load fetches both events and options, defaulting to LAST_30_DAYS`() = runTest {
        val fake = FakeAuditRepository(eventsResult = ApiResult.Success(FakeAuditRepository.defaultPage(items = listOf(FakeAuditRepository.item()))))
        val vm = AuditListViewModel(fake, onSessionEnded = {})
        assertEquals(1, fake.eventsCalls)
        assertEquals(1, fake.optionsCalls)
        assertEquals(AuditPeriod.LAST_30_DAYS, fake.seenFilters.first().period)
        assertEquals(1, vm.state.value.items.size)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `updateFilter resets to page 0 and reloads with the new filter`() = runTest {
        val fake = FakeAuditRepository()
        val vm = AuditListViewModel(fake, onSessionEnded = {})

        vm.updateFilter(AuditFilter(period = AuditPeriod.TODAY, eventType = "USER_CREATED"))
        assertEquals(AuditPeriod.TODAY, fake.seenFilters.last().period)
        assertEquals("USER_CREATED", fake.seenFilters.last().eventType)
        assertEquals(0, fake.seenPages.last())
        assertEquals(2, fake.eventsCalls)
    }

    @Test
    fun `loadMore appends to the existing items and requests the next page`() = runTest {
        val page0 = FakeAuditRepository.defaultPage(items = listOf(FakeAuditRepository.item("a")), page = 0, totalPages = 2, totalElements = 2)
        val fake = FakeAuditRepository(eventsResult = ApiResult.Success(page0))
        val vm = AuditListViewModel(fake, onSessionEnded = {})
        assertTrue(vm.state.value.canLoadMore)

        fake.eventsResult = ApiResult.Success(FakeAuditRepository.defaultPage(items = listOf(FakeAuditRepository.item("b")), page = 1, totalPages = 2, totalElements = 2))
        vm.loadMore()
        assertEquals(listOf("a", "b"), vm.state.value.items.map { it.auditEventId })
        assertEquals(1, fake.seenPages.last())
    }

    @Test
    fun `refresh replaces the page rather than appending`() = runTest {
        val fake = FakeAuditRepository(eventsResult = ApiResult.Success(FakeAuditRepository.defaultPage(items = listOf(FakeAuditRepository.item("a")))))
        val vm = AuditListViewModel(fake, onSessionEnded = {})
        assertEquals(1, vm.state.value.items.size)

        fake.eventsResult = ApiResult.Success(FakeAuditRepository.defaultPage(items = listOf(FakeAuditRepository.item("b"))))
        vm.refresh()
        assertEquals(listOf("b"), vm.state.value.items.map { it.auditEventId })
    }

    @Test
    fun `a first-load network error surfaces as an error with no items yet`() = runTest {
        val fake = FakeAuditRepository(eventsResult = ApiResult.NetworkError)
        val vm = AuditListViewModel(fake, onSessionEnded = {})
        assertTrue(vm.state.value.error is ApiResult.NetworkError)
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun `a refresh failure after items already exist keeps the stale items visible`() = runTest {
        val fake = FakeAuditRepository(eventsResult = ApiResult.Success(FakeAuditRepository.defaultPage(items = listOf(FakeAuditRepository.item()))))
        val vm = AuditListViewModel(fake, onSessionEnded = {})
        assertEquals(1, vm.state.value.items.size)

        fake.eventsResult = ApiResult.NetworkError
        vm.refresh()
        assertTrue(vm.state.value.error is ApiResult.NetworkError)
        assertEquals(1, vm.state.value.items.size)
    }

    @Test
    fun `retrying after a failure calls the backend exactly once per tap, no accumulation`() = runTest {
        val fake = FakeAuditRepository(eventsResult = ApiResult.NetworkError)
        val vm = AuditListViewModel(fake, onSessionEnded = {})
        assertEquals(1, fake.eventsCalls)

        fake.eventsResult = ApiResult.Success(FakeAuditRepository.defaultPage(items = listOf(FakeAuditRepository.item())))
        vm.refresh()
        assertEquals(2, fake.eventsCalls)
        assertEquals(null, vm.state.value.error)
    }

    @Test
    fun `SessionEnded from the options call invokes the callback without ever calling events`() = runTest {
        var sessionEndedCalls = 0
        val fake = FakeAuditRepository(optionsResult = ApiResult.SessionEnded)
        val vm = AuditListViewModel(fake, onSessionEnded = { sessionEndedCalls++ })
        assertEquals(1, sessionEndedCalls)
        assertEquals(0, fake.eventsCalls)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `SessionEnded from the events call invokes the callback exactly once`() = runTest {
        var sessionEndedCalls = 0
        val fake = FakeAuditRepository(eventsResult = ApiResult.SessionEnded)
        AuditListViewModel(fake, onSessionEnded = { sessionEndedCalls++ })
        assertEquals(1, sessionEndedCalls)
    }

    @Test
    fun `loadMore is a no-op once there are no more pages`() = runTest {
        val fake = FakeAuditRepository(eventsResult = ApiResult.Success(FakeAuditRepository.defaultPage(items = listOf(FakeAuditRepository.item()), totalPages = 1)))
        val vm = AuditListViewModel(fake, onSessionEnded = {})
        assertFalse(vm.state.value.canLoadMore)

        vm.loadMore()
        assertEquals(1, fake.eventsCalls) // still just the initial load
    }

    @Test
    fun `AuditFilter activeFacetCount counts eventType, targetType and a non-blank query independently`() {
        assertEquals(0, AuditFilter().activeFacetCount)
        assertEquals(1, AuditFilter(eventType = "USER_CREATED").activeFacetCount)
        assertEquals(2, AuditFilter(eventType = "USER_CREATED", targetType = "USER").activeFacetCount)
        assertEquals(3, AuditFilter(eventType = "USER_CREATED", targetType = "USER", query = "SZ-100001").activeFacetCount)
        assertEquals(0, AuditFilter(query = "  ").activeFacetCount)
    }
}
