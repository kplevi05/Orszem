package hu.orszembejelento.backend.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Gives every request a server-generated correlation ID, exposed in the MDC for logging,
 * in the `X-Correlation-Id` response header, and in error bodies.
 *
 * A client-supplied ID is deliberately **not** adopted as the canonical value. Trusting it
 * would let a caller collide or forge identifiers and make the logs of one user's requests
 * indistinguishable from another's.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val correlationId = UUID.randomUUID().toString()
        request.setAttribute(ATTRIBUTE, correlationId)
        response.setHeader(HEADER, correlationId)
        MDC.put(MDC_KEY, correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val HEADER = "X-Correlation-Id"
        const val ATTRIBUTE = "orszem.correlationId"
        const val MDC_KEY = "correlationId"

        fun current(request: HttpServletRequest): String =
            request.getAttribute(ATTRIBUTE) as? String ?: "unknown"
    }
}
