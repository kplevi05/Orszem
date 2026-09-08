package hu.orszembejelento.app.report

import hu.orszembejelento.app.report.data.ReportDisplaySnapshot
import hu.orszembejelento.app.report.data.ReportRepository
import hu.orszembejelento.app.report.data.StatusRefreshOutcome
import hu.orszembejelento.app.report.data.SubmitOutcome
import hu.orszembejelento.app.report.data.network.SubmitReportResponseBody
import hu.orszembejelento.app.report.domain.PublicReportStatus
import hu.orszembejelento.app.report.domain.ReportDraft
import hu.orszembejelento.app.report.domain.SubmissionState
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class ReportRepositoryTest {

    private val fixedNow = Instant.parse("2026-01-01T12:00:00Z")

    private fun draft(settlementId: UUID = UUID.randomUUID(), trainIdentifier: String? = "  G123  ") = ReportDraft(
        occurredAt = fixedNow,
        trainIdentifierInput = trainIdentifier,
        settlementId = settlementId,
        railwayLineId = null,
        eventTypeCode = "FIGHT",
    )

    private val display = ReportDisplaySnapshot("Alfaváros", null, "Kategória", "Verekedés")

    // ------------------------------------------------------- persist-before-network ordering

    @Test
    fun `the local record is committed before the first network attempt`() = runTest {
        val callLog = mutableListOf<String>()
        val dao = FakeReportHistoryDao(callLog)
        val api = FakePublicApi(callLog).apply {
            submitResponse = Response.success(201, SubmitReportResponseBody(UUID.randomUUID().toString(), fixedNow.toString(), "RECEIVED"))
        }
        val repository = ReportRepository(dao, FakeCryptoBox(), api, now = { fixedNow })

        repository.submit(draft(), display)

        assertEquals(listOf("dao.insert", "api.submitReport"), callLog)
    }

    @Test
    fun `a local persistence failure never calls the network at all`() = runTest {
        val api = FakePublicApi()
        val repository = ReportRepository(FakeReportHistoryDao(), FakeCryptoBox(failEncrypt = true), api, now = { fixedNow })

        val outcome = repository.submit(draft(), display)

        assertEquals(SubmitOutcome.LocalPersistenceFailed, outcome)
        assertEquals(0, api.submitCallCount)
    }

    // -------------------------------------------------------------------------- normalization

    @Test
    fun `the train identifier is trimmed before it is persisted and sent`() = runTest {
        val dao = FakeReportHistoryDao()
        val api = FakePublicApi().apply {
            submitResponse = Response.success(201, SubmitReportResponseBody(UUID.randomUUID().toString(), fixedNow.toString(), "RECEIVED"))
        }
        val repository = ReportRepository(dao, FakeCryptoBox(), api, now = { fixedNow })

        repository.submit(draft(trainIdentifier = "  G123  "), display)

        assertEquals("G123", api.lastSubmitBody?.trainIdentifier)
    }

    @Test
    fun `a blank train identifier normalizes to null`() = runTest {
        val dao = FakeReportHistoryDao()
        val api = FakePublicApi().apply {
            submitResponse = Response.success(201, SubmitReportResponseBody(UUID.randomUUID().toString(), fixedNow.toString(), "RECEIVED"))
        }
        val repository = ReportRepository(dao, FakeCryptoBox(), api, now = { fixedNow })

        repository.submit(draft(trainIdentifier = "   "), display)

        assertNull(api.lastSubmitBody?.trainIdentifier)
    }

    // ------------------------------------------------------------------------------- outcomes

    @Test
    fun `a 201 response marks the record SUBMITTED`() = runTest {
        val dao = FakeReportHistoryDao()
        val publicId = UUID.randomUUID()
        val api = FakePublicApi().apply {
            submitResponse = Response.success(201, SubmitReportResponseBody(publicId.toString(), fixedNow.toString(), "RECEIVED"))
        }
        val repository = ReportRepository(dao, FakeCryptoBox(), api, now = { fixedNow })

        val outcome = repository.submit(draft(), display)

        assertTrue(outcome is SubmitOutcome.Created)
        val stored = dao.findByClientSubmissionId((outcome as SubmitOutcome.Created).entity.clientSubmissionId)
        assertEquals(SubmissionState.SUBMITTED, stored?.submissionState)
        assertEquals(publicId, stored?.publicReportId)
        assertEquals(PublicReportStatus.RECEIVED, stored?.publicStatus)
    }

    @Test
    fun `an idempotent 200 replay also marks the record SUBMITTED, as Replayed`() = runTest {
        val dao = FakeReportHistoryDao()
        val api = FakePublicApi().apply {
            submitResponse = Response.success(200, SubmitReportResponseBody(UUID.randomUUID().toString(), fixedNow.toString(), "RECEIVED"))
        }
        val repository = ReportRepository(dao, FakeCryptoBox(), api, now = { fixedNow })

        val outcome = repository.submit(draft(), display)

        assertTrue(outcome is SubmitOutcome.Replayed)
    }

    @Test
    fun `a network failure (thrown exception) leaves the record PENDING`() = runTest {
        val dao = FakeReportHistoryDao()
        val api = FakePublicApi().apply { throwOnSubmit = IOException("boom") }
        val repository = ReportRepository(dao, FakeCryptoBox(), api, now = { fixedNow })
        val settlement = UUID.randomUUID()

        val outcome = repository.submit(draft(settlement), display)

        assertTrue(outcome is SubmitOutcome.AmbiguousFailure)
        val storedRow = requireNotNull(dao.findByClientSubmissionId(findOnlyId(dao)))
        assertEquals(SubmissionState.PENDING, storedRow.submissionState)
    }

    @Test
    fun `a definitive 400 deletes the record - it is never treated as history`() = runTest {
        val dao = FakeReportHistoryDao()
        val api = FakePublicApi().apply { submitResponse = FakePublicApi.errorResponse(400, """{"code":"INVALID_SETTLEMENT"}""") }
        val repository = ReportRepository(dao, FakeCryptoBox(), api, now = { fixedNow })

        val outcome = repository.submit(draft(), display)

        assertTrue(outcome is SubmitOutcome.ValidationFailed)
        assertEquals("INVALID_SETTLEMENT", (outcome as SubmitOutcome.ValidationFailed).code)
        assertTrue(dao.observeAll().value.isEmpty())
    }

    @Test
    fun `after a 400, a later submission attempt gets a brand new identity`() = runTest {
        val dao = FakeReportHistoryDao()
        val api = FakePublicApi().apply { submitResponse = FakePublicApi.errorResponse(400, """{"code":"INVALID_SETTLEMENT"}""") }
        val repository = ReportRepository(dao, FakeCryptoBox(), api, now = { fixedNow })

        repository.submit(draft(), display)
        val firstIds = dao.observeAll().value.map { it.clientSubmissionId }.toSet()

        api.submitResponse = Response.success(201, SubmitReportResponseBody(UUID.randomUUID().toString(), fixedNow.toString(), "RECEIVED"))
        val second = repository.submit(draft(), display) as SubmitOutcome.Created

        assertFalse(firstIds.contains(second.entity.clientSubmissionId))
    }

    @Test
    fun `a 409 marks the record CONFLICT`() = runTest {
        val dao = FakeReportHistoryDao()
        val api = FakePublicApi().apply { submitResponse = FakePublicApi.errorResponse(409, """{"code":"IDEMPOTENCY_KEY_REUSED"}""") }
        val repository = ReportRepository(dao, FakeCryptoBox(), api, now = { fixedNow })

        val outcome = repository.submit(draft(), display)

        assertEquals(SubmitOutcome.Conflict, outcome)
        val storedRow = requireNotNull(dao.findByClientSubmissionId(findOnlyId(dao)))
        assertEquals(SubmissionState.CONFLICT, storedRow.submissionState)
    }

    // ---------------------------------------------------------------------------------- retry

    @Test
    fun `retry resends the exact same clientSubmissionId, credential and normalized payload`() = runTest {
        val dao = FakeReportHistoryDao()
        val api = FakePublicApi().apply { throwOnSubmit = IOException("boom") }
        val repository = ReportRepository(dao, FakeCryptoBox(), api, now = { fixedNow })

        repository.submit(draft(), display)
        val id = findOnlyId(dao)
        val firstBody = api.lastSubmitBody
        val firstCredential = api.lastSubmitCredential

        api.throwOnSubmit = null
        api.submitResponse = Response.success(201, SubmitReportResponseBody(UUID.randomUUID().toString(), fixedNow.toString(), "RECEIVED"))
        repository.retry(id)

        assertEquals(firstBody, api.lastSubmitBody)
        assertEquals(firstCredential, api.lastSubmitCredential)
    }

    @Test
    fun `retry with an undecryptable credential marks the record ACCESS_LOST and never calls the network`() = runTest {
        val dao = FakeReportHistoryDao()
        val api = FakePublicApi()
        // First submit with a working crypto box so a PENDING row exists.
        val workingRepository = ReportRepository(dao, FakeCryptoBox(), api.apply { throwOnSubmit = IOException("boom") }, now = { fixedNow })
        workingRepository.submit(draft(), display)
        val id = findOnlyId(dao)

        val brokenRepository = ReportRepository(dao, FakeCryptoBox(failDecrypt = true), api, now = { fixedNow })
        val callsBeforeRetry = api.submitCallCount
        val outcome = brokenRepository.retry(id)

        assertEquals(SubmitOutcome.AccessLost, outcome)
        assertEquals(callsBeforeRetry, api.submitCallCount)
        assertEquals(SubmissionState.ACCESS_LOST, dao.findByClientSubmissionId(id)?.submissionState)
    }

    // ---------------------------------------------------------------------- status refresh

    @Test
    fun `a generic 404 on status refresh leaves the record untouched, per the existence-safe contract`() = runTest {
        val dao = FakeReportHistoryDao()
        val api = FakePublicApi().apply {
            submitResponse = Response.success(201, SubmitReportResponseBody(UUID.randomUUID().toString(), fixedNow.toString(), "RECEIVED"))
        }
        val repository = ReportRepository(dao, FakeCryptoBox(), api, now = { fixedNow })
        val created = repository.submit(draft(), display) as SubmitOutcome.Created

        api.getReportResponse = FakePublicApi.errorResponse(404, """{"code":"REPORT_NOT_FOUND"}""")
        val outcome = repository.refreshStatus(created.entity.clientSubmissionId)

        assertEquals(StatusRefreshOutcome.Unavailable, outcome)
        val stored = dao.findByClientSubmissionId(created.entity.clientSubmissionId)
        assertEquals(SubmissionState.SUBMITTED, stored?.submissionState) // never deleted, never marked access-lost
        assertNotNull(stored?.publicReportId)
    }

    // ------------------------------------------------------------------------------- ordering

    @Test
    fun `observed history is newest-first`() = runTest {
        val dao = FakeReportHistoryDao()
        val api = FakePublicApi().apply {
            submitResponse = Response.success(201, SubmitReportResponseBody(UUID.randomUUID().toString(), fixedNow.toString(), "RECEIVED"))
        }
        val repository = ReportRepository(dao, FakeCryptoBox(), api, now = { fixedNow.plusSeconds(1) })
        repository.submit(draft(), display)

        val repositoryEarlier = ReportRepository(dao, FakeCryptoBox(), api, now = { fixedNow })
        repositoryEarlier.submit(draft(), display)

        val timestamps = repository.observeHistory().first().map { it.localCreatedAt }
        assertEquals(timestamps.sortedDescending(), timestamps)
    }

    private fun findOnlyId(dao: FakeReportHistoryDao): UUID =
        dao.observeAll().value.single().clientSubmissionId
}
