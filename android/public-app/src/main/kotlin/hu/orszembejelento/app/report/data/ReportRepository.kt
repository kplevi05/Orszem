package hu.orszembejelento.app.report.data

import hu.orszembejelento.app.report.data.crypto.CryptoBox
import hu.orszembejelento.app.report.data.local.ReportHistoryDao
import hu.orszembejelento.app.report.data.local.ReportHistoryEntity
import hu.orszembejelento.app.report.data.network.ApiErrorBody
import hu.orszembejelento.app.report.data.network.ApiErrorCode
import hu.orszembejelento.app.report.data.network.PublicApi
import hu.orszembejelento.app.report.data.network.SubmitReportRequestBody
import hu.orszembejelento.app.report.domain.PublicReportStatus
import hu.orszembejelento.app.report.domain.ReportAccessCredential
import hu.orszembejelento.app.report.domain.ReportDraft
import hu.orszembejelento.app.report.domain.SubmissionState
import hu.orszembejelento.app.report.domain.normalize
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import retrofit2.Response

/** Everything needed to render Step 2's "what you're about to submit" and the success screen. */
data class ReportDisplaySnapshot(
    val settlementName: String,
    val railwayLineDisplay: String?,
    val categoryDisplay: String,
    val eventTypeDisplay: String,
)

sealed class SubmitOutcome {
    data class Created(val entity: ReportHistoryEntity) : SubmitOutcome()
    data class Replayed(val entity: ReportHistoryEntity) : SubmitOutcome()

    /**
     * A definitive validation failure - the backend proved no report was created. Per §10
     * the attempt is not treated as history: the caller deletes this local record (already
     * done by the time this is returned) and returns the user to an editable form. A later
     * retry must use an entirely new identity, never this one.
     */
    data class ValidationFailed(val code: String) : SubmitOutcome()

    /** Timeout, connection failure, or an ambiguous/transient server error. The local PENDING record is unchanged. */
    data class AmbiguousFailure(val code: String?) : SubmitOutcome()

    /** 409 - the same `clientSubmissionId` is already bound to a different payload/credential. Should never happen. */
    data object Conflict : SubmitOutcome()

    /** The credential could not be produced/stored/decrypted at all - nothing was sent. */
    data object AccessLost : SubmitOutcome()

    /** Durable local persistence itself failed before any network attempt - the report was never sent (§5). */
    data object LocalPersistenceFailed : SubmitOutcome()
}

sealed class StatusRefreshOutcome {
    data class Updated(val status: PublicReportStatus) : StatusRefreshOutcome()
    data object Unavailable : StatusRefreshOutcome() // generic 404 - existence-safe, not interpreted further (§18)
    data object AccessLost : StatusRefreshOutcome()
    data object NetworkFailure : StatusRefreshOutcome() // cached status preserved
    data object NotSubmittedYet : StatusRefreshOutcome() // no publicReportId to check yet
}

/**
 * Orchestrates the whole submission lifecycle (Phase 5 brief §5-19). The one rule every
 * method here is built around: **local durable persistence of `clientSubmissionId` + the
 * access credential + the frozen normalized payload happens before the first network
 * attempt, unconditionally** - see [submit].
 *
 * There is intentionally no credential-recovery path: once [CryptoBox.decrypt] fails for a
 * stored blob, that record can only ever become [SubmissionState.ACCESS_LOST] - matching
 * the backend's own anonymous, account-less design (ADR 0008).
 */
class ReportRepository(
    private val dao: ReportHistoryDao,
    private val cryptoBox: CryptoBox,
    private val api: PublicApi,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val now: () -> Instant = Instant::now,
) {

    fun observeHistory(): Flow<List<ReportHistoryEntity>> = dao.observeAll()

    fun observe(clientSubmissionId: UUID): Flow<ReportHistoryEntity?> = dao.observe(clientSubmissionId)

    /**
     * Normalizes [draft], generates a fresh identity, persists a PENDING record, and only
     * then attempts delivery. If persistence itself fails, the report is never sent -
     * there is no plaintext or unpersisted fallback path (§5, §34's Android equivalent).
     */
    suspend fun submit(draft: ReportDraft, display: ReportDisplaySnapshot): SubmitOutcome {
        val normalized = draft.normalize()
        val clientSubmissionId = UUID.randomUUID()
        val credential = ReportAccessCredential.generate()

        val entity = runCatching {
            val encryptedBlob = cryptoBox.encrypt(credential.value.toByteArray(Charsets.UTF_8))
            ReportHistoryEntity(
                clientSubmissionId = clientSubmissionId,
                publicReportId = null,
                encryptedCredentialBlob = encryptedBlob,
                credentialCryptoVersion = ReportHistoryEntity.CURRENT_CRYPTO_VERSION,
                occurredAt = normalized.occurredAt,
                trainIdentifier = normalized.trainIdentifier,
                settlementId = normalized.settlementId,
                settlementNameSnapshot = display.settlementName,
                railwayLineId = normalized.railwayLineId,
                railwayLineDisplaySnapshot = display.railwayLineDisplay,
                categoryDisplaySnapshot = display.categoryDisplay,
                eventTypeCode = normalized.eventTypeCode,
                eventTypeDisplaySnapshot = display.eventTypeDisplay,
                submissionState = SubmissionState.PENDING,
                publicStatus = null,
                serverSubmittedAt = null,
                localCreatedAt = now(),
                lastStatusCheckedAt = null,
                lastErrorCode = null,
            ).also { dao.insert(it) }
        }.getOrNull() ?: return SubmitOutcome.LocalPersistenceFailed

        // Local commit has happened. Only now does the first network attempt begin.
        return deliver(entity, credential)
    }

    /** Resends the exact frozen identity/payload/credential already on record - never reconstructed from UI state. */
    suspend fun retry(clientSubmissionId: UUID): SubmitOutcome {
        val entity = dao.findByClientSubmissionId(clientSubmissionId) ?: return SubmitOutcome.LocalPersistenceFailed
        val credential = decryptCredential(entity) ?: run {
            dao.markState(clientSubmissionId, SubmissionState.ACCESS_LOST, null)
            return SubmitOutcome.AccessLost
        }
        return deliver(entity, credential)
    }

    private suspend fun deliver(entity: ReportHistoryEntity, credential: ReportAccessCredential): SubmitOutcome {
        val body = SubmitReportRequestBody(
            clientSubmissionId = entity.clientSubmissionId.toString(),
            occurredAt = entity.occurredAt.toString(),
            trainIdentifier = entity.trainIdentifier,
            settlementId = entity.settlementId.toString(),
            railwayLineId = entity.railwayLineId?.toString(),
            eventTypeCode = entity.eventTypeCode,
        )

        val response = runCatching { api.submitReport(body, credential.value) }
            .getOrElse { return SubmitOutcome.AmbiguousFailure(null) }

        return when (response.code()) {
            200, 201 -> {
                val payload = response.body() ?: return SubmitOutcome.AmbiguousFailure(null)
                val publicReportId = runCatching { UUID.fromString(payload.reportId) }.getOrNull()
                    ?: return SubmitOutcome.AmbiguousFailure(null)
                val submittedAt = runCatching { Instant.parse(payload.submittedAt) }.getOrNull()
                    ?: return SubmitOutcome.AmbiguousFailure(null)
                dao.markSubmitted(entity.clientSubmissionId, publicReportId, submittedAt, PublicReportStatus.RECEIVED)
                val updated = entity.copy(
                    submissionState = SubmissionState.SUBMITTED,
                    publicReportId = publicReportId,
                    serverSubmittedAt = submittedAt,
                    publicStatus = PublicReportStatus.RECEIVED,
                    lastErrorCode = null,
                )
                if (response.code() == 201) SubmitOutcome.Created(updated) else SubmitOutcome.Replayed(updated)
            }

            400 -> {
                // Definitive: the backend proved no report exists for this attempt. Not
                // history - delete the local record entirely (§10). A later attempt must
                // start over with a brand-new identity.
                val code = errorCode(response) ?: ApiErrorCode.VALIDATION_ERROR
                dao.deleteByClientSubmissionId(entity.clientSubmissionId)
                SubmitOutcome.ValidationFailed(code)
            }

            409 -> {
                dao.markState(entity.clientSubmissionId, SubmissionState.CONFLICT, ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
                SubmitOutcome.Conflict
            }

            503 -> {
                dao.markState(entity.clientSubmissionId, SubmissionState.PENDING, ApiErrorCode.REFERENCE_DATASET_UNAVAILABLE)
                SubmitOutcome.AmbiguousFailure(ApiErrorCode.REFERENCE_DATASET_UNAVAILABLE)
            }

            else -> {
                // Any other/ambiguous status (5xx, unexpected 4xx): stays PENDING, retryable.
                val code = errorCode(response)
                dao.markState(entity.clientSubmissionId, SubmissionState.PENDING, code)
                SubmitOutcome.AmbiguousFailure(code)
            }
        }
    }

    suspend fun refreshStatus(clientSubmissionId: UUID): StatusRefreshOutcome {
        val entity = dao.findByClientSubmissionId(clientSubmissionId) ?: return StatusRefreshOutcome.NotSubmittedYet
        val publicReportId = entity.publicReportId ?: return StatusRefreshOutcome.NotSubmittedYet
        val credential = decryptCredential(entity) ?: run {
            dao.markState(clientSubmissionId, SubmissionState.ACCESS_LOST, null)
            return StatusRefreshOutcome.AccessLost
        }

        val response = runCatching { api.getReport(publicReportId.toString(), credential.value) }
            .getOrElse {
                dao.markStatusCheckFailed(clientSubmissionId, now(), null)
                return StatusRefreshOutcome.NetworkFailure
            }

        return when (response.code()) {
            200 -> {
                val status = response.body()?.status?.let { runCatching { PublicReportStatus.valueOf(it) }.getOrNull() }
                    ?: return StatusRefreshOutcome.NetworkFailure
                dao.updateStatus(clientSubmissionId, status, now())
                StatusRefreshOutcome.Updated(status)
            }

            404 -> {
                // Existence-safe by design on the server - never interpreted as "the
                // credential must be wrong" or "the report must not exist" (§18). The
                // credential and history row are left untouched.
                dao.markStatusCheckFailed(clientSubmissionId, now(), ApiErrorCode.REPORT_NOT_FOUND)
                StatusRefreshOutcome.Unavailable
            }

            else -> {
                dao.markStatusCheckFailed(clientSubmissionId, now(), errorCode(response))
                StatusRefreshOutcome.NetworkFailure
            }
        }
    }

    /**
     * Refreshes several SUBMITTED records with a small bounded concurrency (§17) - never
     * one unbounded burst of requests.
     */
    suspend fun refreshAll(clientSubmissionIds: List<UUID>, concurrency: Int = 3) {
        coroutineScope {
            val semaphore = Semaphore(concurrency)
            clientSubmissionIds.map { id ->
                async { semaphore.withPermit { refreshStatus(id) } }
            }.forEach { it.await() }
        }
    }

    private fun decryptCredential(entity: ReportHistoryEntity): ReportAccessCredential? {
        val plaintext = cryptoBox.decrypt(entity.encryptedCredentialBlob) ?: return null
        return ReportAccessCredential.ofStored(String(plaintext, Charsets.UTF_8))
    }

    private fun errorCode(response: Response<*>): String? =
        response.errorBody()?.string()?.let { body ->
            runCatching { json.decodeFromString(ApiErrorBody.serializer(), body).code }.getOrNull()
        }
}
