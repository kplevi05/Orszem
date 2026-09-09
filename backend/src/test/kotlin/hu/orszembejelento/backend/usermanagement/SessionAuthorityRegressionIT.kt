package hu.orszembejelento.backend.usermanagement

import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import hu.orszembejelento.backend.support.AbstractUserManagementIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Proves the opaque, server-side-only session design (Phase 2) still delivers what Phase 6
 * depends on: a role or scope change takes effect on an *already-issued, never-revoked*
 * session's very next request, with no token to wait out (brief §23/§57).
 *
 * Every test here calls with the SAME bearer token obtained before the mutation - the point
 * is specifically that nothing about the token itself changes; only server-side state does.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class SessionAuthorityRegressionIT : AbstractUserManagementIntegrationTest() {

    private fun adminBearer(): String {
        val admin = createSuperAdmin.create()
        completeInitialChange(admin.serviceId, admin.temporaryCredential)
        return loginSuccessfully(admin.serviceId).accessToken
    }

    @Test
    fun `a role demotion is enforced on the moderator's existing session's very next request`() {
        val moderator = givenUser(role = UserRole.MODERATOR)
        val staleBearer = loginSuccessfully(moderator.serviceId).accessToken
        // Confirm the session actually has moderator authority before the change.
        check(listUsers(staleBearer).statusCode() == 200)

        val adminBearerToken = adminBearer()
        check(changeRole(adminBearerToken, moderator.serviceId.value, "SERVICE_USER").statusCode() == 200)

        // Same token, not refreshed, not re-issued, not revoked - the session row itself is
        // still perfectly valid. Only the role stored against its user id changed.
        val response = listUsers(staleBearer)
        check(response.statusCode() == 403) { "a demoted session must lose user-management authority immediately: ${response.body()}" }
    }

    @Test
    fun `an area revoke removes a scoped target from the moderator's existing session's next request`() {
        val area = givenArea()
        val moderator = givenUser(role = UserRole.MODERATOR)
        assignArea(moderator.id, area.id)
        val target = givenUser()
        assignArea(target.id, area.id)

        val staleBearer = loginSuccessfully(moderator.serviceId).accessToken
        check(userDetail(staleBearer, target.serviceId.value).statusCode() == 200) { "must be visible before the revoke" }

        val adminBearerToken = adminBearer()
        check(revokeArea(adminBearerToken, moderator.serviceId.value, area.id).statusCode() == 200)

        val response = userDetail(staleBearer, target.serviceId.value)
        check(response.statusCode() == 404) { "the same stale session must lose visibility immediately: ${response.body()}" }
        check(errorCode(response) == "USER_NOT_FOUND")
    }

    @Test
    fun `a global-access revoke narrows the moderator's existing session's next request`() {
        val moderator = givenUser(role = UserRole.MODERATOR)
        val unassignedUser = givenUser() // visible to a GLOBAL moderator only

        val adminBearerToken = adminBearer()
        check(grantGlobalAccess(adminBearerToken, moderator.serviceId.value).statusCode() == 200)

        val staleBearer = loginSuccessfully(moderator.serviceId).accessToken
        check(userDetail(staleBearer, unassignedUser.serviceId.value).statusCode() == 200) { "must be visible while global" }

        check(revokeGlobalAccess(adminBearerToken, moderator.serviceId.value).statusCode() == 200)

        val response = userDetail(staleBearer, unassignedUser.serviceId.value)
        check(response.statusCode() == 404) { "the same stale session must lose the global view immediately: ${response.body()}" }
    }

    @Test
    fun `an area grant extends the moderator's existing session's next request without any new login`() {
        val newArea = givenArea()
        val moderator = givenUser(role = UserRole.MODERATOR)
        val target = givenUser()
        assignArea(target.id, newArea.id)

        val staleBearer = loginSuccessfully(moderator.serviceId).accessToken
        check(userDetail(staleBearer, target.serviceId.value).statusCode() == 404) { "not yet in scope" }

        val adminBearerToken = adminBearer()
        check(grantArea(adminBearerToken, moderator.serviceId.value, newArea.id).statusCode() == 200)

        val response = userDetail(staleBearer, target.serviceId.value)
        check(response.statusCode() == 200) { "the same stale session must gain visibility immediately: ${response.body()}" }
    }
}
