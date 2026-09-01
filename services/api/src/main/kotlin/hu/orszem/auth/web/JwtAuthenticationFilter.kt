package hu.orszem.auth.web

import hu.orszem.auth.domain.AuthenticatedActor
import hu.orszem.auth.infrastructure.JwtService
import hu.orszem.shared.security.BearerAuthenticationFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** Authentication token whose principal is the [AuthenticatedActor]. */
class ServiceUserAuthentication(
    val actor: AuthenticatedActor,
) : AbstractAuthenticationToken(actor.capabilities.map { SimpleGrantedAuthority(it.name) }) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any = "" // never a secret
    override fun getPrincipal(): Any = actor
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
) : OncePerRequestFilter(), BearerAuthenticationFilter {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith(BEARER_PREFIX, ignoreCase = true)) {
            val token = header.substring(BEARER_PREFIX.length).trim()
            val actor = jwtService.parse(token)
            if (actor != null && SecurityContextHolder.getContext().authentication == null) {
                SecurityContextHolder.getContext().authentication = ServiceUserAuthentication(actor)
            }
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
    }
}

/** Convenience accessor for controllers / use cases. */
fun currentActor(): AuthenticatedActor? =
    (SecurityContextHolder.getContext().authentication as? ServiceUserAuthentication)?.actor
