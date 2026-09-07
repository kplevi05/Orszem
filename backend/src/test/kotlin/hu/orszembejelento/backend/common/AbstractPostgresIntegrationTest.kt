package hu.orszembejelento.backend.common

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.boot.test.context.TestConfiguration
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Shared base for tests that need a real PostgreSQL.
 *
 * A real database, not H2: Flyway behaviour, the PostgreSQL driver and the connection
 * settings are exactly what production uses. `@ServiceConnection` wires the container
 * into the datasource, so no property plumbing is needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractPostgresIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    class Containers {
        @Bean
        @ServiceConnection
        fun postgres(): PostgreSQLContainer =
            PostgreSQLContainer("postgres:16-alpine").withReuse(false)
    }
}
