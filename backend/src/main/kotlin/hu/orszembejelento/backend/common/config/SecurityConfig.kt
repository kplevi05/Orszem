package hu.orszembejelento.backend.common.config

import hu.orszembejelento.backend.auth.api.BearerTokenAuthenticationFilter
import hu.orszembejelento.backend.common.web.ApiPaths
import hu.orszembejelento.backend.common.web.CorrelationIdFilter
import hu.orszembejelento.backend.common.web.ErrorCode
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * HTTP security rules.
 *
 * Default-deny: everything not listed as public requires a valid bearer token. New
 * endpoints are therefore protected unless someone deliberately opens them, rather than
 * public until someone remembers to lock them.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        bearerFilter: BearerTokenAuthenticationFilter,
    ): SecurityFilterChain {
        http
            // No CSRF protection: this API is consumed by a native client with a bearer
            // token, never by a cookie-authenticated browser form, so there is no ambient
            // credential for a cross-site request to abuse. There is no Service Web.
            .csrf { it.disable() }
            // No CORS configuration at all. The Public Web calls its API same-origin
            // through the Caddy proxy, and the Android clients are not browsers, so no
            // origin ever needs to be allowed.
            .cors { it.disable() }
            // Every request is authenticated from its own bearer token; the server keeps no
            // HTTP session and issues no session cookie.
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .anonymous { }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .headers { headers ->
                headers.cacheControl { }
                headers.frameOptions { it.deny() }
            }
            .authorizeHttpRequests { registry ->
                registry
                    // Liveness/readiness for the platform. Caddy does not route this
                    // publicly; see deploy/caddy/Caddyfile.
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    // API identity, deliberately public.
                    .requestMatchers("${ApiPaths.V1}/meta").permitAll()
                    // Every anonymous Public endpoint: reference lookups, the event
                    // catalogue, and report submission/status. No service-area or
                    // authorisation information is ever returned from any of these - see
                    // PublicReferenceController and PublicReportController. Report GET is
                    // additionally protected by its own report-access-credential capability
                    // inside the application layer (ADR 0008) - Spring Security has no
                    // notion of that credential and never needs one.
                    .requestMatchers("${ApiPaths.V1}/public/**").permitAll()
                    // The three flows that by definition cannot present a valid access
                    // token yet. Each performs its own credential verification.
                    .requestMatchers(
                        "${ApiPaths.V1}/service/auth/login",
                        "${ApiPaths.V1}/service/auth/complete-password-change",
                        "${ApiPaths.V1}/service/auth/refresh",
                    ).permitAll()
                    // Local API documentation. Reachable on loopback for development;
                    // Caddy returns 404 for these on every public host.
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    .anyRequest().authenticated()
            }
            .addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter::class.java)
            .exceptionHandling { handling ->
                // Both handlers return the same generic body. A caller must not be able to
                // tell "no valid session" from "session valid but not permitted" by probing.
                handling.authenticationEntryPoint { _, response, _ ->
                    writeError(response, 401, ErrorCode.SESSION_INVALID)
                }
                handling.accessDeniedHandler { _, response, _ ->
                    writeError(response, 403, ErrorCode.SESSION_INVALID)
                }
            }

        return http.build()
    }

    /**
     * Writes the error body directly rather than through a serializer.
     *
     * Every value is a controlled constant or a server-generated UUID, so there is nothing
     * to escape, and this keeps the security filter chain independent of which Jackson
     * major version the framework happens to autoconfigure.
     */
    private fun writeError(
        response: jakarta.servlet.http.HttpServletResponse,
        status: Int,
        code: ErrorCode,
    ) {
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")

        val correlationId = response.getHeader(CorrelationIdFilter.HEADER) ?: "unknown"
        response.writer.write(
            """{"code":"${code.name}","message":"Authentication is required.","correlationId":"$correlationId"}""",
        )
    }
}
