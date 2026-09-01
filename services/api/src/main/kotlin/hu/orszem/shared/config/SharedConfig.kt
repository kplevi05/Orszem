package hu.orszem.shared.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset

@Configuration
class SharedConfig {

    /** Single UTC clock, injected everywhere time is needed so tests can substitute it. */
    @Bean
    fun clock(): Clock = Clock.system(ZoneOffset.UTC)
}

/**
 * Business calendar settings. `todayReports` and any calendar-day analytics use
 * this zone (Demo v1 default: Europe/Budapest).
 */
@ConfigurationProperties(prefix = "orszem")
data class OrszemProperties(
    val businessTimeZone: ZoneId = ZoneId.of("Europe/Budapest"),
    val auth: Auth = Auth(),
    val publicReport: PublicReport = PublicReport(),
    val demo: Demo = Demo(),
) {
    data class Auth(
        val jwt: Jwt = Jwt(),
    ) {
        data class Jwt(
            val secret: String = "",
            val issuer: String = "orszem-demo",
            val ttl: java.time.Duration = java.time.Duration.ofHours(8),
        )
    }

    data class PublicReport(
        val maxFutureSkew: java.time.Duration = java.time.Duration.ofMinutes(5),
        val rateLimit: RateLimit = RateLimit(),
    ) {
        data class RateLimit(
            val enabled: Boolean = true,
            val capacity: Long = 20,
            val window: java.time.Duration = java.time.Duration.ofMinutes(5),
        )
    }

    data class Demo(
        val seedEnabled: Boolean = false,
        /** Shared secret for the demo reset endpoint. Blank disables the endpoint. */
        val resetToken: String = "",
    )
}
