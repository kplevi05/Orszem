package hu.orszem.catalog.infrastructure

import hu.orszem.catalog.domain.EventCatalogPort
import hu.orszem.catalog.domain.EventTypeRef
import hu.orszem.catalog.domain.EventTypeView
import org.springframework.jdbc.core.DataClassRowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JdbcEventCatalog(
    private val jdbc: NamedParameterJdbcTemplate,
) : EventCatalogPort {

    private val viewMapper = DataClassRowMapper(EventTypeView::class.java)
    private val refMapper = DataClassRowMapper(EventTypeRef::class.java)

    override fun listActiveEventTypes(): List<EventTypeView> = jdbc.query(
        """
        SELECT et.code            AS code,
               et.label           AS label,
               et.description      AS description,
               et.sort_order       AS "sortOrder",
               ec.code            AS "categoryCode",
               ec.label           AS "categoryLabel",
               ec.sort_order       AS "categorySortOrder"
        FROM event_types et
        JOIN event_categories ec ON ec.id = et.category_id
        WHERE et.active = true AND ec.active = true
        ORDER BY ec.sort_order, et.sort_order, et.label
        """.trimIndent(),
        emptyMap<String, Any>(),
        viewMapper,
    )

    override fun findActiveByCode(code: String): EventTypeRef? = jdbc.query(
        """
        SELECT et.id             AS id,
               et.code           AS code,
               et.label          AS label,
               ec.code           AS "categoryCode",
               ec.label          AS "categoryLabel",
               et.active         AS active
        FROM event_types et
        JOIN event_categories ec ON ec.id = et.category_id
        WHERE et.code = :code AND et.active = true
        """.trimIndent(),
        mapOf("code" to code),
        refMapper,
    ).firstOrNull()

    override fun findById(id: UUID): EventTypeRef? = jdbc.query(
        """
        SELECT et.id             AS id,
               et.code           AS code,
               et.label          AS label,
               ec.code           AS "categoryCode",
               ec.label          AS "categoryLabel",
               et.active         AS active
        FROM event_types et
        JOIN event_categories ec ON ec.id = et.category_id
        WHERE et.id = :id
        """.trimIndent(),
        mapOf("id" to id),
        refMapper,
    ).firstOrNull()
}
