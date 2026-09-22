package hu.orszembejelento.backend

import hu.orszembejelento.backend.common.config.ApiProperties
import hu.orszembejelento.backend.common.config.AuthProperties
import hu.orszembejelento.backend.common.config.PublicSubmissionRateLimitProperties
import hu.orszembejelento.backend.common.config.ReportSubmissionProperties
import hu.orszembejelento.backend.common.config.WorkflowFallbackProperties
import hu.orszembejelento.backend.maintenance.MaintenanceProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration

// Authentication is entirely our own (Service login, rotating refresh tokens, a per-request
// session check). With no UserDetailsService bean Spring Boot would create an in-memory user
// and print its generated password at startup: an unused credential written to the log,
// which the logging policy forbids. Excluded here rather than in application.yml so it holds
// in every environment, including tests, which use their own application.yml.
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@EnableConfigurationProperties(
    ApiProperties::class,
    AuthProperties::class,
    MaintenanceProperties::class,
    PublicSubmissionRateLimitProperties::class,
    ReportSubmissionProperties::class,
    WorkflowFallbackProperties::class,
)
class BackendApplication

fun main(args: Array<String>) {
    runApplication<BackendApplication>(*args)
}
