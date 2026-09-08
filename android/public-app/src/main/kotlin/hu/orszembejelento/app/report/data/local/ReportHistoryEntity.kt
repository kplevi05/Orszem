package hu.orszembejelento.app.report.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import hu.orszembejelento.app.report.domain.PublicReportStatus
import hu.orszembejelento.app.report.domain.SubmissionState
import java.time.Instant
import java.util.UUID

/**
 * One local report-history row (Phase 5 brief §20).
 *
 * Doubles as the app's domain model for a history item - a separate mapping layer would
 * only duplicate this same set of fields (CLAUDE.md §13: do not abstract two things that
 * look similar). [encryptedCredentialBlob] is the only place the report access credential
 * ever touches disk, and only in its [KeystoreCryptoBox][hu.orszembejelento.app.report.data.crypto.KeystoreCryptoBox]-encrypted
 * form - see that class. No GPS coordinates, no free text, and no raw credential are ever
 * stored here.
 */
@Entity(tableName = "report_history")
data class ReportHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "client_submission_id")
    val clientSubmissionId: UUID,

    @ColumnInfo(name = "public_report_id")
    val publicReportId: UUID?,

    @ColumnInfo(name = "encrypted_credential_blob")
    val encryptedCredentialBlob: ByteArray,

    @ColumnInfo(name = "credential_crypto_version")
    val credentialCryptoVersion: Int,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Instant,

    @ColumnInfo(name = "train_identifier")
    val trainIdentifier: String?,

    @ColumnInfo(name = "settlement_id")
    val settlementId: UUID,

    @ColumnInfo(name = "settlement_name_snapshot")
    val settlementNameSnapshot: String,

    @ColumnInfo(name = "railway_line_id")
    val railwayLineId: UUID?,

    @ColumnInfo(name = "railway_line_display_snapshot")
    val railwayLineDisplaySnapshot: String?,

    @ColumnInfo(name = "category_display_snapshot")
    val categoryDisplaySnapshot: String,

    @ColumnInfo(name = "event_type_code")
    val eventTypeCode: String,

    @ColumnInfo(name = "event_type_display_snapshot")
    val eventTypeDisplaySnapshot: String,

    @ColumnInfo(name = "submission_state")
    val submissionState: SubmissionState,

    @ColumnInfo(name = "public_status")
    val publicStatus: PublicReportStatus?,

    @ColumnInfo(name = "server_submitted_at")
    val serverSubmittedAt: Instant?,

    @ColumnInfo(name = "local_created_at")
    val localCreatedAt: Instant,

    @ColumnInfo(name = "last_status_checked_at")
    val lastStatusCheckedAt: Instant?,

    /** A stable, non-sensitive code the UI maps to Hungarian copy - never a raw exception message. */
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReportHistoryEntity) return false
        return clientSubmissionId == other.clientSubmissionId && publicReportId == other.publicReportId &&
            encryptedCredentialBlob.contentEquals(other.encryptedCredentialBlob) &&
            credentialCryptoVersion == other.credentialCryptoVersion && occurredAt == other.occurredAt &&
            trainIdentifier == other.trainIdentifier && settlementId == other.settlementId &&
            settlementNameSnapshot == other.settlementNameSnapshot && railwayLineId == other.railwayLineId &&
            railwayLineDisplaySnapshot == other.railwayLineDisplaySnapshot &&
            categoryDisplaySnapshot == other.categoryDisplaySnapshot && eventTypeCode == other.eventTypeCode &&
            eventTypeDisplaySnapshot == other.eventTypeDisplaySnapshot && submissionState == other.submissionState &&
            publicStatus == other.publicStatus && serverSubmittedAt == other.serverSubmittedAt &&
            localCreatedAt == other.localCreatedAt && lastStatusCheckedAt == other.lastStatusCheckedAt &&
            lastErrorCode == other.lastErrorCode
    }

    override fun hashCode(): Int = clientSubmissionId.hashCode()

    companion object {
        /** Bumped whenever [hu.orszembejelento.app.report.data.crypto.KeystoreCryptoBox]'s blob format changes. */
        const val CURRENT_CRYPTO_VERSION = 1
    }
}
