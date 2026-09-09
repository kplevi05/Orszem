package hu.orszembejelento.backend.reports.domain

import hu.orszembejelento.backend.routing.domain.UnclassifiedReason
import java.time.Instant
import java.util.UUID

/** Internal workflow state. Never exposed to a Public client - see [PublicReportStatus]. */
enum class ReportStatus { NEW, IN_PROGRESS, ARCHIVED }

/** What the Public status API shows instead of [ReportStatus] - ADR 0008, docs/product/EVENT_CATALOG_V2.md's sibling. */
enum class PublicReportStatus { RECEIVED, PROCESSING, CLOSED }

/**
 * Maps internal workflow state to the Public vocabulary.
 *
 * `NEW` is never sent to a Public client, by name or by inference - a client must not be
 * able to distinguish "just received" from any other pre-processing state by watching for
 * a specific string. A future moderation-deleted report also maps to `CLOSED`, not a
 * distinct public value (not implemented yet - see ADR 0008).
 */
fun ReportStatus.toPublic(): PublicReportStatus = when (this) {
    ReportStatus.NEW -> PublicReportStatus.RECEIVED
    ReportStatus.IN_PROGRESS -> PublicReportStatus.PROCESSING
    ReportStatus.ARCHIVED -> PublicReportStatus.CLOSED
}

/**
 * An anonymous Public report.
 *
 * [id] is the internal identity - used in FKs and joins, and **never** returned to a
 * client. [publicId] is the only identity a Public client is ever given. Keeping the two
 * separate means the internal key can be used freely without ever becoming an API
 * contract.
 *
 * [clientSubmissionId] is the idempotency key: a `UNIQUE` database constraint, not an
 * application-level check, makes "one report per client submission attempt" true even
 * under concurrent retries - see `SubmitReportUseCase`.
 *
 * [publicAccessCredentialHash] is SHA-256 of the client-generated access credential
 * ([PublicReportAccessCredential]) - never the credential itself.
 */
data class Report(
    val id: UUID,
    val publicId: UUID,
    val clientSubmissionId: UUID,
    val publicAccessCredentialHash: ByteArray,
    val occurredAt: Instant,
    val submittedAt: Instant,
    val trainIdentifier: String?,
    val settlementId: UUID,
    val submittedRailwayLineId: UUID?,
    val eventTypeCode: String,
    val status: ReportStatus,
    // Phase 7 workflow state (V004). Defaulted so every existing Phase 4 call site that
    // constructs a freshly-submitted Report - always NEW, unassigned, version 0, never
    // archived - needs no change at all; these are exactly the database column defaults.
    val assignedUserId: UUID? = null,
    val workflowVersion: Long = 0,
    val archivedAt: Instant? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Report) return false
        return id == other.id && publicId == other.publicId && clientSubmissionId == other.clientSubmissionId &&
            publicAccessCredentialHash.contentEquals(other.publicAccessCredentialHash) &&
            occurredAt == other.occurredAt && submittedAt == other.submittedAt &&
            trainIdentifier == other.trainIdentifier && settlementId == other.settlementId &&
            submittedRailwayLineId == other.submittedRailwayLineId && eventTypeCode == other.eventTypeCode &&
            status == other.status && assignedUserId == other.assignedUserId &&
            workflowVersion == other.workflowVersion && archivedAt == other.archivedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + publicId.hashCode()
        result = 31 * result + clientSubmissionId.hashCode()
        return result
    }

    /** The business fields idempotency compares - see [NormalizedReportPayload]. */
    fun toNormalizedPayload() = NormalizedReportPayload(
        occurredAt = occurredAt,
        trainIdentifier = trainIdentifier,
        settlementId = settlementId,
        railwayLineId = submittedRailwayLineId,
        eventTypeCode = eventTypeCode,
    )
}

/**
 * The business fields a `clientSubmissionId` replay is compared against - deliberately
 * **not** a hash of the raw request. Comparing normalized fields directly means JSON
 * whitespace, property order, and equivalent textual timestamp representations can never
 * change idempotency semantics (they normalize to the same [Instant] before comparison, if
 * they mean the same instant). `eventTypeCode` is compared, never the client-derived
 * category, because the category is never part of the client payload in the first place -
 * see `SubmitReportRequest`.
 */
data class NormalizedReportPayload(
    val occurredAt: Instant,
    val trainIdentifier: String?,
    val settlementId: UUID,
    val railwayLineId: UUID?,
    val eventTypeCode: String,
)

/**
 * The routing decision computed once, at submission time, for a [Report] -
 * `RoutingService`'s result (ADR 0007) as it was persisted.
 *
 * This is the **current** snapshot, not event sourcing: a later phase that deliberately
 * re-routes a `NEW` report overwrites this row, and nothing here records what the prior
 * value was. Historical/audit concerns for routing changes are a separate, later decision
 * (ADR 0008).
 *
 * [referenceDatasetVersion] names the reference-state revision `RoutingService` consulted
 * when this snapshot was computed - not that every fact used in the decision originated in
 * that specific import. Carried forward unchanged from ADR 0007 Decision 2.
 */
data class ReportRoutingSnapshot(
    val reportId: UUID,
    val routingStatus: RoutingSnapshotStatus,
    val routingReason: UnclassifiedReason?,
    val resolvedRailwayLineId: UUID?,
    val serviceAreaId: UUID?,
    val referenceDatasetVersion: String,
    val routedAt: Instant,
) {
    init {
        when (routingStatus) {
            RoutingSnapshotStatus.ROUTED -> require(
                serviceAreaId != null && resolvedRailwayLineId != null && routingReason == null,
            ) { "a ROUTED snapshot must name an area and a line, and carry no reason" }
            RoutingSnapshotStatus.UNCLASSIFIED -> require(
                serviceAreaId == null && routingReason != null,
            ) { "an UNCLASSIFIED snapshot must carry a reason and never an area" }
        }
    }
}

enum class RoutingSnapshotStatus { ROUTED, UNCLASSIFIED }
