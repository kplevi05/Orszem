package hu.orszem.analytics.infrastructure

import hu.orszem.analytics.domain.AnalyticsQueryPort
import hu.orszem.analytics.domain.AnalyticsSummary
import hu.orszem.analytics.domain.CategoryStat
import hu.orszem.analytics.domain.EventTypeStat
import hu.orszem.analytics.domain.SettlementStat
import hu.orszem.analytics.domain.TrainStat
import hu.orszem.shared.config.OrszemProperties
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcAnalyticsRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val properties: OrszemProperties,
) : AnalyticsQueryPort {

    private val zone get() = properties.businessTimeZone.id

    override fun summary(): AnalyticsSummary = jdbc.queryForObject(
        """
        SELECT
            count(*)                                                              AS total,
            count(*) FILTER (
                WHERE (occurred_at AT TIME ZONE :zone)::date = (now() AT TIME ZONE :zone)::date
            )                                                                    AS today,
            count(*) FILTER (WHERE status IN ('NEW', 'IN_PROGRESS'))              AS active,
            count(*) FILTER (WHERE status = 'ARCHIVED')                           AS archived
        FROM reports
        """.trimIndent(),
        mapOf("zone" to zone),
    ) { rs, _ ->
        AnalyticsSummary(rs.getInt("total"), rs.getInt("today"), rs.getInt("active"), rs.getInt("archived"))
    }!!

    override fun eventTypeStats(): List<EventTypeStat> = jdbc.query(
        """
        WITH total AS (SELECT count(*)::numeric AS n FROM reports)
        SELECT et.code AS event_type_code, ec.code AS category_code, ec.label AS category_label,
               et.label AS label, count(r.id) AS cnt,
               CASE WHEN (SELECT n FROM total) = 0 THEN 0
                    ELSE round(count(r.id) * 100.0 / (SELECT n FROM total), 2) END AS pct
        FROM event_types et
        JOIN event_categories ec ON ec.id = et.category_id
        JOIN reports r ON r.event_type_id = et.id
        GROUP BY et.code, ec.code, ec.label, et.label
        ORDER BY cnt DESC, label ASC
        """.trimIndent(),
        emptyMap<String, Any>(),
    ) { rs, _ ->
        EventTypeStat(
            rs.getString("event_type_code"), rs.getString("category_code"), rs.getString("category_label"),
            rs.getString("label"), rs.getInt("cnt"), rs.getDouble("pct"),
        )
    }

    override fun categoryStats(): List<CategoryStat> = jdbc.query(
        """
        WITH total AS (SELECT count(*)::numeric AS n FROM reports)
        SELECT ec.code AS category_code, ec.label AS category_label, count(r.id) AS cnt,
               CASE WHEN (SELECT n FROM total) = 0 THEN 0
                    ELSE round(count(r.id) * 100.0 / (SELECT n FROM total), 2) END AS pct
        FROM event_categories ec
        JOIN event_types et ON et.category_id = ec.id
        JOIN reports r ON r.event_type_id = et.id
        GROUP BY ec.code, ec.label
        ORDER BY cnt DESC, category_label ASC
        """.trimIndent(),
        emptyMap<String, Any>(),
    ) { rs, _ ->
        CategoryStat(rs.getString("category_code"), rs.getString("category_label"), rs.getInt("cnt"), rs.getDouble("pct"))
    }

    override fun settlementStats(): List<SettlementStat> = jdbc.query(
        """
        SELECT settlement, count(*) AS cnt
        FROM reports
        GROUP BY settlement
        ORDER BY cnt DESC, settlement ASC
        """.trimIndent(),
        emptyMap<String, Any>(),
    ) { rs, _ -> SettlementStat(rs.getString("settlement"), rs.getInt("cnt")) }

    override fun trainStats(): List<TrainStat> = jdbc.query(
        """
        SELECT train_identifier, count(*) AS cnt
        FROM reports
        GROUP BY train_identifier
        ORDER BY cnt DESC, train_identifier ASC
        """.trimIndent(),
        emptyMap<String, Any>(),
    ) { rs, _ -> TrainStat(rs.getString("train_identifier"), rs.getInt("cnt")) }
}
