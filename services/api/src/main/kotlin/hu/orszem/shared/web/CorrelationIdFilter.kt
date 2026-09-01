package hu.orszem.shared.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

const val CORRELATION_ID_HEADER = "X-Correlation-Id"
const val CORRELATION_ID_MDC_KEY = "correlationId"

/**
 * Assigns a correlation id to every request (honouring an inbound
 * `X-Correlation-Id` when present and well-formed), exposes it via MDC for
 * structured logging and echoes it back on the response.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class CorrelationIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val correlationId = request.getHeader(CORRELATION_ID_HEADER)
            ?.takeIf { it.isNotBlank() && it.length <= 64 && it.all { c -> c.isLetterOrDigit() || c == '-' } }
            ?: UUID.randomUUID().toString()
        MDC.put(CORRELATION_ID_MDC_KEY, correlationId)
        response.setHeader(CORRELATION_ID_HEADER, correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY)
        }
    }
}

fun currentCorrelationId(): String? = MDC.get(CORRELATION_ID_MDC_KEY)
