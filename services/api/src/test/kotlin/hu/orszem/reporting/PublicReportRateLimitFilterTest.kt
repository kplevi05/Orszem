package hu.orszem.reporting

import com.fasterxml.jackson.databind.ObjectMapper
import hu.orszem.reporting.web.PublicReportRateLimitFilter
import hu.orszem.shared.config.OrszemProperties
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration

class PublicReportRateLimitFilterTest {

    private val props = OrszemProperties(
        publicReport = OrszemProperties.PublicReport(
            rateLimit = OrszemProperties.PublicReport.RateLimit(enabled = true, capacity = 3, window = Duration.ofMinutes(5)),
        ),
    )
    private val filter = PublicReportRateLimitFilter(props, ObjectMapper())

    private fun post(ip: String = "203.0.113.7"): MockHttpServletResponse {
        val request = MockHttpServletRequest("POST", "/api/v1/public/reports").apply {
            requestURI = "/api/v1/public/reports"
            remoteAddr = ip
        }
        val response = MockHttpServletResponse()
        val chain = FilterChain { _, res -> (res as MockHttpServletResponse).status = 201 }
        filter.doFilter(request, response, chain)
        return response
    }

    @Test
    fun `allows up to capacity then returns 429 with Retry-After and problem+json`() {
        repeat(3) { assertThat(post().status).isEqualTo(201) }

        val limited = post()
        assertThat(limited.status).isEqualTo(429)
        assertThat(limited.getHeader("Retry-After")).isNotNull()
        assertThat(limited.contentType).contains("application/problem+json")
        assertThat(limited.contentAsString).contains("RATE_LIMITED")
    }

    @Test
    fun `separate source IPs have independent budgets`() {
        repeat(3) { post(ip = "198.51.100.1") }
        assertThat(post(ip = "198.51.100.2").status).isEqualTo(201)
    }

    @Test
    fun `non report paths are not filtered`() {
        val request = MockHttpServletRequest("GET", "/api/v1/public/event-types").apply {
            requestURI = "/api/v1/public/event-types"
        }
        assertThat(filter.shouldNotFilter(request)).isTrue()
    }
}
