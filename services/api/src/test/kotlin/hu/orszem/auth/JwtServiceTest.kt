package hu.orszem.auth

import hu.orszem.auth.domain.AuthenticatedActor
import hu.orszem.auth.domain.Capability
import hu.orszem.auth.infrastructure.JwtService
import hu.orszem.shared.config.OrszemProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class JwtServiceTest {

    private val secret = "unit-test-signing-secret-that-is-long-enough-0123456789"
    private val actor = AuthenticatedActor(
        userId = UUID.randomUUID(),
        username = "demo.service",
        displayName = "Demo Szolgálat",
        role = "SERVICE_USER",
        capabilities = setOf(Capability.REPORT_ACCEPT, Capability.ANALYTICS_READ),
    )

    private fun service(clock: Clock, ttl: Duration = Duration.ofHours(8), issuer: String = "orszem-demo"): JwtService {
        val props = OrszemProperties(
            auth = OrszemProperties.Auth(OrszemProperties.Auth.Jwt(secret = secret, issuer = issuer, ttl = ttl)),
        )
        return JwtService(props, clock).also { it.init() }
    }

    @Test
    fun `issues and parses a token round-trip`() {
        val svc = service(Clock.fixed(Instant.parse("2026-09-01T10:00:00Z"), ZoneOffset.UTC))
        val issued = svc.issue(actor)

        val parsed = svc.parse(issued.token)
        assertThat(parsed).isNotNull
        assertThat(parsed!!.userId).isEqualTo(actor.userId)
        assertThat(parsed.username).isEqualTo("demo.service")
        assertThat(parsed.capabilities).containsExactlyInAnyOrderElementsOf(actor.capabilities)
        assertThat(issued.expiresAt).isEqualTo(Instant.parse("2026-09-01T18:00:00Z"))
    }

    @Test
    fun `rejects a token after it expires`() {
        val issueClock = Clock.fixed(Instant.parse("2026-09-01T10:00:00Z"), ZoneOffset.UTC)
        val token = service(issueClock, ttl = Duration.ofHours(1)).issue(actor).token

        val laterClock = Clock.fixed(Instant.parse("2026-09-01T11:30:00Z"), ZoneOffset.UTC)
        assertThat(service(laterClock, ttl = Duration.ofHours(1)).parse(token)).isNull()
    }

    @Test
    fun `rejects a tampered token`() {
        val svc = service(Clock.systemUTC())
        val token = svc.issue(actor).token
        val tampered = token.dropLast(3) + "abc"
        assertThat(svc.parse(tampered)).isNull()
    }

    @Test
    fun `rejects a token from a different issuer`() {
        val clock = Clock.fixed(Instant.parse("2026-09-01T10:00:00Z"), ZoneOffset.UTC)
        val token = service(clock, issuer = "someone-else").issue(actor).token
        assertThat(service(clock, issuer = "orszem-demo").parse(token)).isNull()
    }
}
