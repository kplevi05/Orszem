package hu.orszembejelento.backend.reference.infrastructure

import hu.orszembejelento.backend.reference.domain.KshCode
import hu.orszembejelento.backend.reference.domain.RailwayLine
import hu.orszembejelento.backend.reference.domain.Settlement
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Reference data access: settlements, railway lines and the validated relations between
 * them.
 *
 * Explicit SQL over [JdbcClient], consistent with the rest of the backend. Every statement
 * binds parameters — user-supplied search text is never concatenated into SQL.
 */
@Repository
class JdbcReferenceRepository(private val jdbc: JdbcClient) {

    // ------------------------------------------------------------- settlements

    fun findSettlementById(id: UUID): Settlement? =
        jdbc.sql("$SELECT_SETTLEMENT WHERE id = :id")
            .param("id", id)
            .query(::mapSettlement)
            .optional()
            .orElse(null)

    fun findSettlementByKshCode(kshCode: KshCode): Settlement? =
        jdbc.sql("$SELECT_SETTLEMENT WHERE ksh_code = :code")
            .param("code", kshCode.value)
            .query(::mapSettlement)
            .optional()
            .orElse(null)

    /**
     * Public settlement search.
     *
     * Only active settlements, matched case-insensitively on a name prefix or contained
     * term, ordered so results are stable, and hard-bounded by [limit] which the caller has
     * already clamped.
     *
     * The search term is bound as a parameter and the wildcards are added around the bound
     * value, so a term containing `%` or `_` is matched literally rather than becoming a
     * pattern the caller controls.
     */
    fun searchActiveSettlements(query: String, limit: Int): List<Settlement> =
        jdbc.sql(
            """
            $SELECT_SETTLEMENT
             WHERE active
               AND name ILIKE '%' || :query || '%'
             ORDER BY
               -- Prefix matches first: typing "Tat" should surface Tata before Bátaszék.
               CASE WHEN name ILIKE :query || '%' THEN 0 ELSE 1 END,
               name,
               ksh_code
             LIMIT :limit
            """.trimIndent(),
        )
            .param("query", escapeLikeWildcards(query))
            .param("limit", limit)
            .query(::mapSettlement)
            .list()

    /**
     * Escapes the LIKE metacharacters so a search term is treated as literal text.
     *
     * Without this a query of `%` would match every settlement in one request, which is a
     * cheap way to defeat the result limit's intent.
     */
    private fun escapeLikeWildcards(query: String): String =
        query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    fun insertSettlement(settlement: Settlement) {
        jdbc.sql(
            """
            INSERT INTO settlements (
                id, ksh_code, name, county_code, county_name, active, created_at, updated_at
            ) VALUES (
                :id, :kshCode, :name, :countyCode, :countyName, :active, :createdAt, :updatedAt
            )
            """.trimIndent(),
        )
            .param("id", settlement.id)
            .param("kshCode", settlement.kshCode.value)
            .param("name", settlement.name)
            .param("countyCode", settlement.countyCode)
            .param("countyName", settlement.countyName)
            .param("active", settlement.active)
            .param("createdAt", timestamp(settlement.createdAt))
            .param("updatedAt", timestamp(settlement.updatedAt))
            .update()
    }

    /** Updates the mutable attributes of an existing settlement, preserving its UUID. */
    fun updateSettlement(
        id: UUID,
        name: String,
        countyCode: String?,
        countyName: String?,
        active: Boolean,
        now: Instant,
    ) {
        jdbc.sql(
            """
            UPDATE settlements
               SET name = :name, county_code = :countyCode, county_name = :countyName,
                   active = :active, updated_at = :now
             WHERE id = :id
            """.trimIndent(),
        )
            .param("name", name)
            .param("countyCode", countyCode)
            .param("countyName", countyName)
            .param("active", active)
            .param("now", timestamp(now))
            .param("id", id)
            .update()
    }

    fun deactivateSettlementsNotIn(keptKshCodes: Collection<String>, now: Instant): Int =
        if (keptKshCodes.isEmpty()) {
            jdbc.sql("UPDATE settlements SET active = FALSE, updated_at = :now WHERE active")
                .param("now", timestamp(now)).update()
        } else {
            jdbc.sql(
                """
                UPDATE settlements
                   SET active = FALSE, updated_at = :now
                 WHERE active AND ksh_code <> ALL (:codes)
                """.trimIndent(),
            )
                .param("now", timestamp(now))
                .param("codes", keptKshCodes.toTypedArray())
                .update()
        }

    fun allSettlementKshCodes(): Map<String, UUID> =
        jdbc.sql("SELECT ksh_code, id FROM settlements")
            .query { rs, _ -> rs.getString("ksh_code") to rs.getObject("id", UUID::class.java) }
            .list()
            .toMap()

    // ----------------------------------------------------------- railway lines

    fun findRailwayLineById(id: UUID): RailwayLine? =
        jdbc.sql("$SELECT_LINE WHERE id = :id")
            .param("id", id)
            .query(::mapRailwayLine)
            .optional()
            .orElse(null)

    fun findRailwayLineByCode(lineCode: String): RailwayLine? =
        jdbc.sql("$SELECT_LINE WHERE line_code = :code")
            .param("code", lineCode)
            .query(::mapRailwayLine)
            .optional()
            .orElse(null)

    /** The active lines validated as serving a settlement. Used by routing and the public API. */
    fun findActiveLinesOfSettlement(settlementId: UUID): List<RailwayLine> =
        jdbc.sql(
            """
            SELECT l.id, l.line_code, l.display_name, l.active, l.created_at, l.updated_at
              FROM railway_lines l
              JOIN settlement_railway_lines m ON m.railway_line_id = l.id
             WHERE m.settlement_id = :settlementId AND l.active
             ORDER BY l.line_code
            """.trimIndent(),
        )
            .param("settlementId", settlementId)
            .query(::mapRailwayLine)
            .list()

    /** Whether a validated reference relation exists, regardless of activity. */
    fun relationExists(settlementId: UUID, railwayLineId: UUID): Boolean =
        jdbc.sql(
            """
            SELECT EXISTS (
                SELECT 1 FROM settlement_railway_lines
                 WHERE settlement_id = :settlementId AND railway_line_id = :lineId
            )
            """.trimIndent(),
        )
            .param("settlementId", settlementId)
            .param("lineId", railwayLineId)
            .query(Boolean::class.java)
            .single()

    fun insertRailwayLine(line: RailwayLine) {
        jdbc.sql(
            """
            INSERT INTO railway_lines (id, line_code, display_name, active, created_at, updated_at)
            VALUES (:id, :lineCode, :displayName, :active, :createdAt, :updatedAt)
            """.trimIndent(),
        )
            .param("id", line.id)
            .param("lineCode", line.lineCode)
            .param("displayName", line.displayName)
            .param("active", line.active)
            .param("createdAt", timestamp(line.createdAt))
            .param("updatedAt", timestamp(line.updatedAt))
            .update()
    }

    fun updateRailwayLine(id: UUID, displayName: String, active: Boolean, now: Instant) {
        jdbc.sql(
            """
            UPDATE railway_lines
               SET display_name = :displayName, active = :active, updated_at = :now
             WHERE id = :id
            """.trimIndent(),
        )
            .param("displayName", displayName)
            .param("active", active)
            .param("now", timestamp(now))
            .param("id", id)
            .update()
    }

    fun allRailwayLineCodes(): Map<String, UUID> =
        jdbc.sql("SELECT line_code, id FROM railway_lines")
            .query { rs, _ -> rs.getString("line_code") to rs.getObject("id", UUID::class.java) }
            .list()
            .toMap()

    /**
     * Line codes that are currently assigned to a service area.
     *
     * The importer consults this before deactivating anything: silently removing a line
     * that operational configuration depends on would orphan that configuration.
     */
    fun lineCodesAssignedToAnArea(): Set<String> =
        jdbc.sql(
            """
            SELECT l.line_code
              FROM railway_lines l
              JOIN service_area_railway_lines m ON m.railway_line_id = l.id
            """.trimIndent(),
        )
            .query(String::class.java)
            .list()
            .filterNotNull()
            .toSet()

    // --------------------------------------------------------------- relations

    fun insertRelation(settlementId: UUID, railwayLineId: UUID) {
        jdbc.sql(
            """
            INSERT INTO settlement_railway_lines (settlement_id, railway_line_id)
            VALUES (:settlementId, :lineId)
            """.trimIndent(),
        )
            .param("settlementId", settlementId)
            .param("lineId", railwayLineId)
            .update()
    }

    fun deleteAllRelations(): Int = jdbc.sql("DELETE FROM settlement_railway_lines").update()

    fun countRelations(): Int =
        jdbc.sql("SELECT COUNT(*) FROM settlement_railway_lines").query(Int::class.java).single()

    /** Current relations as external key pairs, for producing a human-readable diff. */
    fun allRelationsByExternalKey(): Set<Pair<String, String>> =
        jdbc.sql(
            """
            SELECT s.ksh_code AS ksh, l.line_code AS line
              FROM settlement_railway_lines m
              JOIN settlements s ON s.id = m.settlement_id
              JOIN railway_lines l ON l.id = m.railway_line_id
            """.trimIndent(),
        )
            .query { rs, _ -> rs.getString("ksh") to rs.getString("line") }
            .list()
            .toSet()

    // ----------------------------------------------------------------- mapping

    private fun mapSettlement(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = Settlement(
        id = rs.getObject("id", UUID::class.java),
        kshCode = KshCode.ofTrusted(rs.getString("ksh_code")),
        name = rs.getString("name"),
        countyCode = rs.getString("county_code"),
        countyName = rs.getString("county_name"),
        active = rs.getBoolean("active"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
    )

    private fun mapRailwayLine(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = RailwayLine(
        id = rs.getObject("id", UUID::class.java),
        lineCode = rs.getString("line_code"),
        displayName = rs.getString("display_name"),
        active = rs.getBoolean("active"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
    )

    private companion object {
        const val SELECT_SETTLEMENT = """
            SELECT id, ksh_code, name, county_code, county_name, active, created_at, updated_at
              FROM settlements
        """

        const val SELECT_LINE = """
            SELECT id, line_code, display_name, active, created_at, updated_at
              FROM railway_lines
        """

        fun timestamp(instant: Instant): java.sql.Timestamp = java.sql.Timestamp.from(instant)
    }
}
