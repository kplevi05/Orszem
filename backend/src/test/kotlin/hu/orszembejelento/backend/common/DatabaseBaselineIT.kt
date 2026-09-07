package hu.orszembejelento.backend.common

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Proves the database baseline is real: the context starts against PostgreSQL, the
 * connection works, and Flyway ran and created its history table even though there are
 * no migrations yet. If Flyway were misconfigured or pointed at the wrong location,
 * `flyway_schema_history` would not exist.
 */
@Import(AbstractPostgresIntegrationTest.Containers::class)
class DatabaseBaselineIT : AbstractPostgresIntegrationTest() {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `connects to postgresql`() {
        val one = jdbcTemplate.queryForObject("SELECT 1", Int::class.java)
        check(one == 1) { "expected SELECT 1 to return 1, got $one" }

        val product = jdbcTemplate.dataSource!!.connection.use { it.metaData.databaseProductName }
        check(product == "PostgreSQL") { "expected PostgreSQL, got $product" }
    }

    @Test
    fun `flyway ran and created its schema history`() {
        val exists = jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'flyway_schema_history')",
            Boolean::class.java,
        )
        check(exists == true) { "Flyway did not create flyway_schema_history - migrations are not wired up" }
    }

    @Test
    fun `no business tables exist yet`() {
        val tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            String::class.java,
        )
        // Phase 1 owns no domain model. This guards against a placeholder table creeping in.
        check(tables.toSet() == setOf("flyway_schema_history")) {
            "expected only flyway_schema_history in the public schema, found: $tables"
        }
    }
}
