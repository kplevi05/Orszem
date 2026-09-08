package hu.orszembejelento.app.report

import hu.orszembejelento.app.report.data.local.ReportHistoryDao
import hu.orszembejelento.app.report.data.local.ReportHistoryEntity
import hu.orszembejelento.app.report.domain.PublicReportStatus
import hu.orszembejelento.app.report.domain.SubmissionState
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * A plain in-memory fake of [ReportHistoryDao] for JVM unit tests - no Robolectric, no
 * device. This tests the app's OWN ordering/state-transition logic against the exact same
 * interface the real (Room-backed, SQL-driven) implementation satisfies; the real SQL
 * behaviour itself (ordering, constraints, migrations) is proven separately by the
 * instrumented test suite against a real on-device Room database - see
 * `ReportHistoryDatabaseInstrumentedTest`.
 */
class FakeReportHistoryDao(private val callLog: MutableList<String>? = null) : ReportHistoryDao {

    private val rows = ConcurrentHashMap<UUID, ReportHistoryEntity>()
    private val stateFlow = MutableStateFlow(emptyList<ReportHistoryEntity>())

    private fun publish() {
        stateFlow.value = rows.values.sortedByDescending { it.localCreatedAt }
    }

    override suspend fun insert(entity: ReportHistoryEntity) {
        callLog?.add("dao.insert")
        check(rows.putIfAbsent(entity.clientSubmissionId, entity) == null) {
            "duplicate clientSubmissionId ${entity.clientSubmissionId}"
        }
        publish()
    }

    override suspend fun findByClientSubmissionId(clientSubmissionId: UUID): ReportHistoryEntity? = rows[clientSubmissionId]

    override fun observeAll(): StateFlow<List<ReportHistoryEntity>> = stateFlow

    override fun observe(clientSubmissionId: UUID) = stateFlow.map { list -> list.firstOrNull { it.clientSubmissionId == clientSubmissionId } }

    override suspend fun update(entity: ReportHistoryEntity) {
        rows[entity.clientSubmissionId] = entity
        publish()
    }

    override suspend fun deleteByClientSubmissionId(clientSubmissionId: UUID) {
        rows.remove(clientSubmissionId)
        publish()
    }

    override suspend fun markSubmitted(
        clientSubmissionId: UUID,
        publicReportId: UUID,
        serverSubmittedAt: Instant,
        publicStatus: PublicReportStatus,
    ) {
        rows[clientSubmissionId]?.let {
            rows[clientSubmissionId] = it.copy(
                submissionState = SubmissionState.SUBMITTED,
                publicReportId = publicReportId,
                serverSubmittedAt = serverSubmittedAt,
                publicStatus = publicStatus,
                lastErrorCode = null,
            )
            publish()
        }
    }

    override suspend fun markState(clientSubmissionId: UUID, state: SubmissionState, errorCode: String?) {
        rows[clientSubmissionId]?.let {
            rows[clientSubmissionId] = it.copy(submissionState = state, lastErrorCode = errorCode)
            publish()
        }
    }

    override suspend fun updateStatus(clientSubmissionId: UUID, status: PublicReportStatus, checkedAt: Instant) {
        rows[clientSubmissionId]?.let {
            rows[clientSubmissionId] = it.copy(publicStatus = status, lastStatusCheckedAt = checkedAt, lastErrorCode = null)
            publish()
        }
    }

    override suspend fun markStatusCheckFailed(clientSubmissionId: UUID, checkedAt: Instant, errorCode: String?) {
        rows[clientSubmissionId]?.let {
            rows[clientSubmissionId] = it.copy(lastStatusCheckedAt = checkedAt, lastErrorCode = errorCode)
            publish()
        }
    }
}
