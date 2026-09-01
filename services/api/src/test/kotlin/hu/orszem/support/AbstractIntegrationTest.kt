package hu.orszem.support

import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.concurrent.TimeUnit

/**
 * Base class for backend integration tests. Boots the full Spring context
 * against a real PostgreSQL (Testcontainers) with Flyway applied, using the
 * `local` profile so the demo event catalog is present.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local", "test")
abstract class AbstractIntegrationTest {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    /**
     * Reconfigure [TestRestTemplate]'s HTTP client with automatic retries
     * disabled. Apache HttpClient 5 otherwise honours `Retry-After` on a 429 by
     * sleeping for that many seconds, which would stall the rate-limit test for
     * minutes. Keeps a generous pool so the concurrent-accept test doesn't
     * starve on connection leases.
     */
    @BeforeEach
    fun useNonRetryingHttpClient() {
        val connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnTotal(50)
            .setMaxConnPerRoute(50)
            .build()
        val client = HttpClients.custom()
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setConnectionRequestTimeout(10, TimeUnit.SECONDS)
                    .setResponseTimeout(30, TimeUnit.SECONDS)
                    .build(),
            )
            .build()
        restTemplate.restTemplate.requestFactory = HttpComponentsClientHttpRequestFactory(client)
    }

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
