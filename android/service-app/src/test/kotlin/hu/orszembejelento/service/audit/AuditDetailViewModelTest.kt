package hu.orszembejelento.service.audit

import hu.orszembejelento.service.audit.ui.AuditDetailViewModel
import hu.orszembejelento.service.common.data.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuditDetailViewModelTest {

    @Before
    fun setUpMainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    @After
    fun tearDownMainDispatcher() { Dispatchers.resetMain() }

    @Test
    fun `initial load fetches the requested event id`() = runTest {
        val fake = FakeAuditRepository(detailResult = ApiResult.Success(FakeAuditRepository.defaultDetail("evt-42")))
        val vm = AuditDetailViewModel("evt-42", fake, onSessionEnded = {})
        assertEquals(listOf("evt-42"), fake.seenDetailIds)
        assertEquals("evt-42", vm.state.value.detail?.auditEventId)
    }

    @Test
    fun `a network error surfaces with no detail yet`() = runTest {
        val fake = FakeAuditRepository(detailResult = ApiResult.NetworkError)
        val vm = AuditDetailViewModel("evt-1", fake, onSessionEnded = {})
        assertTrue(vm.state.value.error is ApiResult.NetworkError)
        assertEquals(null, vm.state.value.detail)
    }

    @Test
    fun `retrying after a failure calls the backend exactly once per tap`() = runTest {
        val fake = FakeAuditRepository(detailResult = ApiResult.NetworkError)
        val vm = AuditDetailViewModel("evt-1", fake, onSessionEnded = {})
        assertEquals(1, fake.detailCalls)

        fake.detailResult = ApiResult.Success(FakeAuditRepository.defaultDetail("evt-1"))
        vm.load()
        assertEquals(2, fake.detailCalls)
        assertEquals(null, vm.state.value.error)
    }

    @Test
    fun `a not-found event surfaces as a Failure error`() = runTest {
        val fake = FakeAuditRepository(detailResult = ApiResult.Failure(code = "AUDIT_EVENT_NOT_FOUND", httpStatus = 404))
        val vm = AuditDetailViewModel("evt-missing", fake, onSessionEnded = {})
        assertTrue(vm.state.value.error is ApiResult.Failure)
        assertEquals("AUDIT_EVENT_NOT_FOUND", (vm.state.value.error as ApiResult.Failure).code)
    }

    @Test
    fun `SessionEnded invokes the callback exactly once`() = runTest {
        var sessionEndedCalls = 0
        val fake = FakeAuditRepository(detailResult = ApiResult.SessionEnded)
        AuditDetailViewModel("evt-1", fake, onSessionEnded = { sessionEndedCalls++ })
        assertEquals(1, sessionEndedCalls)
    }
}
