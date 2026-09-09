package hu.orszembejelento.backend.usermanagement.infrastructure

import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.domain.UserStatus
import hu.orszembejelento.backend.reference.domain.ServiceAreaStatus
import hu.orszembejelento.backend.usermanagement.domain.AssignedArea
import hu.orszembejelento.backend.usermanagement.domain.ManagedUser
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * The read model user management needs: users joined with their current service-area
 * assignments, with the moderator-scope visibility rule expressed directly in SQL so
 * pagination and filtering stay correct together (Phase 6 brief §26/§55) rather than
 * paginating in memory over a filtered-after-the-fact list.
 *
 * Deliberately separate from [hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository]:
 * that repository is Phase 2's own, and this module continues to use it unmodified for row
 * locking and for the mutations it already owns (password/role/status). This repository
 * owns only the cross-table *read* queries — including the visibility filter, which is a
 * Phase 6 concept the identity module has no reason to know about.
 */
@Repository
class JdbcUserManagementRepository(private val jdbc: JdbcClient) {

    /**
     * @param excludeSuperAdmin true for any MODERATOR actor — a SUPER_ADMIN row must never
     *        appear in a MODERATOR's list or detail response (§6/§28).
     * @param territorialActorAreaIds `null` for SUPER_ADMIN or a global MODERATOR (no
     *        restriction beyond [excludeSuperAdmin]); a — possibly empty — set of the
     *        actor's own active area ids for a territorial MODERATOR, applying the overlap
     *        rule from [hu.orszembejelento.backend.usermanagement.domain.UserManagementPolicy.canViewTarget].
     */
    data class Filter(
        val role: UserRole? = null,
        val status: UserStatus? = null,
        val serviceIdQuery: String? = null,
        val areaId: UUID? = null,
        val excludeSuperAdmin: Boolean,
        val territorialActorAreaIds: Set<UUID>?,
    )

    fun count(filter: Filter): Int {
        val (where, params) = whereClause(filter)
        return jdbc.sql("SELECT COUNT(*) FROM users u $where")
            .params(params)
            .query(Int::class.java)
            .single()
    }

    /** Deterministic, service-ID-ascending order (§26), so the same filter always pages the same way. */
    fun findPage(filter: Filter, limit: Int, offset: Int): List<ManagedUser> {
        val (where, params) = whereClause(filter)
        val orderedIds = jdbc.sql("SELECT u.id FROM users u $where ORDER BY u.service_id ASC LIMIT :limit OFFSET :offset")
            .params(params)
            .param("limit", limit)
            .param("offset", offset)
            .query(UUID::class.java)
            .list()
            .filterNotNull()

        val byId = findManagedUsers(orderedIds.toSet()).associateBy { it.id }
        return orderedIds.mapNotNull { byId[it] }
    }

    fun findManagedUser(userId: UUID): ManagedUser? = findManagedUsers(setOf(userId)).singleOrNull()

    fun findManagedUserByServiceId(serviceId: ServiceId): ManagedUser? {
        val id = jdbc.sql("SELECT id FROM users WHERE service_id = :serviceId")
            .param("serviceId", serviceId.value)
            .query(UUID::class.java)
            .optional()
            .orElse(null) ?: return null
        return findManagedUser(id)
    }

    // ------------------------------------------------------------------------------ private

    private data class UserRow(
        val id: UUID,
        val serviceId: ServiceId,
        val role: UserRole,
        val status: UserStatus,
        val mustChangePassword: Boolean,
        val globalAreaAccess: Boolean,
    )

    private fun findManagedUsers(userIds: Set<UUID>): List<ManagedUser> {
        if (userIds.isEmpty()) return emptyList()

        val rows = jdbc.sql(
            """
            SELECT id, service_id, role, status, must_change_password, global_area_access
              FROM users WHERE id IN (:ids)
            """.trimIndent(),
        )
            .param("ids", userIds)
            .query { rs, _ ->
                UserRow(
                    id = rs.getObject("id", UUID::class.java),
                    serviceId = ServiceId.ofTrusted(rs.getString("service_id")),
                    role = UserRole.valueOf(rs.getString("role")),
                    status = UserStatus.valueOf(rs.getString("status")),
                    mustChangePassword = rs.getBoolean("must_change_password"),
                    globalAreaAccess = rs.getBoolean("global_area_access"),
                )
            }
            .list()

        val areasByUser = jdbc.sql(
            """
            SELECT usa.user_id, sa.id AS area_id, sa.name, sa.status
              FROM user_service_areas usa
              JOIN service_areas sa ON sa.id = usa.service_area_id
             WHERE usa.user_id IN (:ids)
             ORDER BY sa.name ASC
            """.trimIndent(),
        )
            .param("ids", userIds)
            .query { rs, _ ->
                rs.getObject("user_id", UUID::class.java) to AssignedArea(
                    id = rs.getObject("area_id", UUID::class.java),
                    name = rs.getString("name"),
                    status = ServiceAreaStatus.valueOf(rs.getString("status")),
                )
            }
            .list()
            .groupBy({ it.first }, { it.second })

        return rows.map { row ->
            ManagedUser(
                id = row.id,
                serviceId = row.serviceId,
                role = row.role,
                status = row.status,
                mustChangePassword = row.mustChangePassword,
                globalAreaAccess = row.globalAreaAccess,
                assignedAreas = areasByUser[row.id].orEmpty(),
            )
        }
    }

    private fun whereClause(filter: Filter): Pair<String, Map<String, Any>> {
        val clauses = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()

        if (filter.excludeSuperAdmin) clauses += "u.role != 'SUPER_ADMIN'"

        filter.role?.let {
            clauses += "u.role = :role"
            params["role"] = it.name
        }
        filter.status?.let {
            clauses += "u.status = :status"
            params["status"] = it.name
        }
        filter.serviceIdQuery?.let {
            clauses += "u.service_id ILIKE :serviceIdPattern"
            params["serviceIdPattern"] = "%${escapeLike(it)}%"
        }
        filter.areaId?.let {
            clauses += "EXISTS (SELECT 1 FROM user_service_areas x WHERE x.user_id = u.id AND x.service_area_id = :filterAreaId)"
            params["filterAreaId"] = it
        }

        filter.territorialActorAreaIds?.let { areaIds ->
            clauses += """
                (
                    (u.global_area_access = TRUE AND :actorHasOwnArea = TRUE)
                    OR EXISTS (
                        SELECT 1 FROM user_service_areas usa
                          JOIN service_areas sa ON sa.id = usa.service_area_id
                         WHERE usa.user_id = u.id AND sa.status = 'ACTIVE' AND usa.service_area_id IN (:actorAreaIds)
                    )
                )
            """.trimIndent()
            params["actorHasOwnArea"] = areaIds.isNotEmpty()
            // A genuinely empty IN-list is expanded safely by Spring, but an explicit
            // sentinel that cannot match any real service-area id keeps the intent visible.
            params["actorAreaIds"] = areaIds.ifEmpty { setOf(NEVER_MATCHES) }
        }

        val where = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")
        return where to params
    }

    private fun escapeLike(raw: String): String = raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private companion object {
        val NEVER_MATCHES: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
}
