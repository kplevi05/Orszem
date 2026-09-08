package hu.orszembejelento.app.report.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import hu.orszembejelento.app.report.domain.PublicReportStatus
import hu.orszembejelento.app.report.domain.SubmissionState
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Dao
interface ReportHistoryDao {

    /**
     * Aborts on a primary-key conflict rather than replacing - a `clientSubmissionId`
     * collision would mean two different report attempts landed on the same id, which
     * must never happen with a correctly generated UUID, so this fails loudly instead of
     * silently overwriting frozen retry state.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ReportHistoryEntity)

    @Query("SELECT * FROM report_history WHERE client_submission_id = :clientSubmissionId")
    suspend fun findByClientSubmissionId(clientSubmissionId: UUID): ReportHistoryEntity?

    @Query("SELECT * FROM report_history ORDER BY local_created_at DESC")
    fun observeAll(): Flow<List<ReportHistoryEntity>>

    @Query("SELECT * FROM report_history WHERE client_submission_id = :clientSubmissionId")
    fun observe(clientSubmissionId: UUID): Flow<ReportHistoryEntity?>

    @Update
    suspend fun update(entity: ReportHistoryEntity)

    /**
     * Removes a record entirely - used only when a definitive validation failure proves
     * no report was ever created for it (§10). A retryable/PENDING record is never deleted
     * this way; it is only ever updated in place.
     */
    @Query("DELETE FROM report_history WHERE client_submission_id = :clientSubmissionId")
    suspend fun deleteByClientSubmissionId(clientSubmissionId: UUID)

    @Query(
        "UPDATE report_history SET submission_state = 'SUBMITTED', public_report_id = :publicReportId, " +
            "server_submitted_at = :serverSubmittedAt, public_status = :publicStatus, last_error_code = NULL " +
            "WHERE client_submission_id = :clientSubmissionId",
    )
    suspend fun markSubmitted(
        clientSubmissionId: UUID,
        publicReportId: UUID,
        serverSubmittedAt: Instant,
        publicStatus: PublicReportStatus,
    )

    @Query(
        "UPDATE report_history SET submission_state = :state, last_error_code = :errorCode " +
            "WHERE client_submission_id = :clientSubmissionId",
    )
    suspend fun markState(clientSubmissionId: UUID, state: SubmissionState, errorCode: String?)

    @Query(
        "UPDATE report_history SET public_status = :status, last_status_checked_at = :checkedAt, last_error_code = NULL " +
            "WHERE client_submission_id = :clientSubmissionId",
    )
    suspend fun updateStatus(clientSubmissionId: UUID, status: PublicReportStatus, checkedAt: Instant)

    @Query(
        "UPDATE report_history SET last_status_checked_at = :checkedAt, last_error_code = :errorCode " +
            "WHERE client_submission_id = :clientSubmissionId",
    )
    suspend fun markStatusCheckFailed(clientSubmissionId: UUID, checkedAt: Instant, errorCode: String?)
}
