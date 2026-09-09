package hu.orszembejelento.backend.usermanagement

import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import hu.orszembejelento.backend.support.AbstractUserManagementIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/** Real-PostgreSQL deactivate/reactivate tests (brief §51). */
@Import(AbstractAuthIntegrationTest.Containers::class)
class UserStatusIT : AbstractUserManagementIntegrationTest() {

    private fun adminBearer(): String {
        val admin = createSuperAdmin.create()
        completeInitialChange(admin.serviceId, admin.temporaryCredential)
        return loginSuccessfully(admin.serviceId).accessToken
    }

    @Test
    fun `deactivate flips ACTIVE to DEACTIVATED and revokes every session`() {
        val target = givenUser()
        val session = loginSuccessfully(target.serviceId)
        val bearer = adminBearer()

        val response = deactivateUser(bearer, target.serviceId.value)
        check(response.statusCode() == 200) { response.body() }
        check(json(response).get("status").asText() == "DEACTIVATED")

        check(activeSessionCount(target.id) == 0)
        check(get("/api/v1/service/account/me", session.accessToken).statusCode() == 401)
        check(login(target.serviceId, STRONG_PASSWORD).statusCode() == 401) { "a generic login failure, existence-safe" }
    }

    @Test
    fun `repeated deactivate is idempotent`() {
        val target = givenUser()
        val bearer = adminBearer()

        check(deactivateUser(bearer, target.serviceId.value).statusCode() == 200)
        val second = deactivateUser(bearer, target.serviceId.value)
        check(second.statusCode() == 200)
        check(json(second).get("status").asText() == "DEACTIVATED")
    }

    @Test
    fun `reactivate flips DEACTIVATED to ACTIVE, creates no session and preserves credential state`() {
        val target = givenUser(status = hu.orszembejelento.backend.identity.domain.UserStatus.DEACTIVATED, mustChangePassword = true)
        val bearer = adminBearer()

        val response = reactivateUser(bearer, target.serviceId.value)
        check(response.statusCode() == 200) { response.body() }
        check(json(response).get("status").asText() == "ACTIVE")

        check(activeSessionCount(target.id) == 0) { "reactivation must not create a session" }
        check(jdbc.sql("SELECT must_change_password FROM users WHERE id = :id").param("id", target.id).query(Boolean::class.java).single()) {
            "mustChangePassword must be preserved, not cleared"
        }
    }

    @Test
    fun `reactivate of a normal-password account leaves that password usable again`() {
        val target = givenUser(status = hu.orszembejelento.backend.identity.domain.UserStatus.DEACTIVATED)
        val bearer = adminBearer()

        check(reactivateUser(bearer, target.serviceId.value).statusCode() == 200)
        check(login(target.serviceId, STRONG_PASSWORD).statusCode() == 200) { "the same password becomes usable again" }
    }

    @Test
    fun `repeated reactivate is idempotent`() {
        val target = givenUser()
        val bearer = adminBearer()

        val first = reactivateUser(bearer, target.serviceId.value)
        check(first.statusCode() == 200)
        val second = reactivateUser(bearer, target.serviceId.value)
        check(second.statusCode() == 200)
    }

    @Test
    fun `a moderator can deactivate and reactivate only a manageable SERVICE_USER`() {
        val area = givenArea()
        val moderator = givenUser(role = UserRole.MODERATOR)
        assignArea(moderator.id, area.id)
        val target = givenUser()
        assignArea(target.id, area.id)
        val bearer = loginSuccessfully(moderator.serviceId).accessToken

        check(deactivateUser(bearer, target.serviceId.value).statusCode() == 200)
        check(reactivateUser(bearer, target.serviceId.value).statusCode() == 200)
    }

    @Test
    fun `a moderator cannot deactivate an out-of-scope user`() {
        val moderator = givenUser(role = UserRole.MODERATOR)
        val target = givenUser()
        val bearer = loginSuccessfully(moderator.serviceId).accessToken

        val response = deactivateUser(bearer, target.serviceId.value)
        check(response.statusCode() == 404) { response.body() }
        check(errorCode(response) == "USER_NOT_FOUND") { "out-of-scope must be indistinguishable from nonexistent" }
    }

    @Test
    fun `a moderator cannot reactivate a peer moderator`() {
        val area = givenArea()
        val moderator = givenUser(role = UserRole.MODERATOR)
        assignArea(moderator.id, area.id)
        val peer = givenUser(role = UserRole.MODERATOR)
        assignArea(peer.id, area.id)
        val bearer = loginSuccessfully(moderator.serviceId).accessToken

        val response = reactivateUser(bearer, peer.serviceId.value)
        check(response.statusCode() == 403) { response.body() }
        check(errorCode(response) == "USER_NOT_MANAGEABLE") { "a visible peer is a distinct rejection from an invisible target" }
    }
}
