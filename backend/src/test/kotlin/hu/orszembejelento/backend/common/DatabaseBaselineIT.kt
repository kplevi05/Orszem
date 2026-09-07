package hu.orszembejelento.backend.common

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Proves the database baseline is real: the context starts against PostgreSQL, the
 * connection works, and Flyway is configured, running, and scanning its migration location.
 *
 * Asserting on the Flyway bean itself matters. Configuring Flyway in `application.yml`
 * proves nothing on its own - Spring Boot 4 moved `FlywayAutoConfiguration` into a separate
 * module, so a build that depends only on `flyway-core` has Flyway on the classpath, has
 * apparently valid configuration, and never runs it. That failure is silent until a
 * migration mysteriously fails to apply.
 */
@Import(AbstractPostgresIntegrationTest.Containers::class)
class DatabaseBaselineIT : AbstractPostgresIntegrationTest() {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var flyway: Flyway

    @Test
    fun `connects to postgresql`() {
        val one = jdbcTemplate.queryForObject("SELECT 1", Int::class.java)
        check(one == 1) { "expected SELECT 1 to return 1, got $one" }

        val product = jdbcTemplate.dataSource!!.connection.use { it.metaData.databaseProductName }
        check(product == "PostgreSQL") { "expected PostgreSQL, got $product" }
    }

    @Test
    fun `flyway is configured against the intended location`() {
        val locations = flyway.configuration.locations.map { it.descriptor }
        check(locations.contains("classpath:db/migration")) {
            "Flyway is not pointed at classpath:db/migration, got: $locations"
        }
        check(flyway.configuration.isCleanDisabled) {
            "flyway.clean must stay disabled in every environment"
        }
    }

    @Test
    fun `flyway scans its location and finds no migrations yet`() {
        // info() connects and resolves the location. It throwing would mean the location
        // is unreadable or the database is unreachable; an empty result is the correct
        // Phase 1 state, since no domain model exists yet.
        val migrations = flyway.info().all()
        check(migrations.isEmpty()) {
            "Phase 1 expects no migrations, found: ${migrations.map { it.script }}"
        }
    }

    @Test
    fun `flyway created its schema history and nothing else`() {
        val tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            String::class.java,
        )
        // Flyway creates its history table on startup even with zero migrations, so its
        // presence confirms Flyway actually ran. Requiring it to be the *only* table also
        // guards against a placeholder table introduced merely to justify a migration.
        check(tables.toSet() == setOf("flyway_schema_history")) {
            "expected only flyway_schema_history in the public schema, found: $tables"
        }
    }
}
