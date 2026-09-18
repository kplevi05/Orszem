package hu.orszembejelento.backend.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Import

/**
 * Phase 13 brief §13/§58 - proves, at runtime rather than by grepping source, that a real
 * login attempt, a real password change and a real admin-issued temporary credential never
 * put a secret value into the application's own logs. Complements the Caddy-layer proof
 * already in place (`scripts/verify-caddy-header-redaction.sh`, which covers
 * `Authorization`/`X-Orszem-Report-Access` at the edge, not the application's own log
 * output).
 *
 * A [ListAppender] is attached directly to Logback's root logger for the duration of one
 * real HTTP flow against real PostgreSQL, with the root level temporarily raised to `DEBUG`
 * so this also covers every `log.debug(...)` call site (e.g.
 * `BearerTokenAuthenticationFilter`'s own), not only the `INFO`-level ones that would fire
 * under the application's normal logging configuration. Every value used is a synthetic
 * probe (brief §58) embedded in an otherwise-compliant password/credential shape - never a
 * real credential - and the assertion failure message never echoes the secret itself, so a
 * failing run cannot leak the very thing it is proving does not leak.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class SecretRedactionTest : AbstractAuthIntegrationTest() {

    @Test
    fun `a real login, password change and admin credential reset never write a secret into application logs`() {
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val originalLevel = rootLogger.level
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        rootLogger.addAppender(appender)
        rootLogger.level = Level.DEBUG

        val secrets: MutableList<String> = mutableListOf()
        try {
            val realPassword = "AUTH_SECRET_PROBE_PHASE13_REAL_PASSWORD_VALUE"
            val wrongPassword = "AUTH_SECRET_PROBE_PHASE13_WRONG_PASSWORD_VALUE"
            val newPassword = "AUTH_SECRET_PROBE_PHASE13_CHANGED_PASSWORD_VALUE"
            secrets += realPassword
            secrets += wrongPassword
            secrets += newPassword

            val user = givenUser(password = realPassword)

            // A failed login attempt with a marker-bearing wrong password.
            login(user.serviceId, wrongPassword)

            // A successful login - the issued tokens themselves must also never be logged.
            val credentials = loginSuccessfully(user.serviceId, realPassword)
            secrets += credentials.accessToken
            secrets += credentials.refreshToken

            // A real password change.
            val changeResponse = post(
                "/api/v1/service/account/change-password",
                """{"currentPassword":${objectMapper.writeValueAsString(realPassword)},""" +
                    """"newPassword":${objectMapper.writeValueAsString(newPassword)}}""",
                credentials.accessToken,
            )
            check(changeResponse.statusCode() == 200) { "fixture setup failed - password change did not succeed" }
            val freshTokens = credentialsFrom(changeResponse)
            secrets += freshTokens.accessToken
            secrets += freshTokens.refreshToken

            // A real admin-issued temporary credential (reset), which DOES legitimately
            // appear in the HTTP response to the admin (Phase 6's own one-time flow) - the
            // property under test is that it never additionally lands in a log line.
            val admin = createSuperAdmin.create()
            // ReportWorkflowTestSupport.completeInitialChange is not reachable from this
            // test's base class (AbstractAuthIntegrationTest directly) - inlined here as the
            // one endpoint call it would otherwise pull in a whole extra support chain for.
            val adminNewPassword = "AUTH_SECRET_PROBE_PHASE13_ADMIN_NEW_PASSWORD_VALUE"
            secrets += adminNewPassword
            val completeResponse = post(
                "/api/v1/service/auth/complete-password-change",
                """{"serviceId":"${admin.serviceId.value}","temporaryPassword":${
                    objectMapper.writeValueAsString(admin.temporaryCredential)
                },"newPassword":${objectMapper.writeValueAsString(adminNewPassword)}}""",
            )
            check(completeResponse.statusCode() == 200) { "fixture setup failed - completing the admin's initial password change did not succeed" }
            val adminBearer = loginSuccessfully(admin.serviceId, adminNewPassword).accessToken
            secrets += admin.temporaryCredential

            val target = givenUser()
            val resetResponse = post(
                "/api/v1/service/user-management/users/${target.serviceId.value}/password-reset",
                "",
                adminBearer,
            )
            check(resetResponse.statusCode() == 200) { "fixture setup failed - password reset did not succeed" }
            secrets += json(resetResponse).get("temporaryCredential").asText()

            rootLogger.detachAppender(appender)

            val loggedMessages = appender.list.map { event -> event.formattedMessage + (event.throwableProxy?.message ?: "") }

            secrets.distinct().forEach { secret ->
                assertFalse(loggedMessages.any { it.contains(secret) }) {
                    // Deliberately does not echo the secret value itself in the failure message.
                    "a secret value of length ${secret.length} leaked into application logs - see the test for which fixture produced it"
                }
            }
        } finally {
            rootLogger.detachAppender(appender)
            rootLogger.level = originalLevel
        }
    }
}
