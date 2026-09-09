package hu.orszembejelento.backend.usermanagement

import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import hu.orszembejelento.backend.support.AbstractUserManagementIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/** Real-PostgreSQL creation tests (brief §49). */
@Import(AbstractAuthIntegrationTest.Containers::class)
class UserCreationIT : AbstractUserManagementIntegrationTest() {

    @Test
    fun `SUPER_ADMIN creates a SERVICE_USER with generated identity and temporary credential`() {
        val admin = createSuperAdmin.create()
        completeInitialChange(admin.serviceId, admin.temporaryCredential)
        val bearer = loginSuccessfully(admin.serviceId).accessToken

        val area = givenArea()
        val response = createUser(bearer, "SERVICE_USER", listOf(area.id), globalAreaAccess = false)
        check(response.statusCode() == 200) { response.body() }

        val body = json(response)
        val serviceId = body.get("serviceId").asText()
        check(hu.orszembejelento.backend.identity.domain.ServiceId.parseOrNull(serviceId) != null) { "generated service id must be well-formed: $serviceId" }
        check(body.get("role").asText() == "SERVICE_USER")
        check(body.get("mustChangePassword").asBoolean())
        val credential = body.get("temporaryCredential").asText()
        check(Regex("^([2-9A-HJ-NP-Z]{4}-){3}[2-9A-HJ-NP-Z]{4}$").matches(credential)) { "unexpected credential shape: $credential" }

        // Plaintext must not be persisted anywhere retrievable - only the hash exists.
        val stored = jdbc.sql("SELECT password_hash FROM users WHERE service_id = :id").param("id", serviceId).query(String::class.java).single()
        check(!stored.contains(credential.replace("-", ""))) { "the plaintext credential must never be stored" }

        val status = jdbc.sql("SELECT status FROM users WHERE service_id = :id").param("id", serviceId).query(String::class.java).single()
        check(status == "ACTIVE")

        val areaCount = jdbc.sql("SELECT COUNT(*) FROM user_service_areas usa JOIN users u ON u.id = usa.user_id WHERE u.service_id = :id")
            .param("id", serviceId).query(Int::class.java).single()
        check(areaCount == 1) { "the requested area must be granted" }

        val globalFlag = jdbc.sql("SELECT global_area_access FROM users WHERE service_id = :id").param("id", serviceId).query(Boolean::class.java).single()
        check(!globalFlag)

        check(auditEventTypes().contains("USER_CREATED"))
    }

    @Test
    fun `SUPER_ADMIN creates a MODERATOR`() {
        val admin = createSuperAdmin.create()
        completeInitialChange(admin.serviceId, admin.temporaryCredential)
        val bearer = loginSuccessfully(admin.serviceId).accessToken

        val response = createUser(bearer, "MODERATOR", emptyList(), globalAreaAccess = true)
        check(response.statusCode() == 200) { response.body() }
        check(json(response).get("role").asText() == "MODERATOR")

        val serviceId = json(response).get("serviceId").asText()
        val globalFlag = jdbc.sql("SELECT global_area_access FROM users WHERE service_id = :id").param("id", serviceId).query(Boolean::class.java).single()
        check(globalFlag)
    }

    @Test
    fun `a moderator creates a scoped SERVICE_USER`() {
        val area = givenArea()
        val moderator = givenUser(role = UserRole.MODERATOR)
        assignArea(moderator.id, area.id)
        val bearer = loginSuccessfully(moderator.serviceId).accessToken

        val response = createUser(bearer, "SERVICE_USER", listOf(area.id), globalAreaAccess = false)
        check(response.statusCode() == 200) { response.body() }

        val serviceId = json(response).get("serviceId").asText()
        val areaCount = jdbc.sql("SELECT COUNT(*) FROM user_service_areas usa JOIN users u ON u.id = usa.user_id WHERE u.service_id = :id")
            .param("id", serviceId).query(Int::class.java).single()
        check(areaCount == 1)
    }

    @Test
    fun `a moderator cannot create an unassigned user - at least one area is required`() {
        val moderator = givenUser(role = UserRole.MODERATOR)
        val bearer = loginSuccessfully(moderator.serviceId).accessToken

        val response = createUser(bearer, "SERVICE_USER", emptyList(), globalAreaAccess = false)
        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "USER_REQUIRES_SERVICE_AREA")
    }

    @Test
    fun `a moderator cannot grant an area outside their own scope`() {
        val ownArea = givenArea()
        val outsideArea = givenArea()
        val moderator = givenUser(role = UserRole.MODERATOR)
        assignArea(moderator.id, ownArea.id)
        val bearer = loginSuccessfully(moderator.serviceId).accessToken

        val response = createUser(bearer, "SERVICE_USER", listOf(outsideArea.id), globalAreaAccess = false)
        check(response.statusCode() == 400) { response.body() }
        check(errorCode(response) == "AREA_NOT_ASSIGNABLE")
    }

    @Test
    fun `a moderator cannot create a globally-accessible user`() {
        val area = givenArea()
        val moderator = givenUser(role = UserRole.MODERATOR)
        assignArea(moderator.id, area.id)
        val bearer = loginSuccessfully(moderator.serviceId).accessToken

        val response = createUser(bearer, "SERVICE_USER", listOf(area.id), globalAreaAccess = true)
        check(response.statusCode() == 403) { response.body() }
        check(errorCode(response) == "USER_MANAGEMENT_FORBIDDEN")
    }

    @Test
    fun `a moderator cannot create another MODERATOR`() {
        val area = givenArea()
        val moderator = givenUser(role = UserRole.MODERATOR)
        assignArea(moderator.id, area.id)
        val bearer = loginSuccessfully(moderator.serviceId).accessToken

        val response = createUser(bearer, "MODERATOR", listOf(area.id), globalAreaAccess = false)
        check(response.statusCode() == 403) { response.body() }
        check(errorCode(response) == "USER_MANAGEMENT_FORBIDDEN")
    }

    @Test
    fun `no HTTP path creates a SUPER_ADMIN, not even for a SUPER_ADMIN actor`() {
        val admin = createSuperAdmin.create()
        completeInitialChange(admin.serviceId, admin.temporaryCredential)
        val bearer = loginSuccessfully(admin.serviceId).accessToken

        val response = createUser(bearer, "SUPER_ADMIN", emptyList(), globalAreaAccess = false)
        check(response.statusCode() == 403) { response.body() }
        check(errorCode(response) == "USER_MANAGEMENT_FORBIDDEN")
    }

    @Test
    fun `a SERVICE_USER actor cannot create anyone`() {
        val user = givenUser()
        val bearer = loginSuccessfully(user.serviceId).accessToken

        val response = createUser(bearer, "SERVICE_USER", emptyList(), globalAreaAccess = false)
        check(response.statusCode() == 403) { response.body() }
        check(errorCode(response) == "USER_MANAGEMENT_FORBIDDEN")
    }
}
