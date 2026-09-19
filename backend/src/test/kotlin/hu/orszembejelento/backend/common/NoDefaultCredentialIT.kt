package hu.orszembejelento.backend.common

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.security.core.userdetails.UserDetailsService

/**
 * Phase 16 (security review): Spring Boot creates an in-memory user with a random password,
 * and prints that password to the startup log, whenever the context has no
 * [UserDetailsService]. Nothing here authenticates through one, so that credential would be
 * unused and would only leak into logs (journald in production).
 *
 * The default user is excluded on `BackendApplication`. Asserting on the context, rather than
 * scraping log output, keeps the guarantee deterministic: with no such bean there is nothing
 * to generate or print a password for.
 */
@Import(AbstractPostgresIntegrationTest.Containers::class)
class NoDefaultCredentialIT : AbstractPostgresIntegrationTest() {

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `no default in-memory user with a generated password exists`() {
        val beans = context.getBeanNamesForType(UserDetailsService::class.java)
        assertTrue(beans.isEmpty()) { "unexpected UserDetailsService beans: ${beans.toList()}" }
    }
}
