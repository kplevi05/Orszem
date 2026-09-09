package hu.orszembejelento.backend.usermanagement

import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.reference.domain.ServiceAreaStatus
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import hu.orszembejelento.backend.support.AbstractUserManagementIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/** Real-PostgreSQL area grant/revoke tests (brief §54, non-concurrency part). */
@Import(AbstractAuthIntegrationTest.Containers::class)
class AreaGrantRevokeIT : AbstractUserManagementIntegrationTest() {

    private fun adminBearer(): String {
        val admin = createSuperAdmin.create()
        completeInitialChange(admin.serviceId, admin.temporaryCredential)
        return loginSuccessfully(admin.serviceId).accessToken
    }

    @Test
    fun `a valid grant succeeds and is reflected in the response and the database`() {
        val area = givenArea()
        val target = givenUser()
        val bearer = adminBearer()

        val response = grantArea(bearer, target.serviceId.value, area.id)
        check(response.statusCode() == 200) { response.body() }
        check(serviceAreas.assignedAreaIds(target.id) == setOf(area.id))
        check(auditEventTypes().contains("USER_AREA_GRANTED"))
    }

    @Test
    fun `a duplicate grant is idempotent and creates no duplicate row or audit spam`() {
        val area = givenArea()
        val target = givenUser()
        val bearer = adminBearer()

        check(grantArea(bearer, target.serviceId.value, area.id).statusCode() == 200)
        check(grantArea(bearer, target.serviceId.value, area.id).statusCode() == 200)

        val rowCount = jdbc.sql("SELECT COUNT(*) FROM user_service_areas WHERE user_id = :id AND service_area_id = :area")
            .param("id", target.id).param("area", area.id).query(Int::class.java).single()
        check(rowCount == 1)

        val auditCount = jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE event_type = 'USER_AREA_GRANTED'").query(Int::class.java).single()
        check(auditCount == 1) { "a true no-op grant must not be audited a second time" }
    }

    @Test
    fun `a valid revoke removes the assignment`() {
        val area = givenArea()
        val target = givenUser()
        assignArea(target.id, area.id)
        val bearer = adminBearer()

        val response = revokeArea(bearer, target.serviceId.value, area.id)
        check(response.statusCode() == 200) { response.body() }
        check(serviceAreas.assignedAreaIds(target.id).isEmpty())
        check(auditEventTypes().contains("USER_AREA_REVOKED"))
    }

    @Test
    fun `granting an inactive area is rejected`() {
        val inactive = givenArea(status = ServiceAreaStatus.INACTIVE)
        val target = givenUser()
        val bearer = adminBearer()

        val response = grantArea(bearer, target.serviceId.value, inactive.id)
        check(response.statusCode() == 400) { response.body() }
        check(errorCode(response) == "AREA_NOT_ASSIGNABLE")
    }

    @Test
    fun `a moderator granting an area outside their own scope is rejected`() {
        val ownArea = givenArea()
        val outsideArea = givenArea()
        val moderator = givenUser(role = UserRole.MODERATOR)
        assignArea(moderator.id, ownArea.id)
        val target = givenUser()
        assignArea(target.id, ownArea.id)
        val bearer = loginSuccessfully(moderator.serviceId).accessToken

        val response = grantArea(bearer, target.serviceId.value, outsideArea.id)
        check(response.statusCode() == 400) { response.body() }
        check(errorCode(response) == "AREA_NOT_ASSIGNABLE")
    }

    @Test
    fun `a moderator cannot revoke a non-global user's last remaining area`() {
        val area = givenArea()
        val moderator = givenUser(role = UserRole.MODERATOR)
        assignArea(moderator.id, area.id)
        val target = givenUser()
        assignArea(target.id, area.id)
        val bearer = loginSuccessfully(moderator.serviceId).accessToken

        val response = revokeArea(bearer, target.serviceId.value, area.id)
        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "USER_REQUIRES_SERVICE_AREA")
        check(serviceAreas.assignedAreaIds(target.id) == setOf(area.id)) { "the assignment must remain intact" }
    }

    @Test
    fun `SUPER_ADMIN may revoke a user's last area, leaving them unassigned`() {
        val area = givenArea()
        val target = givenUser()
        assignArea(target.id, area.id)
        val bearer = adminBearer()

        val response = revokeArea(bearer, target.serviceId.value, area.id)
        check(response.statusCode() == 200) { response.body() }
        check(serviceAreas.assignedAreaIds(target.id).isEmpty())
    }

    @Test
    fun `an inactive stored assignment is preserved until explicitly revoked, and SUPER_ADMIN can revoke it`() {
        val area = givenArea()
        val target = givenUser()
        assignArea(target.id, area.id)
        // The area retires after the assignment already exists.
        jdbc.sql("UPDATE service_areas SET status = 'INACTIVE' WHERE id = :id").param("id", area.id).update()

        val bearer = adminBearer()
        val detail = userDetail(bearer, target.serviceId.value)
        check(detail.statusCode() == 200)
        val areas = json(detail).get("areas")
        check(areas.size() == 1) { "the stale assignment must still be visible" }
        check(areas[0].get("status").asText() == "INACTIVE")

        val response = revokeArea(bearer, target.serviceId.value, area.id)
        check(response.statusCode() == 200) { response.body() }
        check(serviceAreas.assignedAreaIds(target.id).isEmpty())
    }

    @Test
    fun `a moderator cannot manage a target holding a latent assignment to an inactive area`() {
        val activeArea = givenArea()
        val moderator = givenUser(role = UserRole.MODERATOR)
        assignArea(moderator.id, activeArea.id)

        val staleArea = givenArea(status = ServiceAreaStatus.INACTIVE)
        val target = givenUser()
        assignArea(target.id, activeArea.id)
        assignArea(target.id, staleArea.id)

        val bearer = loginSuccessfully(moderator.serviceId).accessToken
        val response = deactivateUser(bearer, target.serviceId.value)
        check(response.statusCode() == 403) { response.body() }
        check(errorCode(response) == "USER_NOT_MANAGEABLE")
    }
}
