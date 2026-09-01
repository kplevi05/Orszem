package hu.orszem.support

import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity

/** Thin helper: logs in once and issues authenticated Service API calls. */
class ServiceApiClient(
    private val rest: TestRestTemplate,
    username: String = "demo.service",
    password: String = "OrszemDemo!2026",
) {
    val token: String = run {
        @Suppress("UNCHECKED_CAST")
        val body = rest.postForObject(
            "/api/v1/service/auth/login",
            mapOf("username" to username, "password" to password),
            Map::class.java,
        ) as Map<String, Any>
        body["accessToken"] as String
    }

    private fun headers() = HttpHeaders().apply { setBearerAuth(token) }

    fun <T> get(path: String, type: Class<T>): ResponseEntity<T> =
        rest.exchange(path, HttpMethod.GET, HttpEntity<Void>(headers()), type)

    fun <T> get(path: String, type: ParameterizedTypeReference<T>): ResponseEntity<T> =
        rest.exchange(path, HttpMethod.GET, HttpEntity<Void>(headers()), type)

    fun <T> post(path: String, type: Class<T>): ResponseEntity<T> =
        rest.exchange(path, HttpMethod.POST, HttpEntity<Void>(headers()), type)
}
