package hu.orszem.auth.infrastructure

import hu.orszem.auth.domain.AuthenticatedActor
import hu.orszem.auth.domain.Capability
import hu.orszem.shared.config.OrszemProperties
import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

data class IssuedToken(val token: String, val expiresAt: Instant)

@Service
class JwtService(
    private val properties: OrszemProperties,
    private val clock: Clock,
) {
    private val config = properties.auth.jwt
    private lateinit var key: SecretKey

    @PostConstruct
    fun init() {
        val secret = config.secret
        require(secret.isNotBlank() && secret.toByteArray(Charsets.UTF_8).size >= 32) {
            "orszem.auth.jwt.secret must be configured with at least 32 bytes (set ORSZEM_JWT_SECRET)."
        }
        key = Keys.hmacShaKeyFor(secret.toByteArray(Charsets.UTF_8))
    }

    fun issue(actor: AuthenticatedActor): IssuedToken {
        val now = clock.instant()
        val expiresAt = now.plus(config.ttl)
        val token = Jwts.builder()
            .issuer(config.issuer)
            .subject(actor.userId.toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .claim("username", actor.username)
            .claim("displayName", actor.displayName)
            .claim("role", actor.role)
            .claim("capabilities", actor.capabilities.map { it.name })
            .signWith(key)
            .compact()
        return IssuedToken(token, expiresAt)
    }

    /** Returns the actor if the token is a valid, unexpired, correctly-signed token from this issuer. */
    fun parse(token: String): AuthenticatedActor? = try {
        val claims: Claims = Jwts.parser()
            .verifyWith(key)
            .requireIssuer(config.issuer)
            .clock { Date.from(clock.instant()) }
            .build()
            .parseSignedClaims(token)
            .payload
        @Suppress("UNCHECKED_CAST")
        val capabilities = (claims["capabilities"] as? List<String> ?: emptyList())
            .mapNotNull { runCatching { Capability.valueOf(it) }.getOrNull() }
            .toSet()
        AuthenticatedActor(
            userId = UUID.fromString(claims.subject),
            username = claims["username"] as? String ?: "",
            displayName = claims["displayName"] as? String ?: "",
            role = claims["role"] as? String ?: "SERVICE_USER",
            capabilities = capabilities,
        )
    } catch (ex: JwtException) {
        null
    } catch (ex: IllegalArgumentException) {
        null
    }
}
