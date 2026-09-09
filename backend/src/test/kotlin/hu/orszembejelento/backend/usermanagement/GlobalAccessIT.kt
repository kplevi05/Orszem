package hu.orszembejelento.backend.usermanagement

import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.scope.domain.AreaScopePolicy
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import hu.orszembejelento.backend.support.AbstractUserManagementIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/** Real-PostgreSQL global-area-access tests (brief §53). */
@Import(AbstractAuthIntegrationTest.Containers::class)
class GlobalAccessIT : AbstractUserManagementIntegrationTest() {

    private val areaScopePolicy = AreaScopePolicy()

    private fun adminBearer(): String {
        val admin = createSuperAdmin.create()
        completeInitialChange(admin.serviceId, admin.temporaryCredential)
        return loginSuccessfully(admin.serviceId).accessToken
    }

    @Test
    fun `SUPER_ADMIN grants and revokes global access, both idempotent`() {
        val target = givenUser()
        val bearer = adminBearer()

        val granted = grantGlobalAccess(bearer, target.serviceId.value)
        check(granted.statusCode() == 200) { granted.body() }
        check(json(granted).get("globalAreaAccess").asBoolean())
        val grantedAgain = grantGlobalAccess(bearer, target.serviceId.value)
        check(grantedAgain.statusCode() == 200)

        val revoked = revokeGlobalAccess(bearer, target.serviceId.value)
        check(revoked.statusCode() == 200) { revoked.body() }
        check(!json(revoked).get("globalAreaAccess").asBoolean())
        val revokedAgain = revokeGlobalAccess(bearer, target.serviceId.value)
        check(revokedAgain.statusCode() == 200)

        // Exactly one grant and one revoke audit row - the repeats must not pad the trail.
        val grants = jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE event_type = 'USER_GLOBAL_ACCESS_GRANTED'").query(Int::class.java).single()
        val revokes = jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE event_type = 'USER_GLOBAL_ACCESS_REVOKED'").query(Int::class.java).single()
        check(grants == 1) { "expected exactly one grant audit row, found $grants" }
        check(revokes == 1) { "expected exactly one revoke audit row, found $revokes" }
    }

    @Test
    fun `granting global access preserves normal area assignments, and revoking makes them effective again`() {
        val area = givenArea()
        val target = givenUser()
        assignArea(target.id, area.id)
        val bearer = adminBearer()

        check(grantGlobalAccess(bearer, target.serviceId.value).statusCode() == 200)
        check(serviceAreas.assignedAreaIds(target.id) == setOf(area.id)) { "the ordinary assignment must not be cleared by granting global access" }

        check(revokeGlobalAccess(bearer, target.serviceId.value).statusCode() == 200)
        val actor = serviceAreas.loadAreaActor(target.id)!!
        check(areaScopePolicy.canAccessArea(actor, hu.orszembejelento.backend.scope.domain.AreaSnapshot(area.id, active = true))) {
            "after revoking global access, the ordinary assignment must be authoritative again"
        }
    }

    @Test
    fun `a global SERVICE_USER reaches every active area but still cannot view UNCLASSIFIED`() {
        val target = givenUser()
        val bearer = adminBearer()
        check(grantGlobalAccess(bearer, target.serviceId.value).statusCode() == 200)

        val actor = serviceAreas.loadAreaActor(target.id)!!
        check(areaScopePolicy.hasGlobalAreaAccess(actor))
        check(!areaScopePolicy.canViewUnclassified(actor)) { "breadth of ordinary work is not the same as UNCLASSIFIED triage" }
    }

    @Test
    fun `a global MODERATOR reaches every active area and CAN view UNCLASSIFIED`() {
        val moderator = givenUser(role = UserRole.MODERATOR)
        val bearer = adminBearer()
        check(grantGlobalAccess(bearer, moderator.serviceId.value).statusCode() == 200)

        val actor = serviceAreas.loadAreaActor(moderator.id)!!
        check(areaScopePolicy.hasGlobalAreaAccess(actor))
        check(areaScopePolicy.canViewUnclassified(actor))
    }

    @Test
    fun `only SUPER_ADMIN may change global access - a moderator is rejected`() {
        val moderator = givenUser(role = UserRole.MODERATOR)
        val target = givenUser()
        val bearer = loginSuccessfully(moderator.serviceId).accessToken

        val response = grantGlobalAccess(bearer, target.serviceId.value)
        check(response.statusCode() == 403) { response.body() }
        check(errorCode(response) == "GLOBAL_ACCESS_NOT_ALLOWED")
    }

    @Test
    fun `a SUPER_ADMIN target is rejected for global-access changes`() {
        val victimAdmin = createSuperAdmin.create()
        val bearer = adminBearer()

        val response = grantGlobalAccess(bearer, victimAdmin.serviceId.value)
        check(response.statusCode() == 403) { response.body() }
        check(errorCode(response) == "GLOBAL_ACCESS_NOT_ALLOWED")
    }
}
