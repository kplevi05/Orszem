package hu.orszembejelento.backend.audit.application

import hu.orszembejelento.backend.audit.domain.AuditActorIdentity
import hu.orszembejelento.backend.audit.domain.AuditDetailCode
import hu.orszembejelento.backend.audit.domain.AuditDetailItem
import hu.orszembejelento.backend.audit.domain.AuditEventDetailView
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditListItem
import hu.orszembejelento.backend.audit.domain.AuditTargetIdentity
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.AuditRawRow
import java.util.UUID
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The one place stored audit metadata is ever read (Phase 12 brief §5/§6) - every other layer
 * only ever sees the [AuditListItem]/[AuditEventDetailView] this produces. No endpoint, no DTO
 * `.from(...)` factory, and no test fixture anywhere else in Phase 12 touches
 * [AuditRawRow.metadataJson] or calls [ObjectMapper.readTree] on it - this class is that one
 * exception, by construction.
 *
 * The `when (row.eventType)` below is exhaustive over [AuditEventType] with no `else` branch -
 * the Kotlin compiler itself refuses to build if a new event type is added to the enum without
 * a matching branch here (brief §6's "explicit compile ... obligation"). An event type the
 * *database* can hold but this build's enum cannot parse ([AuditRawRow.eventType] `null`, a
 * forward-compatibility guard against a future writer, never reachable from today's closed
 * writer set) instead falls through to [genericListItem]/[genericDetail] - a safe title, safe
 * actor, safe target, and an empty detail array, never the raw stored bytes.
 *
 * Every metadata value that reaches an [AuditDetailItem] is read by an explicit JSON path
 * (`node.path("exactKey")`) - there is no loop over the stored object's own keys anywhere in
 * this file, so an unlisted key can never reach the client no matter what a future writer adds
 * to `metadata` (brief §27 - "unknown fields are omitted", proven by
 * `AuditUnknownMetadataLeakIT`).
 */
@Component
class AuditEventSafeProjector(private val objectMapper: ObjectMapper) {

    fun listItem(row: AuditRawRow, areaNames: Map<UUID, String>): AuditListItem {
        val eventType = row.eventType
            ?: return AuditListItem(row.id, row.createdAt, null, actorOf(row), genericTarget(row), emptyList())

        val node = metadataOf(row)
        val target = targetOf(row, node)
        return AuditListItem(
            auditEventId = row.id,
            occurredAt = row.createdAt,
            eventType = eventType,
            actor = actorOf(row),
            target = target,
            summary = summaryDetails(eventType, node, areaNames),
        )
    }

    fun detail(row: AuditRawRow, areaNames: Map<UUID, String>): AuditEventDetailView {
        val eventType = row.eventType
            ?: return AuditEventDetailView(row.id, row.createdAt, null, actorOf(row), genericTarget(row), emptyList())

        val node = metadataOf(row)
        return AuditEventDetailView(
            auditEventId = row.id,
            occurredAt = row.createdAt,
            eventType = eventType,
            actor = actorOf(row),
            target = targetOf(row, node),
            details = fullDetails(eventType, node, areaNames),
        )
    }

    /**
     * Every ServiceArea UUID a metadata value might embed, across the whole event, so the
     * caller can batch-resolve names in one query (brief §29) rather than one per row. Reads
     * the exact same whitelisted keys [fullDetails] itself reads - nothing broader.
     */
    fun embeddedServiceAreaIds(row: AuditRawRow): Set<UUID> {
        val eventType = row.eventType ?: return emptySet()
        val node = metadataOf(row)
        return when (eventType) {
            AuditEventType.USER_CREATED -> node.path("areaIds").asString("").split(",")
                .mapNotNull { it.trim().takeIf(String::isNotEmpty)?.let(::uuidOrNull) }.toSet()
            AuditEventType.USER_AREA_GRANTED, AuditEventType.USER_AREA_REVOKED ->
                setOfNotNull(uuidOrNull(node.path("areaId").asString(null)))
            AuditEventType.RAILWAY_LINE_SERVICE_AREA_ASSIGNED,
            AuditEventType.RAILWAY_LINE_SERVICE_AREA_MOVED,
            AuditEventType.RAILWAY_LINE_SERVICE_AREA_UNASSIGNED,
            AuditEventType.SETTLEMENT_LINE_SERVICE_AREA_CHANGED,
            -> setOfNotNull(uuidOrNull(node.path("fromAreaId").asString(null)), uuidOrNull(node.path("toAreaId").asString(null)))
            else -> emptySet()
        }
    }

    // --------------------------------------------------------------------------------- actor

    private fun actorOf(row: AuditRawRow): AuditActorIdentity = AuditActorIdentity(serviceId = row.actorServiceId)

    // -------------------------------------------------------------------------------- target

    private fun genericTarget(row: AuditRawRow) = AuditTargetIdentity(targetType = row.targetType, displayLabel = null)

    private fun targetOf(row: AuditRawRow, node: JsonNode): AuditTargetIdentity {
        val targetType = row.targetType ?: return AuditTargetIdentity(null, null)
        val label = when (targetType) {
            AuditTargetType.USER -> row.targetUserServiceId
            AuditTargetType.REPORT -> node.path("publicReportId").asString(null)?.let(::shortReportId)
            AuditTargetType.SERVICE_AREA -> row.targetAreaName
            AuditTargetType.RAILWAY_LINE -> railwayLineLabel(row.targetLineCode, row.targetLineDisplayName)
            // Neither carries a safe per-instance identifier worth showing (brief §11): a
            // session has no admin-facing code, and a reference-dataset import is a system
            // batch id, not something an admin would recognise. The type name alone
            // (Android's own targetTypeLabelRes) is already the safe representation.
            AuditTargetType.SESSION, AuditTargetType.REFERENCE_DATASET -> null
        }
        return AuditTargetIdentity(targetType, label)
    }

    private fun railwayLineLabel(code: String?, displayName: String?): String? = when {
        code != null && displayName != null -> "$code · $displayName"
        code != null -> code
        displayName != null -> displayName
        else -> null
    }

    private fun shortReportId(publicReportId: String): String = "#" + publicReportId.take(8).uppercase()

    // ------------------------------------------------------------------------------- details

    /** The full safe detail array for the detail screen (brief §22/§23/§54/§55). */
    private fun fullDetails(eventType: AuditEventType, node: JsonNode, areaNames: Map<UUID, String>): List<AuditDetailItem> =
        when (eventType) {
            AuditEventType.SUPER_ADMIN_CREATED -> listOfNotNull(sourceDetail(node))
            AuditEventType.SUPER_ADMIN_PASSWORD_RESET -> listOfNotNull(sourceDetail(node), revokedSessionsDetail(node))
            AuditEventType.INITIAL_PASSWORD_CHANGED -> emptyList()
            AuditEventType.PASSWORD_CHANGED -> listOfNotNull(revokedSessionsDetail(node))
            AuditEventType.SESSION_CREATED -> emptyList()
            AuditEventType.SESSION_REVOKED -> listOfNotNull(stringDetail(AuditDetailCode.REVOCATION_REASON, node.path("reason").asString(null)))
            AuditEventType.LOGOUT_ALL -> listOfNotNull(revokedSessionsDetail(node))
            AuditEventType.REFRESH_TOKEN_REUSE_DETECTED -> emptyList()
            AuditEventType.REFERENCE_DATASET_IMPORTED -> listOfNotNull(
                sourceDetail(node),
                stringDetail(AuditDetailCode.DATASET_VERSION, node.path("datasetVersion").asString(null)),
                stringDetail(AuditDetailCode.SETTLEMENTS_IMPORTED, node.path("settlements").asString(null)),
                stringDetail(AuditDetailCode.RAILWAY_LINES_IMPORTED, node.path("railwayLines").asString(null)),
                stringDetail(AuditDetailCode.MAPPINGS_IMPORTED, node.path("mappings").asString(null)),
            )

            AuditEventType.USER_CREATED -> listOfNotNull(
                stringDetail(AuditDetailCode.NEW_ROLE, node.path("role").asString(null)),
                areasDetail(node, "areaIds", areaNames),
                globalAccessDetail(node.path("globalAreaAccess").asString(null)),
            )
            AuditEventType.USER_PASSWORD_RESET -> listOfNotNull(revokedSessionsDetail(node))
            AuditEventType.USER_DEACTIVATED -> listOfNotNull(revokedSessionsDetail(node))
            AuditEventType.USER_REACTIVATED -> emptyList()
            AuditEventType.USER_ROLE_CHANGED -> listOfNotNull(
                stringDetail(AuditDetailCode.OLD_ROLE, node.path("oldRole").asString(null)),
                stringDetail(AuditDetailCode.NEW_ROLE, node.path("newRole").asString(null)),
            )
            AuditEventType.USER_AREA_GRANTED -> listOfNotNull(singleAreaDetail(AuditDetailCode.AREA, node, "areaId", areaNames))
            AuditEventType.USER_AREA_REVOKED -> listOfNotNull(singleAreaDetail(AuditDetailCode.AREA, node, "areaId", areaNames))
            AuditEventType.USER_GLOBAL_ACCESS_GRANTED -> emptyList()
            AuditEventType.USER_GLOBAL_ACCESS_REVOKED -> emptyList()

            AuditEventType.REPORT_CLAIMED -> listOfNotNull(
                stringDetail(AuditDetailCode.FROM_STATUS, node.path("fromStatus").asString(null)),
                stringDetail(AuditDetailCode.TO_STATUS, node.path("toStatus").asString(null)),
                stringDetail(AuditDetailCode.NEW_ASSIGNEE, node.path("newAssigneeServiceId").asString(null)),
            )
            AuditEventType.REPORT_RETURNED_TO_NEW -> listOfNotNull(
                stringDetail(AuditDetailCode.FROM_STATUS, node.path("fromStatus").asString(null)),
                stringDetail(AuditDetailCode.TO_STATUS, node.path("toStatus").asString(null)),
                stringDetail(AuditDetailCode.PREVIOUS_ASSIGNEE, node.path("previousAssigneeServiceId").asString(null)),
            )
            AuditEventType.REPORT_REASSIGNED -> listOfNotNull(
                stringDetail(AuditDetailCode.PREVIOUS_ASSIGNEE, node.path("previousAssigneeServiceId").asString(null)),
                stringDetail(AuditDetailCode.NEW_ASSIGNEE, node.path("newAssigneeServiceId").asString(null)),
            )
            AuditEventType.REPORT_ARCHIVED -> listOfNotNull(
                stringDetail(AuditDetailCode.FROM_STATUS, node.path("fromStatus").asString(null)),
                stringDetail(AuditDetailCode.TO_STATUS, node.path("toStatus").asString(null)),
                stringDetail(AuditDetailCode.PREVIOUS_ASSIGNEE, node.path("previousAssigneeServiceId").asString(null)),
            )

            AuditEventType.REPORT_MODERATION_DELETED -> listOfNotNull(
                stringDetail(AuditDetailCode.REASON, node.path("reason").asString(null)),
                stringDetail(AuditDetailCode.STATUS_BEFORE_DELETE, node.path("statusBeforeDelete").asString(null)),
            )
            AuditEventType.REPORT_MODERATION_RESTORED -> listOfNotNull(
                stringDetail(AuditDetailCode.STATUS_BEFORE_DELETE, node.path("statusBeforeDelete").asString(null)),
                stringDetail(AuditDetailCode.RESULTING_STATUS, node.path("resultingWorkflowStatus").asString(null)),
            )

            AuditEventType.SETTLEMENT_LINE_SERVICE_AREA_CHANGED -> listOfNotNull(
                singleAreaDetail(AuditDetailCode.FROM_AREA, node, "fromAreaId", areaNames),
                singleAreaDetail(AuditDetailCode.TO_AREA, node, "toAreaId", areaNames),
            )
            AuditEventType.SERVICE_AREA_CREATED -> emptyList()
            AuditEventType.SERVICE_AREA_RENAMED -> listOfNotNull(
                stringDetail(AuditDetailCode.OLD_NAME, node.path("oldName").asString(null)),
                stringDetail(AuditDetailCode.NEW_NAME, node.path("newName").asString(null)),
            )
            AuditEventType.SERVICE_AREA_ACTIVATED -> emptyList()
            AuditEventType.SERVICE_AREA_DEACTIVATED -> emptyList()

            AuditEventType.RAILWAY_LINE_SERVICE_AREA_ASSIGNED -> listOfNotNull(singleAreaDetail(AuditDetailCode.TO_AREA, node, "toAreaId", areaNames))
            AuditEventType.RAILWAY_LINE_SERVICE_AREA_MOVED -> listOfNotNull(
                singleAreaDetail(AuditDetailCode.FROM_AREA, node, "fromAreaId", areaNames),
                singleAreaDetail(AuditDetailCode.TO_AREA, node, "toAreaId", areaNames),
            )
            AuditEventType.RAILWAY_LINE_SERVICE_AREA_UNASSIGNED -> listOfNotNull(singleAreaDetail(AuditDetailCode.FROM_AREA, node, "fromAreaId", areaNames))
        }

    /** A short subset of [fullDetails] for the list row (brief §21/§51) - at most the one or two facts a card can show. */
    private fun summaryDetails(eventType: AuditEventType, node: JsonNode, areaNames: Map<UUID, String>): List<AuditDetailItem> =
        when (eventType) {
            AuditEventType.USER_ROLE_CHANGED -> listOfNotNull(
                stringDetail(AuditDetailCode.OLD_ROLE, node.path("oldRole").asString(null)),
                stringDetail(AuditDetailCode.NEW_ROLE, node.path("newRole").asString(null)),
            )
            AuditEventType.SERVICE_AREA_RENAMED -> listOfNotNull(
                stringDetail(AuditDetailCode.OLD_NAME, node.path("oldName").asString(null)),
                stringDetail(AuditDetailCode.NEW_NAME, node.path("newName").asString(null)),
            )
            AuditEventType.REPORT_MODERATION_DELETED -> listOfNotNull(stringDetail(AuditDetailCode.REASON, node.path("reason").asString(null)))
            AuditEventType.RAILWAY_LINE_SERVICE_AREA_MOVED -> listOfNotNull(
                singleAreaDetail(AuditDetailCode.FROM_AREA, node, "fromAreaId", areaNames),
                singleAreaDetail(AuditDetailCode.TO_AREA, node, "toAreaId", areaNames),
            )
            else -> fullDetails(eventType, node, areaNames).take(2)
        }

    // ---------------------------------------------------------------------- detail builders

    private fun stringDetail(code: AuditDetailCode, value: String?): AuditDetailItem? = value?.let { AuditDetailItem(code, it) }

    private fun sourceDetail(node: JsonNode) = stringDetail(AuditDetailCode.SOURCE, node.path("source").asString(null))

    private fun revokedSessionsDetail(node: JsonNode) = stringDetail(AuditDetailCode.REVOKED_SESSIONS, node.path("revokedSessions").asString(null))

    private fun globalAccessDetail(raw: String?): AuditDetailItem? = raw?.let { AuditDetailItem(AuditDetailCode.GLOBAL_ACCESS, it) }

    private fun areasDetail(node: JsonNode, key: String, areaNames: Map<UUID, String>): AuditDetailItem? {
        val ids = node.path(key).asString("").split(",").mapNotNull { it.trim().takeIf(String::isNotEmpty)?.let(::uuidOrNull) }
        if (ids.isEmpty()) return null
        val names = ids.mapNotNull { areaNames[it] }
        if (names.isEmpty()) return null
        return AuditDetailItem(AuditDetailCode.AREAS, names.joinToString(", "))
    }

    private fun singleAreaDetail(code: AuditDetailCode, node: JsonNode, key: String, areaNames: Map<UUID, String>): AuditDetailItem? {
        val id = uuidOrNull(node.path(key).asString(null)) ?: return null
        val name = areaNames[id] ?: return null
        return AuditDetailItem(code, name)
    }

    // ------------------------------------------------------------------------------- utils

    private fun metadataOf(row: AuditRawRow): JsonNode = objectMapper.readTree(row.metadataJson)

    private fun uuidOrNull(raw: String?): UUID? = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
}
