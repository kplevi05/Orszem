package hu.orszembejelento.backend.usermanagement

import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import hu.orszembejelento.backend.support.AbstractUserManagementIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/** Real-PostgreSQL role-change tests (brief §52). */
@Import(AbstractAuthIntegrationTest.Containers::class)
class RoleChangeIT : AbstractUserManagementIntegrationTest() {

    private fun adminBearer(): String {
        val admin = createSuperAdmin.create()
        completeInitialChange(admin.serviceId, admin.temporaryCredential)
        return loginSuccessfully(admin.serviceId).accessToken
    }

    @Test
    fun `SUPER_ADMIN promotes SERVICE_USER to MODERATOR, preserving areas, global flag and password`() {
        val area = givenArea()
        val target = givenUser()
        assignArea(target.id, area.id)
        setGlobalAccess(target.id, false)
        val bearer = adminBearer()

        val response = changeRole(bearer, target.serviceId.value, "MODERATOR")
        check(response.statusCode() == 200) { response.body() }
        check(json(response).get("role").asText() == "MODERATOR")

        check(jdbc.sql("SELECT role FROM users WHERE id = :id").param("id", target.id).query(String::class.java).single() == "MODERATOR")
        check(serviceAreas.assignedAreaIds(target.id) == setOf(area.id)) { "area assignments must be preserved" }
        check(login(target.serviceId, STRONG_PASSWORD).statusCode() == 200) { "the password must be preserved" }
    }

    @Test
    fun `SUPER_ADMIN demotes MODERATOR to SERVICE_USER`() {
        val target = givenUser(role = UserRole.MODERATOR)
        val bearer = adminBearer()

        val response = changeRole(bearer, target.serviceId.value, "SERVICE_USER")
        check(response.statusCode() == 200) { response.body() }
        check(jdbc.sql("SELECT role FROM users WHERE id = :id").param("id", target.id).query(String::class.java).single() == "SERVICE_USER")
    }

    @Test
    fun `role change is rejected when the target is a SUPER_ADMIN`() {
        val victimAdmin = createSuperAdmin.create()
        val bearer = adminBearer()

        val response = changeRole(bearer, victimAdmin.serviceId.value, "MODERATOR")
        check(response.statusCode() == 400) { response.body() }
        check(errorCode(response) == "INVALID_ROLE_TRANSITION")
    }

    @Test
    fun `role change is rejected when SUPER_ADMIN is the requested role`() {
        val target = givenUser()
        val bearer = adminBearer()

        val response = changeRole(bearer, target.serviceId.value, "SUPER_ADMIN")
        check(response.statusCode() == 400) { response.body() }
        check(errorCode(response) == "INVALID_ROLE_TRANSITION")
    }

    @Test
    fun `a moderator actor is rejected outright, regardless of target`() {
        val moderator = givenUser(role = UserRole.MODERATOR)
        val target = givenUser()
        val bearer = loginSuccessfully(moderator.serviceId).accessToken

        val response = changeRole(bearer, target.serviceId.value, "MODERATOR")
        check(response.statusCode() == 403) { response.body() }
        check(errorCode(response) == "USER_MANAGEMENT_FORBIDDEN") { "expected a forbidden rejection, got ${errorCode(response)}" }
    }

    @Test
    fun `a SERVICE_USER actor is rejected outright`() {
        val actor = givenUser()
        val target = givenUser()
        val bearer = loginSuccessfully(actor.serviceId).accessToken

        val response = changeRole(bearer, target.serviceId.value, "MODERATOR")
        check(response.statusCode() == 403) { response.body() }
    }

    @Test
    fun `AreaScopePolicy reflects a role change on the very next request`() {
        val target = givenUser() // SERVICE_USER, no UNCLASSIFIED access
        setGlobalAccess(target.id, true)
        val bearer = adminBearer()

        check(changeRole(bearer, target.serviceId.value, "MODERATOR").statusCode() == 200)

        // The freshly-promoted user's OWN session (if they log in now) reflects MODERATOR
        // immediately - no token to expire, since role/scope are read from the database on
        // every request (§23).
        val session = loginSuccessfully(target.serviceId)
        val me = get("/api/v1/service/account/me", session.accessToken)
        check(json(me).get("role").asText() == "MODERATOR")
    }
}
