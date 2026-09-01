package hu.orszem.demo

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Demo seed / reset. Available ONLY in the `local` and `demo` profiles and only
 * when `orszem.demo.seed-enabled=true`. Restores the documented baseline:
 * 120 reports (8 NEW / 6 IN_PROGRESS / 106 ARCHIVED), the `demo.service` user,
 * and (on the reset day) 16 "today" reports.
 *
 * Production builds/profiles never create this bean, so no reset path exists there.
 */
@Service
@Profile("local", "demo")
@ConditionalOnProperty(prefix = "orszem.demo", name = ["seed-enabled"], havingValue = "true")
class DemoDataService(
    private val dataSource: javax.sql.DataSource,
) {

    private val scripts = listOf(
        "db/demo/000_reset_demo.sql",
        "db/demo/010_demo_service_user.sql",
        "db/demo/020_demo_reports.sql",
    )

    /**
     * Runs the demo SQL scripts in a single transaction. Each script is executed
     * as one statement string; the PostgreSQL JDBC driver is dollar-quote aware,
     * so `DO $$ ... $$` blocks and multi-statement files work directly.
     */
    @Transactional
    fun reset() {
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    scripts.forEach { path ->
                        val sql = ClassPathResource(path).inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        statement.execute(sql)
                    }
                }
                connection.commit()
            } catch (ex: Exception) {
                connection.rollback()
                throw ex
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }
}
