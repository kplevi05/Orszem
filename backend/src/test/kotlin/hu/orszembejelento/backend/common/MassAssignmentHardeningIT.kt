package hu.orszembejelento.backend.common

import hu.orszembejelento.backend.audit.support.AuditTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Phase 13 brief §57 - proves the invariant directly rather than by reading DTO source: an
 * unexpected field in a client request body can never mutate server-controlled state, on
 * any of the endpoints the brief specifically names - `status`, a `role` the endpoint does
 * not itself accept, `assignee`, a moderation actor, a routing-snapshot field, an internal
 * id, and a server-controlled `workflowVersion`/`adminVersion`.
 *
 * Per brief §57, this does **not** require a `400` everywhere - the application's shared
 * Jackson configuration is deliberately, globally lenient about unknown properties (see
 * `SubmitReportBodyAdvice`'s own KDoc), and that is an accepted, safe design as long as an
 * ignored field never reaches domain state. Each test below injects a hostile extra field,
 * confirms the call still succeeds exactly as the *legitimate* fields describe, and then
 * reads the real database row to prove the hostile field had zero effect - not merely that
 * the HTTP response "looked fine".
 *
 * The one endpoint that deliberately rejects unknown fields outright rather than ignoring
 * them - Public report submission, via `SubmitReportBodyAdvice` - had no direct test
 * anywhere in the existing suite despite its own KDoc claiming to be "confirmed by testing,
 * not assumed"; the first test below closes that specific, real gap.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class MassAssignmentHardeningIT : AuditTestSupport() {

    @Test
    fun `Public submission rejects an unrecognised field instead of silently applying it`() {
        val area = givenRoutedArea()
        val hostileBody = """
            {
              "clientSubmissionId": "${UUID.randomUUID()}",
              "occurredAt": "${clock.instant()}",
              "trainIdentifier": null,
              "settlementId": "${area.settlementId}",
              "railwayLineId": null,
              "eventTypeCode": "FIGHT",
              "status": "CLOSED",
              "serviceAreaId": "${area.areaId}",
              "categoryCode": "FAKE"
            }
        """.trimIndent()
        val response = submitReport(hostileBody)
        assertEquals(400, response.statusCode())
        assertEquals("VALIDATION_ERROR", errorCode(response))
    }

    @Test
    fun `claiming a report ignores a client-supplied assignedUserId and status`() {
        val report = givenRoutedReport()
        val user = givenServiceUser()
        grantArea(user.id, report.area.areaId)
        val attacker = givenServiceUser()
        val bearer = bearerFor(user)

        val response = post(
            "/api/v1/service/reports/${report.publicId}/claim",
            """{"expectedVersion":0,"assignedUserId":"${attacker.id}","status":"CLOSED","workflowVersion":999}""",
            bearer,
        )
        assertEquals(200, response.statusCode())

        val row = reportRow(report.publicId)
        assertEquals("IN_PROGRESS", row.status) { "the hostile 'status':'CLOSED' field must never take effect" }
        assertEquals(user.id, row.assignedUserId) { "the assignee must be the authenticated caller, never the hostile 'assignedUserId'" }
        assertEquals(1L, row.workflowVersion) { "the version bump comes only from the real claim operation, never the hostile 'workflowVersion'" }
    }

    @Test
    fun `reassigning a report ignores a client-supplied workflowVersion override`() {
        val report = givenRoutedReport()
        val claimant = givenServiceUser()
        grantArea(claimant.id, report.area.areaId)
        val target = givenServiceUser()
        grantArea(target.id, report.area.areaId)
        val claimantBearer = bearerFor(claimant)
        check(claim(claimantBearer, report.publicId, 0).statusCode() == 200)

        val admin = adminBearer()
        val response = post(
            "/api/v1/service/reports/${report.publicId}/reassign",
            """{"expectedVersion":1,"targetServiceId":"${target.serviceId.value}","workflowVersion":999,"assignedUserId":"${UUID.randomUUID()}"}""",
            admin,
        )
        assertEquals(200, response.statusCode())

        val row = reportRow(report.publicId)
        assertEquals(2L, row.workflowVersion) { "exactly one real version increment - the hostile 'workflowVersion':999 must never be adopted" }
        assertEquals(target.id, row.assignedUserId) { "the assignee is exactly the real targetServiceId, never the hostile 'assignedUserId'" }
    }

    @Test
    fun `moderation-deleting a report ignores a client-supplied moderation actor`() {
        val report = givenRoutedReport()
        val moderator = givenTerritorialModerator()
        grantArea(moderator.id, report.area.areaId)
        val framedActor = givenSuperAdmin()
        val bearer = bearerFor(moderator)

        val response = post(
            "/api/v1/service/moderation/reports/${report.publicId}/delete",
            """{"expectedVersion":0,"reason":"SPAM","deletedByUserId":"${framedActor.id}","actorUserId":"${framedActor.id}"}""",
            bearer,
        )
        assertEquals(204, response.statusCode())

        val episode = openModerationEpisode(report.publicId)
        checkNotNull(episode)
        assertEquals(moderator.id, episode.deletedByUserId) { "the real moderator, never a hostile framed 'deletedByUserId'" }
        assertNotEquals(framedActor.id, episode.deletedByUserId)
    }

    @Test
    fun `creating a user ignores a client-supplied serviceId, status and internal id`() {
        val admin = adminBearer()
        val hostileServiceId = "SZ-000001"
        val hostileId = UUID.randomUUID()

        val response = post(
            "/api/v1/service/user-management/users",
            """{"role":"SERVICE_USER","areaIds":[],"globalAreaAccess":false,""" +
                """"serviceId":"$hostileServiceId","id":"$hostileId","status":"DEACTIVATED","mustChangePassword":false}""",
            admin,
        )
        assertEquals(200, response.statusCode())

        val actualServiceId = json(response).get("serviceId").asText()
        assertNotEquals(hostileServiceId, actualServiceId) { "the service ID is always server-generated, never client-supplied" }

        val status = jdbc.sql("SELECT status FROM users WHERE service_id = :id")
            .param("id", actualServiceId)
            .query(String::class.java)
            .single()
        assertEquals("ACTIVE", status) { "a newly-created user is always ACTIVE - the hostile 'status':'DEACTIVATED' must never take effect" }

        val storedId = jdbc.sql("SELECT id FROM users WHERE service_id = :id")
            .param("id", actualServiceId)
            .query(UUID::class.java)
            .single()
        assertNotEquals(hostileId, storedId) { "the row's internal id is always server-generated" }
    }

    @Test
    fun `changing a role ignores a client-supplied globalAreaAccess field`() {
        val admin = adminBearer()
        val target = givenServiceUser()
        check(!globalAccessOf(target.serviceId.value)) { "fixture precondition: the target starts without global access" }

        val response = post(
            "/api/v1/service/user-management/users/${target.serviceId.value}/role",
            """{"role":"MODERATOR","globalAreaAccess":true}""",
            admin,
        )
        assertEquals(200, response.statusCode())

        val role = jdbc.sql("SELECT role FROM users WHERE id = :id").param("id", target.id).query(String::class.java).single()
        assertEquals("MODERATOR", role)
        assertEquals(false, globalAccessOf(target.serviceId.value)) {
            "the role endpoint does not accept globalAreaAccess at all - the hostile field must never take effect"
        }
    }

    @Test
    fun `renaming a service area ignores a client-supplied status and adminVersion override`() {
        val admin = adminBearer()
        val area = givenRoutedArea()

        val response = post(
            "/api/v1/service/service-area-admin/areas/${area.areaId}/rename",
            """{"expectedVersion":0,"name":"Atnevezve","status":"INACTIVE","adminVersion":999}""",
            admin,
        )
        assertEquals(200, response.statusCode())

        assertEquals("ACTIVE", areaStatus(area.areaId)) { "rename never touches status - the hostile 'status':'INACTIVE' must never take effect" }
        assertEquals(1L, areaAdminVersion(area.areaId)) { "exactly one real version increment - the hostile 'adminVersion':999 must never be adopted" }
    }

    private fun globalAccessOf(serviceId: String): Boolean =
        jdbc.sql("SELECT global_area_access FROM users WHERE service_id = :id").param("id", serviceId).query(Boolean::class.java).single()
}
