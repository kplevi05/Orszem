package hu.orszembejelento.backend.usermanagement

import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.domain.UserStatus
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import hu.orszembejelento.backend.support.AbstractUserManagementIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/** Real-PostgreSQL administrative password-reset tests (brief §50). */
@Import(AbstractAuthIntegrationTest.Containers::class)
class PasswordResetIT : AbstractUserManagementIntegrationTest() {

    private fun adminBearer(): String {
        val admin = createSuperAdmin.create()
        completeInitialChange(admin.serviceId, admin.temporaryCredential)
        return loginSuccessfully(admin.serviceId).accessToken
    }

    @Test
    fun `SUPER_ADMIN resets a SERVICE_USER's password`() {
        val target = givenUser()
        val oldSession = loginSuccessfully(target.serviceId)
        val bearer = adminBearer()

        val response = resetPassword(bearer, target.serviceId.value)
        check(response.statusCode() == 200) { response.body() }
        check(json(response).get("serviceId").asText() == target.serviceId.value)
        val credential = temporaryCredentialOf(response)
        check(credential.isNotBlank())

        // Old password dead, old session dead, immediately.
        check(login(target.serviceId, STRONG_PASSWORD).statusCode() == 401)
        check(get("/api/v1/service/account/me", oldSession.accessToken).statusCode() == 401)
        check(activeSessionCount(target.id) == 0)

        check(jdbc.sql("SELECT must_change_password FROM users WHERE id = :id").param("id", target.id).query(Boolean::class.java).single())

        // The new credential completes the initial-change flow like any temporary credential.
        val completed = completeInitialChange(target.serviceId, credential, ANOTHER_STRONG_PASSWORD)
        check(completed.statusCode() == 200) { completed.body() }
    }

    @Test
    fun `SUPER_ADMIN resets a MODERATOR's password`() {
        val moderator = givenUser(role = UserRole.MODERATOR)
        val bearer = adminBearer()

        val response = resetPassword(bearer, moderator.serviceId.value)
        check(response.statusCode() == 200) { response.body() }
    }

    @Test
    fun `a moderator resets a manageable SERVICE_USER's password`() {
        val area = givenArea()
        val moderator = givenUser(role = UserRole.MODERATOR)
        assignArea(moderator.id, area.id)
        val target = givenUser()
        assignArea(target.id, area.id)
        val bearer = loginSuccessfully(moderator.serviceId).accessToken

        val response = resetPassword(bearer, target.serviceId.value)
        check(response.statusCode() == 200) { response.body() }
        check(activeSessionCount(target.id) == 0)
    }

    @Test
    fun `a moderator cannot reset another moderator's password`() {
        val area = givenArea()
        val moderator = givenUser(role = UserRole.MODERATOR)
        assignArea(moderator.id, area.id)
        val peer = givenUser(role = UserRole.MODERATOR)
        assignArea(peer.id, area.id)
        val bearer = loginSuccessfully(moderator.serviceId).accessToken

        val response = resetPassword(bearer, peer.serviceId.value)
        check(response.statusCode() == 403) { response.body() }
        check(errorCode(response) == "USER_NOT_MANAGEABLE")
    }

    @Test
    fun `a moderator cannot reset a global SERVICE_USER's password`() {
        val area = givenArea()
        val moderator = givenUser(role = UserRole.MODERATOR)
        assignArea(moderator.id, area.id)
        val globalUser = givenUser()
        setGlobalAccess(globalUser.id, true)
        val bearer = loginSuccessfully(moderator.serviceId).accessToken

        val response = resetPassword(bearer, globalUser.serviceId.value)
        check(response.statusCode() == 403) { response.body() }
        check(errorCode(response) == "USER_NOT_MANAGEABLE")
    }

    @Test
    fun `reset of a DEACTIVATED account works but does not reactivate it`() {
        val target = givenUser(status = UserStatus.DEACTIVATED)
        val bearer = adminBearer()

        val response = resetPassword(bearer, target.serviceId.value)
        check(response.statusCode() == 200) { response.body() }

        val status = jdbc.sql("SELECT status FROM users WHERE id = :id").param("id", target.id).query(String::class.java).single()
        check(status == "DEACTIVATED") { "reset must not reactivate the account" }

        val credential = temporaryCredentialOf(response)
        // Still cannot log in - the account is deactivated, only its credential changed.
        check(completeInitialChange(target.serviceId, credential).statusCode() != 200)
    }

    @Test
    fun `there is no HTTP path to reset a SUPER_ADMIN's password`() {
        val victimAdmin = createSuperAdmin.create()
        val actingBearer = adminBearer()

        val response = resetPassword(actingBearer, victimAdmin.serviceId.value)
        check(response.statusCode() == 403) { response.body() }
        check(errorCode(response) == "USER_NOT_MANAGEABLE")
    }

    @Test
    fun `no credential ever appears in audit metadata`() {
        val target = givenUser()
        val bearer = adminBearer()

        val response = resetPassword(bearer, target.serviceId.value)
        val credential = temporaryCredentialOf(response)

        val metadata = jdbc.sql("SELECT metadata::text FROM audit_events WHERE event_type = 'USER_PASSWORD_RESET'")
            .query(String::class.java).list().filterNotNull()
        check(metadata.isNotEmpty())
        metadata.forEach { row -> check(!row.contains(credential.replace("-", ""))) { "credential leaked into audit metadata: $row" } }
    }
}
