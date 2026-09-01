package hu.orszem.demo

import hu.orszem.shared.config.OrszemProperties
import hu.orszem.shared.error.ApiException
import hu.orszem.shared.error.ErrorCode
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Demo-only reset endpoint. Exists only in `local`/`demo` with seed enabled, and
 * requires the `X-Demo-Reset-Token` header to match `orszem.demo.reset-token`.
 * Not part of the public OpenAPI contract and never present in production.
 */
@RestController
@RequestMapping("/api/v1/admin/demo")
@Profile("local", "demo")
@ConditionalOnProperty(prefix = "orszem.demo", name = ["seed-enabled"], havingValue = "true")
class DemoAdminController(
    private val demoDataService: DemoDataService,
    private val properties: OrszemProperties,
) {

    @PostMapping("/reset")
    fun reset(@RequestHeader(name = "X-Demo-Reset-Token", required = false) token: String?): ResponseEntity<Map<String, String>> {
        val expected = properties.demo.resetToken
        if (expected.isBlank() || token != expected) {
            throw ApiException(ErrorCode.UNAUTHORIZED, "Érvénytelen demo reset token.")
        }
        demoDataService.reset()
        return ResponseEntity.ok(mapOf("status" to "RESET_OK"))
    }
}
