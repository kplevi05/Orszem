package hu.orszem.reporting.web

import com.fasterxml.jackson.databind.ObjectMapper
import hu.orszem.shared.config.OrszemProperties
import hu.orszem.shared.error.ErrorCode
import hu.orszem.shared.error.ProblemResponse
import hu.orszem.shared.web.currentCorrelationId
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Minimal in-memory rate limit for `POST /api/v1/public/reports`
 * (BUSINESS_RULES.md §10). Not a production spam system. The source IP is used
 * only as an ephemeral bucket key and is never persisted.
 */
@Component
class PublicReportRateLimitFilter(
    private val properties: OrszemProperties,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    private val buckets = ConcurrentHashMap<String, Bucket>()

    public override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (!properties.publicReport.rateLimit.enabled) return true
        return !(request.method == "POST" && request.requestURI == "/api/v1/public/reports")
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val bucket = buckets.computeIfAbsent(clientKey(request)) { newBucket() }
        val probe = bucket.tryConsumeAndReturnRemaining(1)
        if (probe.isConsumed) {
            filterChain.doFilter(request, response)
            return
        }
        val retryAfter = ceil(probe.nanosToWaitForRefill / 1_000_000_000.0).toLong().coerceAtLeast(1)
        response.status = ErrorCode.RATE_LIMITED.status.value()
        response.setHeader(HttpHeaders.RETRY_AFTER, retryAfter.toString())
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = "UTF-8"
        objectMapper.writeValue(
            response.outputStream,
            ProblemResponse(
                title = ErrorCode.RATE_LIMITED.title,
                status = ErrorCode.RATE_LIMITED.status.value(),
                code = ErrorCode.RATE_LIMITED.name,
                detail = "Túl sok kérés érkezett rövid időn belül.",
                correlationId = currentCorrelationId(),
            ),
        )
    }

    private fun newBucket(): Bucket {
        val rl = properties.publicReport.rateLimit
        val window = if (rl.window < Duration.ofSeconds(1)) Duration.ofSeconds(1) else rl.window
        val limit = Bandwidth.builder()
            .capacity(rl.capacity)
            .refillIntervally(rl.capacity, window)
            .build()
        return Bucket.builder().addLimit(limit).build()
    }

    private fun clientKey(request: HttpServletRequest): String {
        val forwarded = request.getHeader("X-Forwarded-For")
        return forwarded?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.remoteAddr
            ?: "unknown"
    }
}
