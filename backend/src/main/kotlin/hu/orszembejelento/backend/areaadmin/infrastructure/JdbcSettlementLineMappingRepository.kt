package hu.orszembejelento.backend.areaadmin.infrastructure

import hu.orszembejelento.backend.areaadmin.domain.SettlementLineMappingView
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class JdbcSettlementLineMappingRepository(private val jdbc: JdbcClient) {
    fun currentArea(settlementId: UUID, lineId: UUID): UUID? =
        jdbc.sql("SELECT service_area_id FROM service_area_settlement_lines WHERE settlement_id = :s AND railway_line_id = :l")
            .param("s", settlementId).param("l", lineId).query(UUID::class.java).optional().orElse(null)

    fun set(settlementId: UUID, lineId: UUID, areaId: UUID?) {
        if (areaId == null) {
            jdbc.sql("DELETE FROM service_area_settlement_lines WHERE settlement_id = :s AND railway_line_id = :l")
                .param("s", settlementId).param("l", lineId).update()
        } else {
            jdbc.sql("""
                INSERT INTO service_area_settlement_lines (settlement_id, railway_line_id, service_area_id)
                VALUES (:s, :l, :a)
                ON CONFLICT (settlement_id, railway_line_id) DO UPDATE SET service_area_id = EXCLUDED.service_area_id
            """.trimIndent()).param("s", settlementId).param("l", lineId).param("a", areaId).update()
        }
    }

    fun list(areaId: UUID?, offset: Long, size: Int): List<SettlementLineMappingView> =
        jdbc.sql("""
            SELECT s.ksh_code, s.name, s.active AS settlement_active, l.line_code,
                   l.active AS line_active, a.id AS area_id, a.name AS area_name, a.status AS area_status
            FROM service_area_settlement_lines m
            JOIN settlements s ON s.id = m.settlement_id
            JOIN railway_lines l ON l.id = m.railway_line_id
            JOIN service_areas a ON a.id = m.service_area_id
            WHERE (CAST(:areaId AS uuid) IS NULL OR a.id = CAST(:areaId AS uuid))
            ORDER BY s.ksh_code, l.line_code
            LIMIT :size OFFSET :offset
        """.trimIndent()).param("areaId", areaId, java.sql.Types.OTHER).param("size", size).param("offset", offset)
            .query { rs, _ -> SettlementLineMappingView(
                rs.getString("ksh_code"), rs.getString("name"), rs.getBoolean("settlement_active"),
                rs.getString("line_code"), rs.getBoolean("line_active"), rs.getObject("area_id", UUID::class.java),
                rs.getString("area_name"), rs.getString("area_status") == "ACTIVE",
            ) }.list()
}
