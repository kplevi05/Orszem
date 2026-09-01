package hu.orszem.support

import org.testcontainers.containers.PostgreSQLContainer

/**
 * Single reusable PostgreSQL container for the whole test run.
 * Time zone is forced to UTC to match the production runtime.
 */
object PostgresTestContainer {

    val instance: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("orszem")
            .withUsername("orszem")
            .withPassword("orszem")
            .withEnv("TZ", "UTC")
            .withEnv("PGTZ", "UTC")
            .also { it.start() }
    }
}
