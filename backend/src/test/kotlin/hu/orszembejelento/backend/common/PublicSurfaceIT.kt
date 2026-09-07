package hu.orszembejelento.backend.common

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Import

/**
 * Verifies what the public surface exposes - and, more importantly, what it does not.
 * The health endpoint sits behind Caddy on the open internet, so a regression that starts
 * leaking component detail or the datasource URL must fail the build.
 *
 * Uses the JDK HTTP client rather than a framework test client: it exercises the real
 * socket and does not move between Spring Boot versions.
 */
@Import(AbstractPostgresIntegrationTest.Containers::class)
class PublicSurfaceIT : AbstractPostgresIntegrationTest() {

    @Value("\${local.server.port}")
    private var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun get(path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `health reports UP and nothing else`() {
        val response = get("/actuator/health")
        check(response.statusCode() == 200) { "expected 200, got ${response.statusCode()}" }

        val body = response.body()
        check(body.contains("\"status\":\"UP\"")) { "expected status UP, got: $body" }

        listOf("components", "diskSpace", "datasource", "jdbc:postgresql", "password", "validationQuery")
            .forEach { leak ->
                check(!body.contains(leak, ignoreCase = true)) {
                    "health response leaked '$leak': $body"
                }
            }
    }

    @Test
    fun `meta endpoint is reachable over http under the v1 prefix`() {
        val response = get("/api/v1/meta")
        check(response.statusCode() == 200) { "expected 200, got ${response.statusCode()}" }
        check(response.body().contains("\"apiVersion\":\"v1\"")) {
            "unexpected meta body: ${response.body()}"
        }
    }

    @Test
    fun `openapi document is generated and describes the meta operation`() {
        val response = get("/v3/api-docs")
        check(response.statusCode() == 200) { "expected 200, got ${response.statusCode()}" }
        check(response.body().contains("/api/v1/meta")) {
            "OpenAPI document does not describe /api/v1/meta"
        }
    }

    @Test
    fun `sensitive actuator endpoints are not reachable`() {
        // Two independent layers keep these closed, and either one is sufficient:
        //   * they are not in `management.endpoints.web.exposure.include`, so Actuator
        //     itself would answer 404;
        //   * Spring Security's default-deny rule now answers 401 first.
        // The assertion therefore accepts both, but never a 200 and never a body: what
        // matters is that no configuration, bean graph or heap dump is ever returned.
        listOf("env", "beans", "configprops", "mappings", "loggers", "heapdump", "threaddump")
            .forEach { endpoint ->
                val response = get("/actuator/$endpoint")
                check(response.statusCode() == 404 || response.statusCode() == 401) {
                    "/actuator/$endpoint must not be reachable, got ${response.statusCode()}"
                }
                listOf("jdbc:postgresql", "password", "ORSZEM_DB", "classpath", "beans")
                    .forEach { leak ->
                        check(!response.body().contains(leak, ignoreCase = true)) {
                            "/actuator/$endpoint leaked '$leak'"
                        }
                    }
            }
    }
}
