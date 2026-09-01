package hu.orszem.auth.web

import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AuthWebConfig {

    /**
     * The JWT filter is wired into the Spring Security chain explicitly
     * (`SecurityConfig`), so prevent Spring Boot from also registering it
     * directly with the servlet container (which would run it twice).
     */
    @Bean
    fun jwtAuthenticationFilterRegistration(
        filter: JwtAuthenticationFilter,
    ): FilterRegistrationBean<JwtAuthenticationFilter> =
        FilterRegistrationBean(filter).apply { isEnabled = false }
}
