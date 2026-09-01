package hu.orszem.shared.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * One structured line per request: method, path, status, duration and the
 * correlation id. Request/response bodies and headers (including Authorization)
 * are never logged (§15 Naplózás, audit és adatvédelem).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
class RequestLoggingFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger("hu.orszem.access")

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI.startsWith("/actuator")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val start = System.nanoTime()
        try {
            filterChain.doFilter(request, response)
        } finally {
            val durationMs = (System.nanoTime() - start) / 1_000_000
            log.info(
                "{} {} -> {} ({} ms) [{}]",
                request.method,
                request.requestURI,
                response.status,
                durationMs,
                currentCorrelationId(),
            )
        }
    }
}
