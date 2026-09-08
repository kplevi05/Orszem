package hu.orszembejelento.backend.reference.infrastructure

import hu.orszembejelento.backend.common.ReferenceStateLock
import hu.orszembejelento.backend.reference.domain.CoverageComponentStatus
import hu.orszembejelento.backend.reference.domain.CurrentReferenceState
import hu.orszembejelento.backend.reference.domain.DatasetCoverage
import hu.orszembejelento.backend.reference.domain.ExistingReferenceRow
import hu.orszembejelento.backend.reference.domain.KshCode
import hu.orszembejelento.backend.reference.domain.RailwayLine
import hu.orszembejelento.backend.reference.domain.ReferenceDatasetImport
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

    /**
     * Deactivates exactly one settlement by its internal id.
     *
     * Deliberately per-row rather than a bulk "deactivate everything not in this dataset"
     * statement: which rows may be deactivated at all depends on [DatasetCoverage] (ADR
     * 0006), a decision [diffReferenceDataset] already made. This method only ever applies
     * to the codes [ReferenceDiff.settlementsToDeactivate] names — under PARTIAL settlement
     * coverage that set is empty, so nothing here is ever called.
     */
    fun deactivateSettlement(id: UUID, now: Instant) {
        jdbc.sql("UPDATE settlements SET active = FALSE, updated_at = :now WHERE id = :id")
            .param("now", timestamp(now))
            .param("id", id)
            .update()
    }

    /**
     * Current settlements keyed by their stable external identity, with just enough state
     * (id, active) for the importer to decide insert vs. update vs. reactivate without a
     * second round trip.
     */
    fun allSettlementKshCodes(): Map<String, ExistingReferenceRow> =
        jdbc.sql("SELECT ksh_code, id, active FROM settlements")
            .query { rs, _ ->
                rs.getString("ksh_code") to
                    ExistingReferenceRow(rs.getObject("id", UUID::class.java), rs.getBoolean("active"))
            }
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

    /**
     * The active, verified lines serving a settlement - the one candidate set both
     * `RoutingService`'s no-selection inference and the public reference API's
     * railway-line listing read, so the two can never disagree about what is selectable
     * (ADR 0007). An inactive relation is excluded here even though it remains a verified
     * fact; see [relationExists] for the check that still consults it.
     */
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

    /**
     * Whether a validated reference relation exists, regardless of activity.
     *
     * This is the one place an inactive relation still matters as a fact: explicit
     * selection of a railway line must be validated against every verified relation, not
     * only active ones, so that selecting a line the settlement is genuinely (if
     * currently inactively) related to reports `RAILWAY_LINE_INACTIVE` rather than the
     * misleading `REFERENCE_MISMATCH` - see `RoutingService`. It is deliberately **not**
     * used to build a candidate set for automatic inference; [findActiveLinesOfSettlement]
     * is, so that routing and the public reference API always agree on what counts as a
     * selectable line (ADR 0007).
     */
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

    fun allRailwayLineCodes(): Map<String, ExistingReferenceRow> =
        jdbc.sql("SELECT line_code, id, active FROM railway_lines")
            .query { rs, _ ->
                rs.getString("line_code") to
                    ExistingReferenceRow(rs.getObject("id", UUID::class.java), rs.getBoolean("active"))
            }
            .list()
            .toMap()

    /**
     * Deactivates exactly one railway line by its internal id. Per-row for the same reason
     * as [deactivateSettlement]: which lines may be deactivated at all was already decided
     * by coverage-aware diffing, and this only ever applies to that precomputed set.
     */
    fun deactivateRailwayLine(id: UUID, now: Instant) {
        jdbc.sql("UPDATE railway_lines SET active = FALSE, updated_at = :now WHERE id = :id")
            .param("now", timestamp(now))
            .param("id", id)
            .update()
    }

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

    fun deleteRelation(settlementId: UUID, railwayLineId: UUID) {
        jdbc.sql("DELETE FROM settlement_railway_lines WHERE settlement_id = :s AND railway_line_id = :l")
            .param("s", settlementId)
            .param("l", railwayLineId)
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

    // ------------------------------------------------------ import serialisation

    /**
     * Serialises a reference-dataset import against every other import **and** against
     * every in-flight report submission, for the lifetime of the current transaction only.
     *
     * A transaction-scoped advisory lock rather than a row lock: nothing to lock yet exists
     * before the first import ever runs, and the whole operation - not one row - is what
     * must not overlap with another import or with a submission reading current state. See
     * [ReferenceStateLock] for why this is the exclusive half of a shared/exclusive pair on
     * one centralised key, and [acquireSharedReferenceStateLock] for the other half.
     */
    fun acquireImportLock() {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)")
            .param("key", ReferenceStateLock.KEY)
            .query { _, _ -> true }
            .list()
    }

    /**
     * Held by a report submission for the lifetime of its transaction while it reads
     * reference/service-area state and computes a routing snapshot - see
     * [ReferenceStateLock]. Many submissions may hold this concurrently; only a concurrent
     * [acquireImportLock] call blocks against it (and is blocked by it), so ordinary
     * submission traffic never contends with itself over this lock.
     */
    fun acquireSharedReferenceStateLock() {
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(:key)")
            .param("key", ReferenceStateLock.KEY)
            .query { _, _ -> true }
            .list()
    }

    fun findImportByVersion(datasetVersion: String): ReferenceDatasetImport? =
        jdbc.sql("$SELECT_IMPORT WHERE dataset_version = :version")
            .param("version", datasetVersion)
            .query(::mapImport)
            .optional()
            .orElse(null)

    fun insertImportProvenance(
        id: UUID,
        datasetVersion: String,
        manifestSha256: ByteArray,
        importedAt: Instant,
        settlementCount: Int,
        railwayLineCount: Int,
        mappingCount: Int,
        coverage: DatasetCoverage,
        sourceMetadataJson: String,
    ) {
        jdbc.sql(
            """
            INSERT INTO reference_dataset_imports (
                id, dataset_version, manifest_sha256, imported_at,
                settlement_count, railway_line_count, mapping_count,
                settlements_coverage, railway_lines_coverage, settlement_railway_lines_coverage,
                source_metadata
            ) VALUES (
                :id, :version, :sha256, :importedAt,
                :settlements, :lines, :mappings,
                :settlementsCoverage, :railwayLinesCoverage, :relationsCoverage,
                CAST(:sourceMetadata AS jsonb)
            )
            """.trimIndent(),
        )
            .param("id", id)
            .param("version", datasetVersion)
            .param("sha256", manifestSha256)
            .param("importedAt", timestamp(importedAt))
            .param("settlements", settlementCount)
            .param("lines", railwayLineCount)
            .param("mappings", mappingCount)
            .param("settlementsCoverage", coverage.settlements.name)
            .param("railwayLinesCoverage", coverage.railwayLines.name)
            .param("relationsCoverage", coverage.settlementRailwayLines.name)
            .param("sourceMetadata", sourceMetadataJson)
            .update()
    }

    /**
     * Makes [newImportId] the one current reference state, demoting whatever was current
     * before it. Two statements, not one UPSERT: the unset must fully complete before the
     * set, or the partial unique index on `is_current` would reject the second row.
     *
     * Must be called from inside the same transaction as the import it promotes - see
     * [ReferenceImportUseCase][hu.orszembejelento.backend.reference.application.ReferenceImportUseCase].
     * Never called at all on a failed or no-op import, which is what keeps a failed import
     * from changing the current state.
     */
    fun promoteToCurrentImport(newImportId: UUID) {
        jdbc.sql("UPDATE reference_dataset_imports SET is_current = FALSE WHERE is_current").update()
        jdbc.sql("UPDATE reference_dataset_imports SET is_current = TRUE WHERE id = :id")
            .param("id", newImportId)
            .update()
    }

    /**
     * The one active reference state, or null if no dataset has ever been successfully
     * imported. Routing and the public reference API treat null as an infrastructure
     * condition (`REFERENCE_DATASET_UNAVAILABLE`), never as an empty business result.
     */
    fun findCurrentReferenceState(): CurrentReferenceState? =
        jdbc.sql(
            "SELECT dataset_version, settlement_railway_lines_coverage FROM reference_dataset_imports WHERE is_current",
        )
            .query { rs, _ ->
                CurrentReferenceState(
                    datasetVersion = rs.getString("dataset_version"),
                    settlementRailwayLinesCoverage =
                        CoverageComponentStatus.valueOf(rs.getString("settlement_railway_lines_coverage")),
                )
            }
            .optional()
            .orElse(null)

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

    private fun mapImport(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = ReferenceDatasetImport(
        id = rs.getObject("id", UUID::class.java),
        datasetVersion = rs.getString("dataset_version"),
        manifestSha256 = rs.getBytes("manifest_sha256"),
        importedAt = rs.getTimestamp("imported_at").toInstant(),
        settlementCount = rs.getInt("settlement_count"),
        railwayLineCount = rs.getInt("railway_line_count"),
        mappingCount = rs.getInt("mapping_count"),
        settlementsCoverage = CoverageComponentStatus.valueOf(rs.getString("settlements_coverage")),
        railwayLinesCoverage = CoverageComponentStatus.valueOf(rs.getString("railway_lines_coverage")),
        settlementRailwayLinesCoverage =
            CoverageComponentStatus.valueOf(rs.getString("settlement_railway_lines_coverage")),
        isCurrent = rs.getBoolean("is_current"),
        sourceMetadataJson = rs.getString("source_metadata"),
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

        const val SELECT_IMPORT = """
            SELECT id, dataset_version, manifest_sha256, imported_at,
                   settlement_count, railway_line_count, mapping_count,
                   settlements_coverage, railway_lines_coverage, settlement_railway_lines_coverage,
                   is_current, source_metadata
              FROM reference_dataset_imports
        """

        fun timestamp(instant: Instant): java.sql.Timestamp = java.sql.Timestamp.from(instant)
    }
}
