package hu.orszem.serviceapp

import com.google.common.truth.Truth.assertThat
import hu.orszem.core.common.Outcome
import hu.orszem.core.network.OrszemApi
import hu.orszem.core.network.dto.EventTypeEmbeddedDto
import hu.orszem.core.network.dto.ReportListResponseDto
import hu.orszem.core.network.dto.ServiceReportListItemDto
import hu.orszem.core.testing.MainDispatcherRule
import hu.orszem.serviceapp.data.ServiceReportRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import retrofit2.Response

/**
 * Demo v1.1 §C/§D — the client must consume the WHOLE active dataset through the
 * existing opaque cursor, not just the first page. These tests pin that down at
 * the repository boundary, where the cursor is actually followed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceReportRepositoryPagingTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val api = mockk<OrszemApi>()
    private val repository = ServiceReportRepository(api, dispatcherRule)

    private fun item(id: String) = ServiceReportListItemDto(
        id = id,
        eventType = EventTypeEmbeddedDto("FIGHT", "Verekedés", "VIOLENCE_DANGER", "Erőszak és közvetlen veszély"),
        trainIdentifier = "IC 123",
        settlement = "Budapest",
        occurredAt = "2026-09-01T10:00:00Z",
        receivedAt = "2026-09-01T10:01:00Z",
        status = "NEW",
    )

    private fun page(ids: List<String>, next: String?) =
        Response.success(ReportListResponseDto(ids.map(::item), next))

    @Test
    fun `every cursor page is followed until the cursor is exhausted`() = runTest {
        coEvery { api.activeReports(any(), null, any()) } returns page(listOf("a", "b"), "cursor-1")
        coEvery { api.activeReports(any(), "cursor-1", any()) } returns page(listOf("c", "d"), "cursor-2")
        coEvery { api.activeReports(any(), "cursor-2", any()) } returns page(listOf("e"), null)

        val outcome = repository.allActiveReports()

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        val ids = (outcome as Outcome.Success).value.map { it.id }
        assertThat(ids).containsExactly("a", "b", "c", "d", "e").inOrder()
        coVerify(exactly = 3) { api.activeReports(any(), any(), any()) }
    }

    @Test
    fun `a single page with no cursor is one request`() = runTest {
        coEvery { api.activeReports(any(), null, any()) } returns page(listOf("a"), null)
        val outcome = repository.allActiveReports()
        assertThat((outcome as Outcome.Success).value).hasSize(1)
        coVerify(exactly = 1) { api.activeReports(any(), any(), any()) }
    }

    @Test
    fun `a report repeated across pages is only counted once`() = runTest {
        // A report accepted between two fetches can shift and be returned twice.
        coEvery { api.activeReports(any(), null, any()) } returns page(listOf("a", "b"), "cursor-1")
        coEvery { api.activeReports(any(), "cursor-1", any()) } returns page(listOf("b", "c"), null)

        val outcome = repository.allActiveReports()

        assertThat((outcome as Outcome.Success).value.map { it.id }).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `a failure on a later page fails the whole load rather than returning a partial dataset`() = runTest {
        coEvery { api.activeReports(any(), null, any()) } returns page(listOf("a"), "cursor-1")
        coEvery { api.activeReports(any(), "cursor-1", any()) } returns
            Response.error(500, "".toResponseBody("application/problem+json".toMediaType()))

        val outcome = repository.allActiveReports()

        // Silently sorting half the data would be worse than reporting the failure.
        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test
    fun `paging stops at the safety limit even if the cursor never ends`() = runTest {
        coEvery { api.activeReports(any(), any(), any()) } returns page(listOf("a"), "always-more")

        val outcome = repository.allActiveReports(maxPages = 3)

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        coVerify(exactly = 3) { api.activeReports(any(), any(), any()) }
    }
}
