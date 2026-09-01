package hu.orszem.reporting

import hu.orszem.support.AbstractIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import java.util.UUID

@DirtiesContext
class PublicReportRateLimitIT @Autowired constructor(
    private val rest: TestRestTemplate,
) : AbstractIntegrationTest() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun rateLimitProperties(registry: DynamicPropertyRegistry) {
            registry.add("orszem.public-report.rate-limit.capacity") { "3" }
            registry.add("orszem.public-report.rate-limit.window") { "PT5M" }
        }
    }

    @Test
    fun `exceeding the capacity returns 429 RATE_LIMITED with Retry-After`() {
        fun send() = rest.postForEntity(
            "/api/v1/public/reports",
            mapOf(
                "id" to UUID.randomUUID(),
                "eventTypeCode" to "LOUD_BEHAVIOR",
                "trainIdentifier" to "IC 123",
                "settlement" to "Budapest",
                "occurredAt" to Instant.now().minusSeconds(30).toString(),
            ),
            String::class.java,
        )

        val statuses = (1..20).map { send() }
        val limited = statuses.firstOrNull { it.statusCode == HttpStatus.TOO_MANY_REQUESTS }
        assertThat(limited).describedAs("expected at least one 429").isNotNull()
        assertThat(limited!!.headers.getFirst("Retry-After")).isNotNull()
        assertThat(limited.body).contains("RATE_LIMITED")
        // Successful ones bounded by capacity (x2 if the loopback is dual-stack).
        assertThat(statuses.count { it.statusCode.is2xxSuccessful }).isBetween(3, 6)
    }
}
