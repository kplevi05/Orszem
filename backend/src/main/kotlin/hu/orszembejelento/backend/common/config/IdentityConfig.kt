package hu.orszembejelento.backend.common.config

import hu.orszembejelento.backend.identity.domain.CommonPasswordBlocklist
import hu.orszembejelento.backend.identity.domain.PasswordHasher
import hu.orszembejelento.backend.identity.domain.PasswordPolicy
import hu.orszembejelento.backend.identity.domain.ServiceIdGenerator
import hu.orszembejelento.backend.identity.domain.TemporaryCredentialGenerator
import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the identity domain, which is deliberately free of Spring annotations so it can be
 * unit-tested without a container.
 */
@Configuration(proxyBeanMethods = false)
class IdentityConfig {

    /**
     * A single UTC clock, injected everywhere a timestamp is needed.
     *
     * Nothing calls `Instant.now()` directly: with the clock as a bean, tests can advance
     * time to exercise token and session expiry deterministically instead of sleeping.
     */
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun passwordHasher(): PasswordHasher = PasswordHasher.default()

    @Bean
    fun commonPasswordBlocklist(): CommonPasswordBlocklist = CommonPasswordBlocklist.loadDefault()

    @Bean
    fun passwordPolicy(blocklist: CommonPasswordBlocklist): PasswordPolicy = PasswordPolicy(blocklist)

    @Bean
    fun serviceIdGenerator(): ServiceIdGenerator = ServiceIdGenerator()

    @Bean
    fun temporaryCredentialGenerator(): TemporaryCredentialGenerator = TemporaryCredentialGenerator()
}
