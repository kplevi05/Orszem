package hu.orszembejelento.backend.common

import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Phase 16: an unauthenticated caller must not be able to turn a plain client mistake into a
 * `500 INTERNAL_ERROR` plus an ERROR-level stack trace in the server log.
 *
 * Found in the release-candidate walk: a required query parameter left out, a POST with no
 * (or a non-JSON) Content-Type, and an `Accept` the API cannot satisfy all fell through
 * [hu.orszembejelento.backend.common.web.ApiExceptionHandler] to its catch-all. Each is a
 * request the *client* got wrong, so each must be a 4xx with the stable, deliberately vague
 * error shape - and the last must still be answered as JSON.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class FrameworkExceptionMappingIT : AbstractAuthIntegrationTest() {

    private val client = HttpClient.newHttpClient()

    private fun send(method: String, path: String, headers: Map<String, String> = emptyMap(), body: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
        headers.forEach { (k, v) -> builder.header(k, v) }
        builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun assertStableErrorShape(response: HttpResponse<String>) {
        assertEquals(setOf("code", "message", "correlationId"), json(response).propertyNames().toSet()) {
            "unexpected error body: ${response.body()}"
        }
        assertFalse(response.body().contains("Exception")) { "error body leaks an exception name: ${response.body()}" }
    }

    @Test
    fun `a missing required query parameter is a 400, not a 500`() {
        val response = send("GET", "/api/v1/public/reference/settlements")

        assertEquals(400, response.statusCode())
        assertEquals("VALIDATION_ERROR", errorCode(response))
        assertStableErrorShape(response)
    }

    @Test
    fun `a POST with a non-JSON content type is a 415, not a 500`() {
        val response = send(
            "POST", "/api/v1/service/auth/login",
            headers = mapOf("Content-Type" to "text/plain"), body = "x",
        )

        assertEquals(415, response.statusCode())
        assertEquals("VALIDATION_ERROR", errorCode(response))
        assertStableErrorShape(response)
    }

    @Test
    fun `a POST with no content type at all is a 415, not a 500`() {
        // java.net.http sends no Content-Type unless asked to, which is exactly the case.
        val response = send("POST", "/api/v1/service/auth/login", body = "{}")

        assertEquals(415, response.statusCode())
        assertStableErrorShape(response)
    }

    @Test
    fun `an Accept the API cannot satisfy is a 406 answered as JSON, not a 500 or a redirect into the error dispatch`() {
        val response = send("GET", "/api/v1/meta", headers = mapOf("Accept" to "application/xml"))

        assertEquals(406, response.statusCode())
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse("").substringBefore(';'))
        assertEquals("VALIDATION_ERROR", errorCode(response))
        assertStableErrorShape(response)
    }

    @Test
    fun `an error body stays JSON even when the caller only accepts something else`() {
        val response = send("GET", "/api/v1/public/reference/settlements", headers = mapOf("Accept" to "text/html"))

        assertEquals(400, response.statusCode())
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse("").substringBefore(';'))
        assertStableErrorShape(response)
    }

    @Test
    fun `well-formed requests are unaffected`() {
        assertEquals(200, send("GET", "/api/v1/meta").statusCode())
        assertEquals(200, send("GET", "/api/v1/meta", headers = mapOf("Accept" to "application/json")).statusCode())
        // Present and valid: a normal answer (200 with a dataset, 503 before the first import),
        // never one of the client-error mappings above and never a 500.
        val status = send("GET", "/api/v1/public/reference/settlements?query=ab").statusCode()
        assertTrue(status == 200 || status == 503) { "unexpected status $status" }
    }
}
