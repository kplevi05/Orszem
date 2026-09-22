package hu.orszembejelento.app.ui.newreport

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import hu.orszembejelento.app.location.LocationAssist
import hu.orszembejelento.app.report.data.CatalogRepository
import hu.orszembejelento.app.report.data.ReferenceRepository
import hu.orszembejelento.app.report.data.ReportRepository
import hu.orszembejelento.app.report.data.crypto.CryptoBox
import hu.orszembejelento.app.report.data.local.ReportHistoryEntity
import hu.orszembejelento.app.report.data.local.ReportHistoryDao
import hu.orszembejelento.app.report.data.network.PublicApi
import hu.orszembejelento.app.report.data.network.RailwayLinesForSettlementResponseBody
import hu.orszembejelento.app.report.data.network.ReportCatalogResponseBody
import hu.orszembejelento.app.report.data.network.SettlementSearchResultBody
import hu.orszembejelento.app.report.data.network.SubmitReportRequestBody
import hu.orszembejelento.app.report.data.network.SubmitReportResponseBody
import hu.orszembejelento.app.report.data.network.PublicReportResponseBody
import hu.orszembejelento.app.report.domain.PublicReportStatus
import hu.orszembejelento.app.report.domain.SubmissionState
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * Field-test fix (§2): updating the settlement search results must never dismiss the
 * keyboard or steal focus from the [SettlementField] text box - the real defect was a
 * default, *focusable* [androidx.compose.material3.DropdownMenu] stealing window focus on
 * every result update, which is what actually closes the IME on a physical device (Compose
 * semantic focus alone does not model that, so these tests exercise the Compose-level focus
 * contract the fix is built on: the field never loses semantic focus across a result-driven
 * recomposition, which is a necessary condition for the window never losing focus either).
 */
class SettlementFocusComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun settlementField() = compose.onAllNodes(hasSetTextAction())[1]

    private fun setContent(api: PublicApi): NewReportViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val viewModel = NewReportViewModel(
            ReportRepository(NoOpReportHistoryDao(), NoOpCryptoBox(), api),
            CatalogRepository(api),
            ReferenceRepository(api),
            LocationAssist(context),
        )
        compose.setContent {
            val state by viewModel.state.collectAsState()
            Step1Content(state = state, viewModel = viewModel, onLocateMe = {})
        }
        return viewModel
    }

    @Test
    fun typing_and_the_arrival_of_search_results_never_moves_focus_off_the_settlement_field() {
        val api = FakeSettlementApi(listOf(SettlementSearchResultBody("11111111-1111-1111-1111-111111111111", "01234", "Alfaváros", null)))
        val viewModel = setContent(api)

        settlementField().performClick()
        settlementField().assertIsFocused()

        settlementField().performTextInput("Alfa")
        settlementField().assertIsFocused() // still focused the instant the keystroke lands

        // Wait on the underlying (debounced) search actually resolving, not on the popup's
        // own text becoming visible in the semantics tree - the two are usually in step, but
        // asserting on the ViewModel's own state is what actually isolates "did the search
        // complete" from "is the popup rendered", and is what this fix's real contract is
        // about: the popup update (whenever it happens) must never cost the field its focus.
        compose.waitUntil(timeoutMillis = 10_000) { viewModel.state.value.settlementResults.isNotEmpty() }
        compose.waitForIdle()
        // The debounced search resolved and the suggestion list is now showing - this is
        // exactly the moment the old focusable DropdownMenu used to steal window focus and
        // dismiss the keyboard. The field must still hold Compose focus here.
        settlementField().assertIsFocused()
    }

    @Test
    fun editing_a_previously_selected_settlement_clears_the_selection_but_keeps_focus_and_keeps_searching() {
        val api = FakeSettlementApi(
            listOf(SettlementSearchResultBody("11111111-1111-1111-1111-111111111111", "01234", "Alfaváros", null)),
            railwayLines = RailwayLinesForSettlementResponseBody("COMPLETE", emptyList()),
        )
        val viewModel = setContent(api)

        settlementField().performClick()
        settlementField().performTextInput("Alfa")
        compose.waitUntil(timeoutMillis = 10_000) { viewModel.state.value.settlementResults.isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText("Alfaváros", substring = true).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.state.value.selectedSettlement != null }

        // Now edit the selected settlement's text - this must clear the selection (§2) while
        // the field keeps focus and keeps searching, never falling back to plain unselected text.
        settlementField().performTextInput("s")
        settlementField().assertIsFocused()
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.state.value.selectedSettlement == null }
        // Unselected free text must never satisfy Step 1 validation (§2's last sentence).
        assertFalse("typed-but-unselected settlement text must not pass validation", viewModel.state.value.step1Valid)
    }

    private class FakeSettlementApi(
        private val results: List<SettlementSearchResultBody>,
        private val railwayLines: RailwayLinesForSettlementResponseBody = RailwayLinesForSettlementResponseBody("COMPLETE", emptyList()),
    ) : PublicApi {
        override suspend fun reportCatalog(): retrofit2.Response<ReportCatalogResponseBody> =
            retrofit2.Response.success(ReportCatalogResponseBody(emptyList()))

        override suspend fun searchSettlements(query: String): retrofit2.Response<List<SettlementSearchResultBody>> =
            retrofit2.Response.success(results.filter { it.name.contains(query, ignoreCase = true) })

        override suspend fun railwayLinesOfSettlement(settlementId: String): retrofit2.Response<RailwayLinesForSettlementResponseBody> =
            retrofit2.Response.success(railwayLines)

        override suspend fun submitReport(
            body: SubmitReportRequestBody,
            accessCredential: String,
        ): retrofit2.Response<SubmitReportResponseBody> =
            retrofit2.Response.error(500, "".toResponseBody("application/json".toMediaType()))

        override suspend fun getReport(publicReportId: String, accessCredential: String): retrofit2.Response<PublicReportResponseBody> =
            retrofit2.Response.error(404, "".toResponseBody("application/json".toMediaType()))
    }

    /** Never exercised by these tests (Step1Content never touches report persistence) - every member is a safe no-op. */
    private class NoOpReportHistoryDao : ReportHistoryDao {
        override suspend fun insert(entity: ReportHistoryEntity) {}
        override suspend fun findByClientSubmissionId(clientSubmissionId: UUID): ReportHistoryEntity? = null
        override fun observeAll(): Flow<List<ReportHistoryEntity>> = MutableStateFlow(emptyList())
        override fun observe(clientSubmissionId: UUID): Flow<ReportHistoryEntity?> = MutableStateFlow(null)
        override suspend fun update(entity: ReportHistoryEntity) {}
        override suspend fun deleteByClientSubmissionId(clientSubmissionId: UUID) {}
        override suspend fun markSubmitted(
            clientSubmissionId: UUID,
            publicReportId: UUID,
            serverSubmittedAt: Instant,
            publicStatus: PublicReportStatus,
        ) {}
        override suspend fun markState(clientSubmissionId: UUID, state: SubmissionState, errorCode: String?) {}
        override suspend fun updateStatus(clientSubmissionId: UUID, status: PublicReportStatus, checkedAt: Instant) {}
        override suspend fun markStatusCheckFailed(clientSubmissionId: UUID, checkedAt: Instant, errorCode: String?) {}
    }

    /** Never exercised by these tests - Step1Content never encrypts anything. */
    private class NoOpCryptoBox : CryptoBox {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
        override fun decrypt(blob: ByteArray): ByteArray? = blob
    }
}
