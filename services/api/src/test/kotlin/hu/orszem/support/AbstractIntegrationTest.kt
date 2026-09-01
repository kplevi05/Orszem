package hu.orszem.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Base class for backend integration tests. Boots the full Spring context
 * against a real PostgreSQL (Testcontainers) with Flyway applied, using the
 * `local` profile so the demo event catalog is present.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
abstract class AbstractIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            val container = PostgresTestContainer.instance
            registry.add("spring.datasource.url", container::getJdbcUrl)
            registry.add("spring.datasource.username", container::getUsername)
            registry.add("spring.datasource.password", container::getPassword)
            registry.add("orszem.auth.jwt.secret") { "integration-test-signing-secret-0123456789-abcdef" }
        }
    }
}
