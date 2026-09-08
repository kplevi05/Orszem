package hu.orszembejelento.backend.reference

import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * The V002 invariants, asserted against real PostgreSQL.
 *
 * These are database-level guarantees on purpose: they are the rules that make routing
 * deterministic, and a rule enforced only in application code stops holding the moment two
 * requests run at once.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class ReferenceSchemaIT : AbstractAuthIntegrationTest() {

    private fun insertSettlement(ksh: String, name: String = "Test", active: Boolean = true) =
        jdbc.sql(
            """
            INSERT INTO settlements (id, ksh_code, name, active, created_at, updated_at)
            VALUES (gen_random_uuid(), :ksh, :name, :active, now(), now())
            RETURNING id
            """.trimIndent(),
        ).param("ksh", ksh).param("name", name).param("active", active)
            .query(UUID::class.java).single()

    private fun insertLine(code: String, active: Boolean = true) =
        jdbc.sql(
            """
            INSERT INTO railway_lines (id, line_code, display_name, active, created_at, updated_at)
            VALUES (gen_random_uuid(), :code, :name, :active, now(), now())
            RETURNING id
            """.trimIndent(),
        ).param("code", code).param("name", "Line $code").param("active", active)
            .query(UUID::class.java).single()

    private fun insertArea(name: String, status: String = "ACTIVE") =
        jdbc.sql(
            """
            INSERT INTO service_areas (id, name, status, created_at, updated_at)
            VALUES (gen_random_uuid(), :name, :status, now(), now())
            RETURNING id
            """.trimIndent(),
        ).param("name", name).param("status", status).query(UUID::class.java).single()

    // ------------------------------------------------------------- settlements

    @Test
    fun `the ksh code shape is enforced by the database`() {
        insertSettlement("01234") // valid, and its leading zero must survive

        val stored = jdbc.sql("SELECT ksh_code FROM settlements WHERE ksh_code = '01234'")
            .query(String::class.java).single()
        check(stored == "01234") { "leading zeroes must be preserved, got $stored" }

        listOf("1234", "123456", "1234A", "", "abcde", "12 45", "12.45").forEach { invalid ->
            check(runCatching { insertSettlement(invalid) }.isFailure) {
                "the CHECK constraint should have rejected '$invalid'"
            }
        }
    }

    @Test
    fun `ksh codes are unique`() {
        insertSettlement("11111")
        check(runCatching { insertSettlement("11111", name = "Another") }.isFailure) {
            "a duplicate KSH code must be rejected"
        }
    }

    @Test
    fun `inactive settlements remain persisted`() {
        val id = insertSettlement("22222", active = false)
        val stillThere = jdbc.sql("SELECT COUNT(*) FROM settlements WHERE id = :id")
            .param("id", id).query(Int::class.java).single()
        check(stillThere == 1) { "a deactivated settlement must not be deleted" }
    }

    // ----------------------------------------------------------- railway lines

    @Test
    fun `line codes are unique and may carry letter suffixes`() {
        insertLine("1")
        insertLine("17a") // must remain representable

        check(runCatching { insertLine("1") }.isFailure) { "a duplicate line code must be rejected" }
    }

    // --------------------------------------------------------------- relations

    @Test
    fun `a settlement-line relation cannot be duplicated`() {
        val settlement = insertSettlement("33333")
        val line = insertLine("30")

        jdbc.sql("INSERT INTO settlement_railway_lines VALUES (:s, :l)")
            .param("s", settlement).param("l", line).update()

        val duplicate = runCatching {
            jdbc.sql("INSERT INTO settlement_railway_lines VALUES (:s, :l)")
                .param("s", settlement).param("l", line).update()
        }
        check(duplicate.isFailure) { "the composite primary key must reject a duplicate relation" }
    }

    @Test
    fun `a relation cannot reference a settlement or line that does not exist`() {
        val settlement = insertSettlement("44444")
        val line = insertLine("40")

        check(
            runCatching {
                jdbc.sql("INSERT INTO settlement_railway_lines VALUES (:s, :l)")
                    .param("s", settlement).param("l", UUID.randomUUID()).update()
            }.isFailure,
        ) { "a dangling railway line reference must be rejected" }

        check(
            runCatching {
                jdbc.sql("INSERT INTO settlement_railway_lines VALUES (:s, :l)")
                    .param("s", UUID.randomUUID()).param("l", line).update()
            }.isFailure,
        ) { "a dangling settlement reference must be rejected" }
    }

    // ------------------------------------------------------------ service areas

    @Test
    fun `the service area status vocabulary is enforced`() {
        insertArea("Nyugat", "ACTIVE")
        insertArea("Kelet", "INACTIVE")

        check(runCatching { insertArea("Rossz", "ARCHIVED") }.isFailure) {
            "an unknown status must be rejected"
        }
        check(runCatching { insertArea("Rossz2", "active") }.isFailure) {
            "the vocabulary is case-sensitive"
        }
    }

    @Test
    fun `service area names are unique`() {
        insertArea("Duplikatum")
        check(runCatching { insertArea("Duplikatum") }.isFailure) {
            "two areas sharing a name would make every operational decision ambiguous"
        }
    }

    // ------------------------------------------- one line belongs to one area

    @Test
    fun `a railway line cannot belong to two service areas`() {
        val line = insertLine("60")
        val west = insertArea("Nyugati terület")
        val east = insertArea("Keleti terület")

        jdbc.sql("INSERT INTO service_area_railway_lines VALUES (:a, :l)")
            .param("a", west).param("l", line).update()

        val second = runCatching {
            jdbc.sql("INSERT INTO service_area_railway_lines VALUES (:a, :l)")
                .param("a", east).param("l", line).update()
        }
        check(second.isFailure) { "the unique index on railway_line_id must reject a second area" }
    }

    @Test
    fun `concurrent attempts to claim one line leave exactly one mapping`() {
        val line = insertLine("70")
        val areas = (1..6).map { insertArea("Verseny $it") }

        val pool = Executors.newFixedThreadPool(areas.size)
        val ready = CountDownLatch(areas.size)
        val go = CountDownLatch(1)
        try {
            val futures = areas.map { areaId ->
                pool.submit<Boolean> {
                    ready.countDown()
                    go.await(10, TimeUnit.SECONDS)
                    runCatching {
                        jdbc.sql("INSERT INTO service_area_railway_lines VALUES (:a, :l)")
                            .param("a", areaId).param("l", line).update()
                    }.isSuccess
                }
            }
            check(ready.await(10, TimeUnit.SECONDS))
            go.countDown()
            val succeeded = futures.count { it.get(30, TimeUnit.SECONDS) }

            check(succeeded == 1) { "exactly one claim may win, $succeeded did" }
        } finally {
            pool.shutdownNow()
        }

        val mappings = jdbc.sql("SELECT COUNT(*) FROM service_area_railway_lines WHERE railway_line_id = :l")
            .param("l", line).query(Int::class.java).single()
        check(mappings == 1) { "the database must hold exactly one mapping, found $mappings" }
    }

    @Test
    fun `a line may belong to no area at all`() {
        val line = insertLine("80")
        val count = jdbc.sql("SELECT COUNT(*) FROM service_area_railway_lines WHERE railway_line_id = :l")
            .param("l", line).query(Int::class.java).single()
        check(count == 0) { "an unassigned line is valid and routes to UNCLASSIFIED later" }
    }

    // ------------------------------------------------------------ authorisation

    @Test
    fun `global area access defaults to false`() {
        val user = givenUser()
        val flag = jdbc.sql("SELECT global_area_access FROM users WHERE id = :id")
            .param("id", user.id).query(Boolean::class.java).single()
        check(flag == false) { "the wide permission must never be the default" }
    }

    @Test
    fun `a user-area assignment cannot reference a missing user or area`() {
        val user = givenUser()
        val area = insertArea("Jogosultsag")

        jdbc.sql("INSERT INTO user_service_areas VALUES (:u, :a)")
            .param("u", user.id).param("a", area).update()

        check(
            runCatching {
                jdbc.sql("INSERT INTO user_service_areas VALUES (:u, :a)")
                    .param("u", user.id).param("a", UUID.randomUUID()).update()
            }.isFailure,
        ) { "a dangling area reference must be rejected" }

        check(
            runCatching {
                jdbc.sql("INSERT INTO user_service_areas VALUES (:u, :a)")
                    .param("u", UUID.randomUUID()).param("a", area).update()
            }.isFailure,
        ) { "a dangling user reference must be rejected" }
    }

    // --------------------------------------------------- separation of concerns

    @Test
    fun `reference tables carry no service area or authorisation columns`() {
        // The structural guarantee behind the four-concepts rule: if a shortcut column ever
        // appears here, the separation has been quietly abandoned.
        val referenceColumns = jdbc.sql(
            """
            SELECT table_name || '.' || column_name
              FROM information_schema.columns
             WHERE table_schema = 'public'
               AND table_name IN ('settlements', 'railway_lines', 'settlement_railway_lines')
            """.trimIndent(),
        ).query(String::class.java).list().filterNotNull()

        listOf("service_area", "user_id", "permission", "allowed", "role").forEach { forbidden ->
            val offenders = referenceColumns.filter { it.contains(forbidden, ignoreCase = true) }
            check(offenders.isEmpty()) {
                "reference data must not contain '$forbidden': $offenders"
            }
        }
    }

    @Test
    fun `V002 created exactly the expected tables`() {
        val tables = jdbc.sql(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
        ).query(String::class.java).list().filterNotNull().toSet()

        listOf(
            "settlements", "railway_lines", "settlement_railway_lines",
            "reference_dataset_imports", "service_areas", "service_area_railway_lines",
            "user_service_areas",
        ).forEach { expected ->
            check(expected in tables) { "missing table $expected" }
        }

        // Phase 3 owns no report or station tables.
        listOf("reports", "stations", "operational_points", "sections_of_line").forEach { forbidden ->
            check(forbidden !in tables) { "$forbidden belongs to a later phase, not Phase 3" }
        }
    }
}
