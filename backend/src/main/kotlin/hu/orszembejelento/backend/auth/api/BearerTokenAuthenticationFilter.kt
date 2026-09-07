package hu.orszembejelento.backend.auth.api

import hu.orszembejelento.backend.auth.application.AuthenticateAccessTokenUseCase
import hu.orszembejelento.backend.auth.application.AuthenticatedActor
import hu.orszembejelento.backend.auth.application.PasswordChangeRequiredException
import hu.orszembejelento.backend.auth.application.SessionInvalidException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Spring Security authentication for the opaque bearer token.
 *
 * Implemented as a filter rather than as parsing inside controllers, so authentication
 * happens before any controller or use case runs. That ordering is what guarantees a
 * request with an invalid token can never reach business logic and cause a state change
 * before being rejected.
 *
 * A missing or unparseable header is left unauthenticated rather than rejected outright:
 * whether that matters is for the security rules to decide, since some paths are public.
 */
@Component
class BearerTokenAuthenticationFilter(
    private val authenticate: AuthenticateAccessTokenUseCase,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            val token = header.substring(BEARER_PREFIX.length).trim()
            try {
                val actor = authenticate.authenticate(token)
                SecurityContextHolder.getContext().authentication = ActorAuthentication(actor)
            } catch (_: SessionInvalidException) {
                // Never log the token itself, only that a request failed to authenticate.
                log.debug("rejected a request with an invalid access token")
                SecurityContextHolder.clearContext()
            } catch (_: PasswordChangeRequiredException) {
                log.debug("rejected a protected request from an account owing a password change")
                SecurityContextHolder.clearContext()
            }
        }

        filterChain.doFilter(request, response)
    }

    companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}

/**
 * The authenticated principal, carrying the actor built from current database state.
 *
 * The role is exposed as a Spring authority so declarative checks work later, but it is
 * always derived from the `users` row for this request — never from anything the client sent.
 */
class ActorAuthentication(val actor: AuthenticatedActor) :
    AbstractAuthenticationToken(listOf(SimpleGrantedAuthority("ROLE_${actor.role.name}"))) {

    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): Any = actor
}
