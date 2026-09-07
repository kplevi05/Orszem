package hu.orszembejelento.backend.common

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Verifies that the schema PostgreSQL actually ends up with is the one V001 intends.
 *
 * In Phase 1 this asserted that the database was empty apart from Flyway's own bookkeeping.
 * V001 legitimately changes that, so the assertion is not removed but tightened: it now
 * pins the exact set of tables, the constraints that enforce data shape, and the indexes
 * the authentication flows and future cleanup depend on.
 *
 * Asserting on the real database rather than on the migration text is the point — a
 * constraint that fails to apply, or an index silently renamed, would pass a file-content
 * check and fail here.
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
    fun `every migration applied in order and validates`() {
        val applied = jdbcTemplate.queryForList(
            "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank",
        )

        // Pinned explicitly rather than counted loosely: an unexpected extra migration, or
        // one applied out of order, should fail here rather than surface as a schema
        // mystery later.
        check(applied.map { it["version"] } == listOf("001", "002")) {
            "unexpected migration history: $applied"
        }
        check(applied.all { it["success"] == true }) { "a migration did not apply successfully: $applied" }

        // Re-validating catches a checksum change, which is how an edit to an already
        // applied migration would show up. V001 in particular is immutable.
        flyway.validate()
    }

    @Test
    fun `creates exactly the tables the implemented phases own`() {
        val tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            String::class.java,
        ).toSet()

        check(
            tables == setOf(
                "flyway_schema_history",
                // Phase 2 - identity, sessions, audit
                "users",
                "auth_sessions",
                "refresh_tokens",
                "audit_events",
                // Phase 3 - reference data, service-area scope
                "settlements",
                "railway_lines",
                "settlement_railway_lines",
                "reference_dataset_imports",
                "service_areas",
                "service_area_railway_lines",
                "user_service_areas",
            ),
        ) {
            "unexpected schema. Reports, taxonomy, stations and sections belong to later " +
                "phases and must not exist yet. Found: $tables"
        }
    }

    @Test
    fun `enforces the canonical service id shape in the database`() {
        val insert = { serviceId: String ->
            jdbcTemplate.update(
                """
                INSERT INTO users (id, service_id, role, status, password_hash,
                                   must_change_password, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, 'SERVICE_USER', 'ACTIVE', 'x', TRUE, now(), now())
                """.trimIndent(),
                serviceId,
            )
        }

        insert("SZ-123456")

        // The database is the last line of defence, independent of application validation.
        listOf("SZ-12345", "SZ-1234567", "sz-123456", "XX-123456", "123456", "SZ-12345A")
            .forEach { invalid ->
                val rejected = runCatching { insert(invalid) }.isFailure
                check(rejected) { "the CHECK constraint should have rejected: $invalid" }
            }
    }

    @Test
    fun `enforces service id uniqueness`() {
        val insert = {
            jdbcTemplate.update(
                """
                INSERT INTO users (id, service_id, role, status, password_hash,
                                   must_change_password, created_at, updated_at)
                VALUES (gen_random_uuid(), 'SZ-424242', 'SERVICE_USER', 'ACTIVE', 'x', TRUE, now(), now())
                """.trimIndent(),
            )
        }
        insert()
        check(runCatching { insert() }.isFailure) { "service_id must be unique" }
    }

    @Test
    fun `enforces the role and status vocabularies`() {
        val insertWith = { role: String, status: String ->
            jdbcTemplate.update(
                """
                INSERT INTO users (id, service_id, role, status, password_hash,
                                   must_change_password, created_at, updated_at)
                VALUES (gen_random_uuid(), 'SZ-777777', ?, ?, 'x', TRUE, now(), now())
                """.trimIndent(),
                role,
                status,
            )
        }
        check(runCatching { insertWith("ADMIN", "ACTIVE") }.isFailure) { "unknown role must be rejected" }
        check(runCatching { insertWith("SERVICE_USER", "DELETED") }.isFailure) { "unknown status must be rejected" }
    }

    @Test
    fun `creates the indexes the auth flows and future cleanup rely on`() {
        val indexes = jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'",
            String::class.java,
        ).toSet()

        listOf(
            "ux_users_service_id",
            "ix_auth_sessions_user",
            "ix_auth_sessions_active",
            // Retention: expired rows must be findable cheaply when cleanup is added.
            "ix_auth_sessions_expiry",
            "ix_refresh_tokens_session",
            "ix_refresh_tokens_expiry",
            "ix_audit_events_target",
            "ix_audit_events_operation",
        ).forEach { expected ->
            check(expected in indexes) { "missing index $expected; found: $indexes" }
        }
    }

    @Test
    fun `audit rows must name a user actor and must not name one for system actors`() {
        val insert = { actorType: String, actorUserId: String? ->
            jdbcTemplate.update(
                """
                INSERT INTO audit_events (id, operation_id, actor_type, actor_user_id,
                                          event_type, target_type, target_id, created_at)
                VALUES (gen_random_uuid(), gen_random_uuid(), ?, CAST(? AS uuid),
                        'SESSION_CREATED', 'SESSION', NULL, now())
                """.trimIndent(),
                actorType,
                actorUserId,
            )
        }

        insert("SYSTEM", null)
        check(runCatching { insert("USER", null) }.isFailure) { "a USER actor must identify the user" }
    }
}
