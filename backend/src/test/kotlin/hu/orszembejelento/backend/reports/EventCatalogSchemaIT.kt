package hu.orszembejelento.backend.reports

import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * The V003 taxonomy invariants, asserted against real PostgreSQL (docs/product/EVENT_CATALOG_V2.md, ADR 0008).
 *
 * `report_categories`/`report_event_types` are seeded once by the migration itself and are
 * never truncated by [hu.orszembejelento.backend.reports.support.PublicReportTestSupport] -
 * these assertions are therefore about the migration's own seed data, exactly as it landed,
 * not about anything a test wrote.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class EventCatalogSchemaIT : AbstractAuthIntegrationTest() {

    @Test
    fun `the migration seeded exactly 7 categories and 61 event types`() {
        val categoryCount = jdbc.sql("SELECT COUNT(*) FROM report_categories").query(Int::class.java).single()
        val eventTypeCount = jdbc.sql("SELECT COUNT(*) FROM report_event_types").query(Int::class.java).single()
        check(categoryCount == 7) { "expected 7 categories, found $categoryCount" }
        check(eventTypeCount == 61) { "expected 61 event types, found $eventTypeCount" }
    }

    @Test
    fun `every category and event type is active by default`() {
        val inactiveCategories = jdbc.sql("SELECT COUNT(*) FROM report_categories WHERE NOT active")
            .query(Int::class.java).single()
        val inactiveEventTypes = jdbc.sql("SELECT COUNT(*) FROM report_event_types WHERE NOT active")
            .query(Int::class.java).single()
        check(inactiveCategories == 0) { "the seed migration must not deactivate anything" }
        check(inactiveEventTypes == 0) { "the seed migration must not deactivate anything" }
    }

    @Test
    fun `category codes are unique and shaped like SCREAMING_SNAKE_CASE`() {
        val codes = jdbc.sql("SELECT code FROM report_categories").query(String::class.java).list().filterNotNull()
        check(codes.size == codes.toSet().size) { "duplicate category code found: $codes" }
        codes.forEach { code ->
            check(Regex("^[A-Z][A-Z0-9_]*$").matches(code)) { "category code '$code' does not match the required shape" }
        }
    }

    @Test
    fun `event type codes are unique and shaped like SCREAMING_SNAKE_CASE`() {
        val codes = jdbc.sql("SELECT code FROM report_event_types").query(String::class.java).list().filterNotNull()
        check(codes.size == codes.toSet().size) { "duplicate event type code found: $codes" }
        codes.forEach { code ->
            check(Regex("^[A-Z][A-Z0-9_]*$").matches(code)) { "event type code '$code' does not match the required shape" }
        }
    }

    @Test
    fun `every event type names a category that exists`() {
        val orphaned = jdbc.sql(
            """
            SELECT et.code FROM report_event_types et
            LEFT JOIN report_categories c ON c.code = et.category_code
            WHERE c.code IS NULL
            """.trimIndent(),
        ).query(String::class.java).list()
        check(orphaned.isEmpty()) { "event types with no matching category: $orphaned" }
    }

    @Test
    fun `an event type cannot reference a category that does not exist`() {
        val rejected = runCatching {
            jdbc.sql(
                "INSERT INTO report_event_types (code, category_code, display_name, display_order, active) " +
                    "VALUES ('ZZZ_DANGLING', 'NO_SUCH_CATEGORY', 'x', 9999, TRUE)",
            ).update()
        }.isFailure
        check(rejected) { "the FK to report_categories must be enforced" }
    }

    @Test
    fun `category display_order is globally unique`() {
        val orders = jdbc.sql("SELECT display_order FROM report_categories").query(Int::class.java).list()
        check(orders.size == orders.toSet().size) { "duplicate category display_order: $orders" }

        val rejected = runCatching {
            jdbc.sql(
                "INSERT INTO report_categories (code, display_name, display_order, active) " +
                    "VALUES ('ZZZ_DUP_ORDER', 'x', 10, TRUE)",
            ).update()
        }.isFailure
        check(rejected) { "duplicating an existing category display_order (10) must be rejected" }
    }

    @Test
    fun `event type display_order is unique only within its own category`() {
        // Two categories may each legitimately have an event type at the same position -
        // each is only ever rendered within its own category's list.
        val distinctOrdersUsedTwice = jdbc.sql(
            """
            SELECT display_order
              FROM report_event_types
             GROUP BY display_order
            HAVING COUNT(DISTINCT category_code) > 1
            """.trimIndent(),
        ).query(Int::class.java).list()
        check(distinctOrdersUsedTwice.isNotEmpty()) {
            "expected at least one display_order value reused across categories - the composite " +
                "uniqueness would otherwise never be exercised by real seed data"
        }

        val category = jdbc.sql("SELECT category_code, display_order FROM report_event_types LIMIT 1")
            .query { rs, _ -> rs.getString("category_code") to rs.getInt("display_order") }
            .list().single()

        val rejected = runCatching {
            jdbc.sql(
                "INSERT INTO report_event_types (code, category_code, display_name, display_order, active) " +
                    "VALUES ('ZZZ_DUP_ORDER', :cat, 'x', :order, TRUE)",
            ).param("cat", category.first).param("order", category.second).update()
        }.isFailure
        check(rejected) { "duplicating an existing (category_code, display_order) pair must be rejected" }
    }

    @Test
    fun `the active event catalogue endpoint exposes exactly the active DB content, nested and ordered`() {
        val response = get("/api/v1/public/report-catalog")
        check(response.statusCode() == 200) { response.body() }
        val body = json(response)
        val categories = body.get("categories")
        check(categories.size() == 7) { "expected 7 categories in the catalogue, got ${categories.size()}" }

        var totalEventTypes = 0
        var lastOrder = Int.MIN_VALUE
        categories.forEach { category ->
            val eventTypes = category.get("eventTypes")
            totalEventTypes += eventTypes.size()
            // display_order is not itself in the response, but the DB-declared order must
            // be preserved - cross-check against the DB for this one category's code.
            val dbOrder = jdbc.sql("SELECT display_order FROM report_categories WHERE code = :code")
                .param("code", category.get("code").asText()).query(Int::class.java).single()
            check(dbOrder > lastOrder) { "categories must be returned in ascending display_order" }
            lastOrder = dbOrder
        }
        check(totalEventTypes == 61) { "expected 61 event types across all categories, got $totalEventTypes" }
    }

    @Test
    fun `deactivating one event type removes it from the catalogue but not from the database`() {
        jdbc.sql("UPDATE report_event_types SET active = FALSE WHERE code = 'FIGHT'").update()
        try {
            val response = get("/api/v1/public/report-catalog")
            check(response.statusCode() == 200)
            check(!response.body().contains("\"FIGHT\"")) { "a deactivated event type must not appear in the catalogue" }

            val stillThere = jdbc.sql("SELECT COUNT(*) FROM report_event_types WHERE code = 'FIGHT'")
                .query(Int::class.java).single()
            check(stillThere == 1) { "a deactivated event type must not be deleted" }
        } finally {
            jdbc.sql("UPDATE report_event_types SET active = TRUE WHERE code = 'FIGHT'").update()
        }
    }

    @Test
    fun `deactivating one category removes only that category, not the seed data`() {
        jdbc.sql("UPDATE report_categories SET active = FALSE WHERE code = 'VIOLENCE_DANGER'").update()
        try {
            val response = get("/api/v1/public/report-catalog")
            check(response.statusCode() == 200)
            val categories = json(response).get("categories")
            check(categories.size() == 6) { "expected 6 active categories, got ${categories.size()}" }
            categories.forEach { check(it.get("code").asText() != "VIOLENCE_DANGER") }
        } finally {
            jdbc.sql("UPDATE report_categories SET active = TRUE WHERE code = 'VIOLENCE_DANGER'").update()
        }
    }
}
