package hu.orszem.shared.security

import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/** Marker for the module-contributed Bearer/JWT authentication filter (M3). */
interface BearerAuthenticationFilter : jakarta.servlet.Filter

@Configuration
class SecurityConfig(
    private val problemAuthenticationEntryPoint: ProblemAuthenticationEntryPoint,
    private val problemAccessDeniedHandler: ProblemAccessDeniedHandler,
    // Contributed by the `auth` module (M3). Optional so the security chain is
    // well-defined even before it exists.
    private val bearerAuthFilter: ObjectProvider<BearerAuthenticationFilter>,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .anonymous(AbstractHttpConfigurer<*, *>::disable)
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(
                    "/api/v1/public/**",
                    "/api/v1/service/auth/login",
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info",
                    "/error",
                ).permitAll()
                auth.requestMatchers("/api/v1/service/**").authenticated()
                auth.anyRequest().denyAll()
            }
            .exceptionHandling {
                it.authenticationEntryPoint(problemAuthenticationEntryPoint)
                it.accessDeniedHandler(problemAccessDeniedHandler)
            }

        bearerAuthFilter.ifAvailable { filter ->
            http.addFilterBefore(filter as jakarta.servlet.Filter, UsernamePasswordAuthenticationFilter::class.java)
        }
        return http.build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder =
        // Matches the demo seed hash parameters (saltLength=16, hashLength=32, p=1, m=65536, t=3).
        Argon2PasswordEncoder(16, 32, 1, 65536, 3)
}
