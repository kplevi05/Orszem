package hu.orszembejelento.backend.reports.application

import hu.orszembejelento.backend.common.config.ReportSubmissionProperties
import hu.orszembejelento.backend.reference.domain.ReferenceDatasetUnavailableException
import hu.orszembejelento.backend.reference.infrastructure.JdbcReferenceRepository
import hu.orszembejelento.backend.reports.domain.IdempotencyKeyReusedException
import hu.orszembejelento.backend.reports.domain.InvalidEventTypeException
import hu.orszembejelento.backend.reports.domain.InvalidRailwayLineException
import hu.orszembejelento.backend.reports.domain.InvalidReportAccessCredentialException
import hu.orszembejelento.backend.reports.domain.InvalidSettlementException
import hu.orszembejelento.backend.reports.domain.NormalizedReportPayload
import hu.orszembejelento.backend.reports.domain.OccurredAtTooFarInFutureException
import hu.orszembejelento.backend.reports.domain.PublicReportAccessCredential
import hu.orszembejelento.backend.reports.domain.PublicReportAccessCredentialHasher
import hu.orszembejelento.backend.reports.domain.Report
import hu.orszembejelento.backend.reports.domain.ReportRoutingSnapshot
import hu.orszembejelento.backend.reports.domain.ReportStatus
import hu.orszembejelento.backend.reports.domain.RoutingSnapshotStatus
import hu.orszembejelento.backend.reports.domain.TrainIdentifierTooLongException
import hu.orszembejelento.backend.reports.infrastructure.JdbcEventCatalogRepository
import hu.orszembejelento.backend.reports.infrastructure.JdbcReportRepository
import hu.orszembejelento.backend.routing.application.RoutingService
import hu.orszembejelento.backend.routing.domain.RoutingOutcome
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** What the client sent, before normalization. See [SubmitReportUseCase] for what happens to it. */
data class SubmitReportCommand(
    val clientSubmissionId: UUID,
    val occurredAt: Instant,
    val trainIdentifier: String?,
    val settlementId: UUID,
    val railwayLineId: UUID?,
    val eventTypeCode: String,
)

sealed class SubmitReportOutcome {
    /** A genuinely new report was created by this call. HTTP 201. */
    data class Created(val report: Report) : SubmitReportOutcome()

    /** An identical, already-existing report was recognised by its `clientSubmissionId`. HTTP 200. */
    data class Replayed(val report: Report) : SubmitReportOutcome()
}

/**
 * Anonymous Public report creation, with client-driven idempotency (ADR 0008).
 *
 * The order below is deliberate and load-bearing, not incidental:
 *
 * 1. The credential's *shape* is checked before anything else touches the database - a
 *    malformed credential can never match a stored hash, and rejecting it up front leaks
 *    nothing about whether `clientSubmissionId` already exists.
 * 2. The request is normalized (trimmed/blank-to-null `trainIdentifier`) and looked up by
 *    `clientSubmissionId` **before** any business validation runs. A replay is recognised
 *    and answered from what was already stored, never re-validated against today's
 *    settlement/event-type/reference state - a settlement that was active on day 1 and is
 *    inactive by the time of a day-30 retry must still return the original report, not a
 *    fresh `INVALID_SETTLEMENT`.
 * 3. Only once no report exists for that `clientSubmissionId` does this validate
 *    settlement/event-type/railway-line identities, acquire the shared reference-state
 *    lock, route, and insert - all in the one transaction this method runs in.
 *
 * Concurrency: [JdbcReportRepository.tryInsert] relies on the `UNIQUE` index on
 * `client_submission_id` via `ON CONFLICT DO NOTHING`, not an application-level
 * check-then-insert. Two concurrent identical submissions converge on the same row (one
 * wins the insert, the other observes the conflict and replays it); two concurrent
 * submissions sharing an id but disagreeing on payload or credential leave exactly one
 * winner and the other gets [IdempotencyKeyReusedException] - see the test suite for the
 * exact races proved.
 */
@Service
class SubmitReportUseCase(
    private val reportRepository: JdbcReportRepository,
    private val eventCatalogRepository: JdbcEventCatalogRepository,
    private val referenceRepository: JdbcReferenceRepository,
    private val routingService: RoutingService,
    private val properties: ReportSubmissionProperties,
    private val clock: Clock,
) {

    @Transactional
    fun submit(command: SubmitReportCommand, suppliedCredentialHeader: String?): SubmitReportOutcome {
        val credential = PublicReportAccessCredential.parseOrNull(suppliedCredentialHeader)
            ?: throw InvalidReportAccessCredentialException()

        val normalized = normalize(command)

        val existing = reportRepository.findByClientSubmissionId(command.clientSubmissionId)
        if (existing != null) {
            return replayOrConflict(existing, normalized, credential)
        }

        // Fresh-creation path only below. Nothing above this line has touched anything
        // that could conflict with a concurrent reference import.
        if (isMateriallyFuture(normalized.occurredAt)) throw OccurredAtTooFarInFutureException()

        val eventType = eventCatalogRepository.findEventTypeByCode(normalized.eventTypeCode)
        if (eventType == null || !eventType.active) throw InvalidEventTypeException()

        // Everything from here on reads reference/service-area state that a concurrent
        // import could be changing, so the shared lock (ADR 0008) is acquired before any
        // of it - settlement/line validation and routing must all see one consistent
        // reference-state revision, never a mix of what an in-flight import is removing
        // and what it is adding.
        referenceRepository.acquireSharedReferenceStateLock()

        val settlement = referenceRepository.findSettlementById(normalized.settlementId)
        if (settlement == null || !settlement.active) throw InvalidSettlementException()

        if (normalized.railwayLineId != null &&
            referenceRepository.findRailwayLineById(normalized.railwayLineId) == null
        ) {
            throw InvalidRailwayLineException()
        }

        val routingOutcome = routingService.route(normalized.settlementId, normalized.railwayLineId)
        if (routingOutcome is RoutingOutcome.ReferenceDatasetUnavailable) {
            throw ReferenceDatasetUnavailableException()
        }

        val now = clock.instant()
        val report = Report(
            id = UUID.randomUUID(),
            publicId = UUID.randomUUID(),
            clientSubmissionId = command.clientSubmissionId,
            publicAccessCredentialHash = PublicReportAccessCredentialHasher.hash(credential),
            occurredAt = normalized.occurredAt,
            submittedAt = now,
            trainIdentifier = normalized.trainIdentifier,
            settlementId = normalized.settlementId,
            submittedRailwayLineId = normalized.railwayLineId,
            eventTypeCode = normalized.eventTypeCode,
            status = ReportStatus.NEW,
        )

        // The race-safe step: a concurrent attempt with the same clientSubmissionId may
        // have won between our lookup above and here. If so, this is a no-op and we fall
        // back to the identical replay-or-conflict logic used on the sequential path.
        if (!reportRepository.tryInsert(report)) {
            val winner = reportRepository.findByClientSubmissionId(command.clientSubmissionId)
                ?: error("insert conflicted but no row exists for ${command.clientSubmissionId}")
            return replayOrConflict(winner, normalized, credential)
        }

        reportRepository.insertRoutingSnapshot(buildSnapshot(report.id, routingOutcome, now))

        return SubmitReportOutcome.Created(report)
    }

    private fun replayOrConflict(
        existing: Report,
        normalized: NormalizedReportPayload,
        credential: PublicReportAccessCredential,
    ): SubmitReportOutcome {
        val payloadMatches = existing.toNormalizedPayload() == normalized
        val credentialMatches = PublicReportAccessCredentialHasher.matches(credential, existing.publicAccessCredentialHash)
        if (payloadMatches && credentialMatches) {
            return SubmitReportOutcome.Replayed(existing)
        }
        throw IdempotencyKeyReusedException()
    }

    private fun normalize(command: SubmitReportCommand): NormalizedReportPayload {
        val trainIdentifier = command.trainIdentifier?.trim()?.ifEmpty { null }
        if (trainIdentifier != null) {
            val codePoints = trainIdentifier.codePointCount(0, trainIdentifier.length)
            if (codePoints > properties.trainIdentifierMaxCodePoints) {
                throw TrainIdentifierTooLongException(properties.trainIdentifierMaxCodePoints)
            }
        }
        return NormalizedReportPayload(
            occurredAt = command.occurredAt,
            trainIdentifier = trainIdentifier,
            settlementId = command.settlementId,
            railwayLineId = command.railwayLineId,
            eventTypeCode = command.eventTypeCode,
        )
    }

    private fun isMateriallyFuture(occurredAt: Instant): Boolean =
        occurredAt.isAfter(clock.instant().plus(properties.occurredAtFutureTolerance))

    private fun buildSnapshot(reportId: UUID, outcome: RoutingOutcome, now: Instant): ReportRoutingSnapshot =
        when (outcome) {
            is RoutingOutcome.Routed -> ReportRoutingSnapshot(
                reportId = reportId,
                routingStatus = RoutingSnapshotStatus.ROUTED,
                routingReason = null,
                resolvedRailwayLineId = outcome.railwayLineId,
                serviceAreaId = outcome.serviceAreaId,
                referenceDatasetVersion = outcome.referenceDatasetVersion,
                routedAt = now,
            )
            is RoutingOutcome.Unclassified -> ReportRoutingSnapshot(
                reportId = reportId,
                routingStatus = RoutingSnapshotStatus.UNCLASSIFIED,
                routingReason = outcome.reason,
                // Present exactly when a line WAS resolved and only then found unassigned/
                // inactive/service-area-inactive; null for the reasons reached before any
                // line was resolved at all. See RoutingOutcome.Unclassified's KDoc.
                resolvedRailwayLineId = outcome.resolvedRailwayLineId,
                serviceAreaId = null,
                referenceDatasetVersion = outcome.referenceDatasetVersion,
                routedAt = now,
            )
            RoutingOutcome.ReferenceDatasetUnavailable ->
                error("unreachable: ReferenceDatasetUnavailable is handled before building a snapshot")
        }
}
