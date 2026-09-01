package hu.orszem

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import hu.orszem.auth.api.ServiceLoginResponse
import hu.orszem.support.AbstractDemoIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod

/**
 * AT-044 — passwords, access tokens and Authorization headers must not appear
 * in the normal server log.
 */
class LogSanitizationIT @Autowired constructor(
    private val rest: TestRestTemplate,
) : AbstractDemoIntegrationTest() {

    private val password = "OrszemDemo!2026"

    @Test
    fun `no password, token or Authorization header is written to the log`() {
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        rootLogger.addAppender(appender)
        try {
            val token = rest.postForObject(
                "/api/v1/service/auth/login",
                mapOf("username" to "demo.service", "password" to password),
                ServiceLoginResponse::class.java,
            ).accessToken

            // wrong password (audited failure path)
            rest.postForEntity(
                "/api/v1/service/auth/login",
                mapOf("username" to "demo.service", "password" to "totally-wrong-secret"),
                String::class.java,
            )

            // an authenticated call
            val headers = HttpHeaders().apply { setBearerAuth(token) }
            rest.exchange("/api/v1/service/me", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)

            val logText = appender.list.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }
            assertThat(logText).doesNotContain(password)
            assertThat(logText).doesNotContain("totally-wrong-secret")
            assertThat(logText).doesNotContain(token)
            assertThat(logText.lowercase()).doesNotContain("authorization: bearer")
        } finally {
            rootLogger.detachAppender(appender)
        }
    }
}
