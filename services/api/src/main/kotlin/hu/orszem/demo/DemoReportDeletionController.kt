package hu.orszem.demo

import hu.orszem.auth.domain.Capability
import hu.orszem.auth.web.currentActor
import hu.orszem.servicecase.application.DeleteReportUseCase
import hu.orszem.shared.error.ApiException
import hu.orszem.shared.error.ErrorCode
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Demo v1.1 — authenticated hard deletion of a report, for pilot/test-data cleanup.
 *
 * This is deliberately NOT part of [hu.orszem.servicecase.api.ServiceReportController]:
 * keeping it in its own bean is what makes the demo gate structural rather than a
 * runtime `if`. The route simply does not exist unless both conditions hold:
 *
 *  1. the active profile is `local` or `demo`, and
 *  2. `orszem.demo.deletion-enabled=true`.
 *
 * Outside that configuration there is no handler, so an authenticated caller gets
 * 404 and an anonymous one is already stopped by the security chain: every
 * `/api/v1/service/` path requires authentication, so the Public App (and any
 * other anonymous caller) can never
 * reach this, whatever the environment.
 *
 * This is not the production moderator/RBAC model and must not grow into one:
 * no roles, no hierarchy, no moderation workflow — one capability, one switch.
 */
@RestController
@RequestMapping("/api/v1/service")
@Profile("local", "demo")
@ConditionalOnProperty(prefix = "orszem.demo", name = ["deletion-enabled"], havingValue = "true")
class DemoReportDeletionController(
    private val deleteReport: DeleteReportUseCase,
) {

    @DeleteMapping("/reports/{reportId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable reportId: UUID) {
        val actor = currentActor()
            ?: throw ApiException(ErrorCode.UNAUTHORIZED, "Érvényes szolgálati bejelentkezés szükséges.")
        // Defence in depth: the capability is only minted when this same switch is
        // on, so a token issued by a differently-configured deployment cannot delete.
        if (Capability.REPORT_DELETE !in actor.capabilities) {
            throw ApiException(ErrorCode.UNAUTHORIZED, "Ehhez a művelethez nincs jogosultság.")
        }
        deleteReport.execute(reportId, actor.userId)
    }
}
