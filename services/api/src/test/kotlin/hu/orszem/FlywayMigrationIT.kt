package hu.orszem

import hu.orszem.support.AbstractIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

class FlywayMigrationIT @Autowired constructor(
    private val jdbc: JdbcTemplate,
) : AbstractIntegrationTest() {

    @Test
    fun `schema migration applies cleanly on an empty database`() {
        val tables = jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            String::class.java,
        )
        assertThat(tables).contains(
            "event_categories", "event_types", "users", "reports", "audit_events", "flyway_schema_history",
        )
    }

    @Test
    fun `demo event catalog is seeded with 7 categories and 61 active event types`() {
        val categories = jdbc.queryForObject(
            "SELECT count(*) FROM event_categories WHERE active = true", Int::class.java,
        )
        val eventTypes = jdbc.queryForObject(
            "SELECT count(*) FROM event_types WHERE active = true", Int::class.java,
        )
        assertThat(categories).isEqualTo(7)
        assertThat(eventTypes).isEqualTo(61)
    }

    @Test
    fun `report state consistency constraint rejects an inconsistent row`() {
        val eventTypeId = jdbc.queryForObject(
            "SELECT id FROM event_types WHERE code = 'KNIFE_ATTACK'", String::class.java,
        )
        val ex = runCatching {
            jdbc.update(
                """
                INSERT INTO reports (id, event_type_id, train_identifier, settlement, occurred_at, status)
                VALUES (gen_random_uuid(), ?::uuid, 'IC 123', 'Budapest', now(), 'ARCHIVED')
                """.trimIndent(),
                eventTypeId,
            )
        }.exceptionOrNull()
        assertThat(ex).isNotNull()
    }
}
