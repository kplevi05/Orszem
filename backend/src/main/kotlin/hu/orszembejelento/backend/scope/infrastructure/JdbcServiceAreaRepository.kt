package hu.orszembejelento.backend.scope.infrastructure

import hu.orszembejelento.backend.reference.domain.ServiceArea
import hu.orszembejelento.backend.reference.domain.ServiceAreaStatus
import hu.orszembejelento.backend.scope.domain.AreaActor
import hu.orszembejelento.backend.identity.domain.UserRole
import java.sql.ResultSet
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Service areas, their current railway-line configuration, and user authorisation.
 *
 * Three separate concerns share this repository because they share the `service_areas`
 * table, but the tables underneath stay distinct: configuration
 * (`service_area_railway_lines`) and authorisation (`user_service_areas`) are never mixed.
 */
@Repository
class JdbcServiceAreaRepository(private val jdbc: JdbcClient) {

    // ------------------------------------------------------------ service areas

    fun findById(id: UUID): ServiceArea? =
        jdbc.sql("$SELECT_AREA WHERE id = :id")
            .param("id", id)
            .query(::mapArea)
            .optional()
            .orElse(null)

    fun findAll(): List<ServiceArea> =
        jdbc.sql("$SELECT_AREA ORDER BY name").query(::mapArea).list()

    /** Every currently ACTIVE area, name-ordered — the assignable-area list (Phase 6 brief §29). */
    fun findAllActive(): List<ServiceArea> =
        jdbc.sql("$SELECT_AREA WHERE status = 'ACTIVE' ORDER BY name").query(::mapArea).list()

    /**
     * Step 2 of the canonical lock order (`USER -> SERVICE AREA -> user_service_areas`,
     * Phase 6 brief §32) when a mutation also touches an area. Must be called inside a
     * transaction, after the target user row is already locked.
     */
    fun lockById(id: UUID): ServiceArea? =
        jdbc.sql("$SELECT_AREA WHERE id = :id FOR UPDATE")
            .param("id", id)
            .query(::mapArea)
            .optional()
            .orElse(null)

    fun insert(area: ServiceArea) {
        jdbc.sql(
            """
            INSERT INTO service_areas (id, name, status, created_at, updated_at, admin_version)
            VALUES (:id, :name, :status, :createdAt, :updatedAt, :adminVersion)
            """.trimIndent(),
        )
            .param("id", area.id)
            .param("name", area.name)
            .param("status", area.status.name)
            .param("createdAt", java.sql.Timestamp.from(area.createdAt))
            .param("updatedAt", java.sql.Timestamp.from(area.updatedAt))
            .param("adminVersion", area.adminVersion)
            .update()
    }

    /**
     * Renames an area and bumps its admin version in one statement (Phase 10 brief §11).
     * Called only after the row is already locked via [lockById] and the caller's own
     * `expectedVersion` check against [ServiceArea.adminVersion] has passed - the row lock
     * is what makes this single-statement write race-safe, not a `WHERE admin_version = ...`
     * guard (consistent with how [hu.orszembejelento.backend.reports.infrastructure.JdbcReportRepository.updateWorkflowState]
     * relies on its own prior row lock rather than repeating the version in the `WHERE`).
     */
    fun renameAndBumpVersion(id: UUID, name: String, newAdminVersion: Long, now: java.time.Instant) {
        jdbc.sql("UPDATE service_areas SET name = :name, admin_version = :version, updated_at = :now WHERE id = :id")
            .param("name", name)
            .param("version", newAdminVersion)
            .param("now", java.sql.Timestamp.from(now))
            .param("id", id)
            .update()
    }

    /** Activates/deactivates an area and bumps its admin version in one statement (brief §12/§13). */
    fun updateStatusAndBumpVersion(id: UUID, status: ServiceAreaStatus, newAdminVersion: Long, now: java.time.Instant) {
        jdbc.sql("UPDATE service_areas SET status = :status, admin_version = :version, updated_at = :now WHERE id = :id")
            .param("status", status.name)
            .param("version", newAdminVersion)
            .param("now", java.sql.Timestamp.from(now))
            .param("id", id)
            .update()
    }

    /**
     * Bumps only the admin version, with no other column change - used when a RailwayLine is
     * assigned into, moved out of, or unassigned from this area (brief §8: every one of those
     * mutations is admin-visible for the area even though the area's own name/status is
     * untouched).
     */
    fun bumpAdminVersion(id: UUID, newAdminVersion: Long, now: java.time.Instant) {
        jdbc.sql("UPDATE service_areas SET admin_version = :version, updated_at = :now WHERE id = :id")
            .param("version", newAdminVersion)
            .param("now", java.sql.Timestamp.from(now))
            .param("id", id)
            .update()
    }

    /** Every RailwayLine id currently mapped to [serviceAreaId] - the admin detail's lines section (brief §38). */
    fun mappedRailwayLineIds(serviceAreaId: UUID): List<UUID> =
        jdbc.sql("SELECT railway_line_id FROM service_area_railway_lines WHERE service_area_id = :areaId")
            .param("areaId", serviceAreaId)
            .query(UUID::class.java)
            .list()
            .filterNotNull()

    /** How many RailwayLines currently map to [serviceAreaId] - the deactivation blocker (brief §14) and the list-row count (brief §37). */
    fun countMappedRailwayLines(serviceAreaId: UUID): Int =
        jdbc.sql("SELECT COUNT(*) FROM service_area_railway_lines WHERE service_area_id = :areaId")
            .param("areaId", serviceAreaId)
            .query(Int::class.java)
            .single()

    /** Removes exactly the mapping for [railwayLineId] currently pointing at [serviceAreaId], if it still does. Returns rows removed (0 or 1). */
    fun removeRailwayLineMapping(serviceAreaId: UUID, railwayLineId: UUID): Int =
        jdbc.sql("DELETE FROM service_area_railway_lines WHERE service_area_id = :areaId AND railway_line_id = :lineId")
            .param("areaId", serviceAreaId)
            .param("lineId", railwayLineId)
            .update()

    // ------------------------------------------- settlement -> area configuration

    /** Every settlement id currently mapped to [serviceAreaId] in `service_area_settlements`. */
    fun mappedSettlementIds(serviceAreaId: UUID): List<UUID> =
        jdbc.sql("SELECT settlement_id FROM service_area_settlements WHERE service_area_id = :areaId")
            .param("areaId", serviceAreaId)
            .query(UUID::class.java)
            .list()
            .filterNotNull()

    /** How many settlements currently map to [serviceAreaId]. */
    fun countMappedSettlements(serviceAreaId: UUID): Int =
        jdbc.sql("SELECT COUNT(*) FROM service_area_settlements WHERE service_area_id = :areaId")
            .param("areaId", serviceAreaId)
            .query(Int::class.java)
            .single()

    /** The service area currently responsible for [settlementId], if any. */
    fun findAreaOfSettlement(settlementId: UUID): ServiceArea? =
        jdbc.sql(
            """
            SELECT a.id, a.name, a.status, a.created_at, a.updated_at, a.admin_version
              FROM service_areas a
              JOIN service_area_settlements m ON m.service_area_id = a.id
             WHERE m.settlement_id = :settlementId
            """.trimIndent(),
        )
            .param("settlementId", settlementId)
            .query(::mapArea)
            .optional()
            .orElse(null)

    fun assignSettlement(serviceAreaId: UUID, settlementId: UUID) {
        jdbc.sql(
            """
            INSERT INTO service_area_settlements (settlement_id, service_area_id)
            VALUES (:settlementId, :areaId)
            ON CONFLICT (settlement_id) DO UPDATE
               SET service_area_id = EXCLUDED.service_area_id,
                   created_at = now()
            """.trimIndent(),
        )
            .param("settlementId", settlementId)
            .param("areaId", serviceAreaId)
            .update()
    }

    fun removeSettlementMapping(serviceAreaId: UUID, settlementId: UUID): Int =
        jdbc.sql("DELETE FROM service_area_settlements WHERE service_area_id = :areaId AND settlement_id = :settlementId")
            .param("areaId", serviceAreaId)
            .param("settlementId", settlementId)
            .update()

    /**
     * Re-routes currently open (`NEW`, non-deleted) reports for [settlementId] into the newly
     * assigned active [targetAreaId] so tickets already waiting in the queue immediately appear
     * under the newly configured service area.
     */
    fun routeOpenReportsForSettlement(settlementId: UUID, targetAreaId: UUID) {
        jdbc.sql(
            """
            UPDATE report_routing_snapshots rs
               SET routing_status = 'ROUTED',
                   service_area_id = :areaId,
                   routing_reason = NULL
              FROM reports r
             WHERE rs.report_id = r.id
               AND r.settlement_id = :settlementId
               AND r.status = 'NEW'
               AND NOT EXISTS (
                   SELECT 1 FROM report_moderation_episodes rme
                    WHERE rme.report_id = r.id AND rme.restored_at IS NULL
               )
            """.trimIndent(),
        )
            .param("areaId", targetAreaId)
            .param("settlementId", settlementId)
            .update()
    }

    /**
     * Moves currently open (`NEW`, non-deleted) reports for [settlementId] in [fromAreaId]
     * back to `UNCLASSIFIED` when the settlement is removed from the service area, ensuring
     * zero ticket loss.
     */
    fun unclassifyOpenReportsForSettlement(settlementId: UUID, fromAreaId: UUID) {
        jdbc.sql(
            """
            UPDATE report_routing_snapshots rs
               SET routing_status = 'UNCLASSIFIED',
                   service_area_id = NULL,
                   routing_reason = 'NO_VERIFIED_RAILWAY_LINE_REFERENCE'
              FROM reports r
             WHERE rs.report_id = r.id
               AND r.settlement_id = :settlementId
               AND rs.service_area_id = :areaId
               AND r.status = 'NEW'
               AND NOT EXISTS (
                   SELECT 1 FROM report_moderation_episodes rme
                    WHERE rme.report_id = r.id AND rme.restored_at IS NULL
               )
            """.trimIndent(),
        )
            .param("settlementId", settlementId)
            .param("areaId", fromAreaId)
            .update()
    }

    /**
     * Moves every report routing snapshot pointing at [serviceAreaId] to `UNCLASSIFIED`
     * (`SERVICE_AREA_INACTIVE`) so no report is ever lost or orphaned when a service area is
     * deactivated or deleted.
     */
    fun unclassifyAllReportsOfArea(serviceAreaId: UUID) {
        jdbc.sql(
            """
            UPDATE report_routing_snapshots
               SET routing_status = 'UNCLASSIFIED',
                   service_area_id = NULL,
                   routing_reason = 'SERVICE_AREA_INACTIVE'
             WHERE service_area_id = :areaId
            """.trimIndent(),
        )
            .param("areaId", serviceAreaId)
            .update()
    }

    /**
     * Deletes a service area after converting any report routing snapshots referencing it to
     * `UNCLASSIFIED`, ensuring zero ticket loss.
     */
    fun deleteArea(serviceAreaId: UUID) {
        unclassifyAllReportsOfArea(serviceAreaId)
        jdbc.sql("DELETE FROM service_area_settlements WHERE service_area_id = :areaId").param("areaId", serviceAreaId).update()
        jdbc.sql("DELETE FROM service_area_settlement_lines WHERE service_area_id = :areaId").param("areaId", serviceAreaId).update()
        jdbc.sql("DELETE FROM service_area_railway_lines WHERE service_area_id = :areaId").param("areaId", serviceAreaId).update()
        jdbc.sql("DELETE FROM user_service_areas WHERE service_area_id = :areaId").param("areaId", serviceAreaId).update()
        jdbc.sql("DELETE FROM service_areas WHERE id = :areaId").param("areaId", serviceAreaId).update()
    }

    /**
     * Every currently-open (NEW or IN_PROGRESS), non-moderation-deleted report whose *routing
     * snapshot* names [serviceAreaId] - the deactivation blocker (brief §15). Deliberately the
     * immutable snapshot's area, never current line configuration: a report already routed
     * here is exactly the "ordinary operational work" a deactivation must not strand, even if
     * its line has since moved elsewhere. ARCHIVED and moderation-deleted reports are excluded
     * on purpose (brief §13/§16) - they are not the "currently-visible ordinary operational
     * work" this blocker exists to protect.
     */
    fun countOpenOperationalReports(serviceAreaId: UUID): Int =
        jdbc.sql(
            """
            SELECT COUNT(*)
              FROM reports r
              JOIN report_routing_snapshots rs ON rs.report_id = r.id
             WHERE rs.service_area_id = :areaId
               AND r.status IN ('NEW', 'IN_PROGRESS')
               AND NOT EXISTS (
                   SELECT 1 FROM report_moderation_episodes rme
                    WHERE rme.report_id = r.id AND rme.restored_at IS NULL
               )
            """.trimIndent(),
        )
            .param("areaId", serviceAreaId)
            .query(Int::class.java)
            .single()

    // ------------------------------------------------- line -> area configuration

    /**
     * The area currently responsible for a line, if any.
     *
     * At most one row can exist per line: the database enforces it with a unique index, so
     * this cannot ambiguously return two areas.
     */
    fun findAreaOfRailwayLine(railwayLineId: UUID): ServiceArea? =
        jdbc.sql(
            """
            SELECT a.id, a.name, a.status, a.created_at, a.updated_at, a.admin_version
              FROM service_areas a
              JOIN service_area_railway_lines m ON m.service_area_id = a.id
             WHERE m.railway_line_id = :lineId
            """.trimIndent(),
        )
            .param("lineId", railwayLineId)
            .query(::mapArea)
            .optional()
            .orElse(null)

    fun findAreaOfSettlementLine(settlementId: UUID, railwayLineId: UUID): ServiceArea? =
        jdbc.sql("""
            SELECT a.id, a.name, a.status, a.created_at, a.updated_at, a.admin_version
            FROM service_areas a JOIN service_area_settlement_lines m ON m.service_area_id = a.id
            WHERE m.settlement_id = :s AND m.railway_line_id = :l
        """.trimIndent()).param("s", settlementId).param("l", railwayLineId).query(::mapArea).optional().orElse(null)

    fun hasSettlementLineMappings(railwayLineId: UUID): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM service_area_settlement_lines WHERE railway_line_id = :l)")
            .param("l", railwayLineId).query(Boolean::class.java).single()

    fun countSettlementLineMappings(serviceAreaId: UUID): Int =
        jdbc.sql("SELECT COUNT(*) FROM service_area_settlement_lines WHERE service_area_id = :a")
            .param("a", serviceAreaId).query(Int::class.java).single()

    fun assignRailwayLine(serviceAreaId: UUID, railwayLineId: UUID) {
        jdbc.sql(
            """
            INSERT INTO service_area_railway_lines (service_area_id, railway_line_id)
            VALUES (:areaId, :lineId)
            """.trimIndent(),
        )
            .param("areaId", serviceAreaId)
            .param("lineId", railwayLineId)
            .update()
    }

    // ------------------------------------------------------------ authorisation

    fun assignUserToArea(userId: UUID, serviceAreaId: UUID) {
        jdbc.sql(
            """
            INSERT INTO user_service_areas (user_id, service_area_id)
            VALUES (:userId, :areaId)
            """.trimIndent(),
        )
            .param("userId", userId)
            .param("areaId", serviceAreaId)
            .update()
    }

    fun assignedAreaIds(userId: UUID): Set<UUID> =
        jdbc.sql("SELECT service_area_id FROM user_service_areas WHERE user_id = :userId")
            .param("userId", userId)
            .query(UUID::class.java)
            .list()
            .filterNotNull()
            .toSet()

    /**
     * Only the areas [userId] may currently *act* in: assigned AND active. An assignment to
     * an area that has since gone INACTIVE is deliberately excluded — it confers no scope to
     * anyone (Phase 6 brief §7/§30), unlike [assignedAreaIds], which is the raw, unfiltered
     * assignment set used where the inactive rows themselves must be visible.
     */
    fun activeAssignedAreaIds(userId: UUID): Set<UUID> =
        jdbc.sql(
            """
            SELECT usa.service_area_id
              FROM user_service_areas usa
              JOIN service_areas sa ON sa.id = usa.service_area_id
             WHERE usa.user_id = :userId AND sa.status = 'ACTIVE'
            """.trimIndent(),
        )
            .param("userId", userId)
            .query(UUID::class.java)
            .list()
            .filterNotNull()
            .toSet()

    /**
     * Grants [serviceAreaId] to [userId], or does nothing if the grant already exists.
     *
     * Idempotent by construction: the `user_service_areas` primary key is the authority on
     * "already granted", not a prior existence check, which would still race (Phase 6 brief
     * §8/§35). Uses `ON CONFLICT DO NOTHING` rather than catching the constraint violation -
     * PostgreSQL aborts the *entire enclosing transaction* the instant any statement raises
     * an error, and catching the resulting `DuplicateKeyException` in application code does
     * not undo that: every later statement in the same transaction (including, here, the
     * caller's own follow-up read of the refreshed user) would then fail with "current
     * transaction is aborted" even though the Kotlin exception was already handled. A
     * conflict clause never raises an error in the first place, so no savepoint is needed.
     * Returns whether a new row was actually inserted, purely so a caller can decide whether
     * the state genuinely changed and an audit row is warranted.
     */
    fun grantAreaIfAbsent(userId: UUID, serviceAreaId: UUID): Boolean =
        jdbc.sql(
            """
            INSERT INTO user_service_areas (user_id, service_area_id)
            VALUES (:userId, :areaId)
            ON CONFLICT (user_id, service_area_id) DO NOTHING
            """.trimIndent(),
        )
            .param("userId", userId)
            .param("areaId", serviceAreaId)
            .update() == 1

    /** Revokes a grant. Returns the number of rows removed (0 or 1) — a no-op revoke is not an error. */
    fun revokeArea(userId: UUID, serviceAreaId: UUID): Int =
        jdbc.sql("DELETE FROM user_service_areas WHERE user_id = :userId AND service_area_id = :areaId")
            .param("userId", userId)
            .param("areaId", serviceAreaId)
            .update()

    fun countAssignedAreas(userId: UUID): Int =
        jdbc.sql("SELECT COUNT(*) FROM user_service_areas WHERE user_id = :userId")
            .param("userId", userId)
            .query(Int::class.java)
            .single()

    /**
     * A user's own assigned areas, name and status included, ordered by name. Used only for
     * the self-account view (`/account/me`) — the raw id set is [assignedAreaIds].
     */
    fun assignedAreas(userId: UUID): List<ServiceArea> =
        jdbc.sql(
            """
            SELECT sa.id, sa.name, sa.status, sa.created_at, sa.updated_at, sa.admin_version
              FROM user_service_areas usa
              JOIN service_areas sa ON sa.id = usa.service_area_id
             WHERE usa.user_id = :userId
             ORDER BY sa.name ASC
            """.trimIndent(),
        )
            .param("userId", userId)
            .query(this::mapArea)
            .list()

    /**
     * Builds the authorisation view of a user from current database state.
     *
     * Role, the global flag and the assignments are all read fresh, so a change to any of
     * them takes effect on the next request rather than when some cached token expires.
     */
    fun loadAreaActor(userId: UUID): AreaActor? {
        val base = jdbc.sql("SELECT role, global_area_access FROM users WHERE id = :id")
            .param("id", userId)
            .query { rs, _ -> UserRole.valueOf(rs.getString("role")) to rs.getBoolean("global_area_access") }
            .optional()
            .orElse(null) ?: return null

        return AreaActor(
            userId = userId,
            role = base.first,
            globalAreaAccess = base.second,
            assignedAreaIds = assignedAreaIds(userId),
        )
    }

    fun setGlobalAreaAccess(userId: UUID, value: Boolean) {
        jdbc.sql("UPDATE users SET global_area_access = :value, updated_at = now() WHERE id = :id")
            .param("value", value)
            .param("id", userId)
            .update()
    }

    private fun mapArea(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = ServiceArea(
        id = rs.getObject("id", UUID::class.java),
        name = rs.getString("name"),
        status = ServiceAreaStatus.valueOf(rs.getString("status")),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
        adminVersion = rs.getLong("admin_version"),
    )

    private companion object {
        const val SELECT_AREA = "SELECT id, name, status, created_at, updated_at, admin_version FROM service_areas"
    }
}
