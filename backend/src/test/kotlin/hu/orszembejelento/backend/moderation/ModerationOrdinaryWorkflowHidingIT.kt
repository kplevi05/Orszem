package hu.orszembejelento.backend.moderation

import hu.orszembejelento.backend.moderation.support.ModerationTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * A currently-deleted report must be invisible and unmutatable through the ordinary Service
 * workflow surface, existence-safe like everywhere else (Phase 9 brief §12/§13/§63).
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class ModerationOrdinaryWorkflowHidingIT : ModerationTestSupport() {

    @Test
    fun `a deleted NEW report disappears from the NEW queue and ordinary detail`() {
        val area = givenRoutedArea()
        val mod = givenGlobalModerator()
        val report = givenRoutedReport(area)
        val bearer = bearerFor(mod)
        check(json(newQueue(bearer)).get("items").asList().map { it.get("publicReportId").asText() }.contains(report.publicId.toString()))

        check(delete(bearer, report.publicId, 0).statusCode() == 204)

        check(json(newQueue(bearer)).get("items").asList().map { it.get("publicReportId").asText() }.contains(report.publicId.toString()).not())
        val detailResponse = detail(bearer, report.publicId)
        check(detailResponse.statusCode() == 404) { detailResponse.body() }
        check(errorCode(detailResponse) == "REPORT_NOT_FOUND")
    }

    @Test
    fun `a deleted IN_PROGRESS report disappears from the IN_PROGRESS queue`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)

        val admin = adminBearer()
        check(delete(admin, report.publicId, 1).statusCode() == 204)

        check(json(inProgressQueue(bearerFor(user))).get("items").asList().isEmpty())
        val detailResponse = detail(bearerFor(user), report.publicId)
        check(detailResponse.statusCode() == 404)
    }

    @Test
    fun `a deleted ARCHIVED report disappears from the Archive queue`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)
        check(close(bearerFor(user), report.publicId, 1).statusCode() == 200)

        val admin = adminBearer()
        check(delete(admin, report.publicId, 2).statusCode() == 204)

        check(json(archiveQueue(admin)).get("items").asList().map { it.get("publicReportId").asText() }.contains(report.publicId.toString()).not())
    }

    @Test
    fun `a deleted report cannot be claimed - existence-safe 404, never REPORT_ALREADY_ASSIGNED`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        val admin = adminBearer()
        check(delete(admin, report.publicId, 0).statusCode() == 204)

        val response = claim(bearerFor(user), report.publicId, 1)
        check(response.statusCode() == 404) { response.body() }
        check(errorCode(response) == "REPORT_NOT_FOUND")
        check(reportRow(report.publicId).status == "NEW" && reportRow(report.publicId).assignedUserId == null)
    }

    @Test
    fun `a deleted report cannot be closed by a supervisor`() {
        val area = givenRoutedArea()
        val mod = givenGlobalModerator()
        val report = givenRoutedReport(area)
        check(delete(bearerFor(mod), report.publicId, 0).statusCode() == 204)

        val response = close(bearerFor(mod), report.publicId, 1)
        check(response.statusCode() == 404) { response.body() }
        check(errorCode(response) == "REPORT_NOT_FOUND")
        check(reportRow(report.publicId).status == "NEW") { "a stale close must never silently archive a deleted report" }
    }

    @Test
    fun `a deleted report's in-flight return cannot process, even though it was IN_PROGRESS at the moment of the request`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)
        val admin = adminBearer()
        check(delete(admin, report.publicId, 1).statusCode() == 204)

        val response = returnToNew(bearerFor(user), report.publicId, 2)
        check(response.statusCode() == 404) { response.body() }
        check(errorCode(response) == "REPORT_NOT_FOUND")
    }

    @Test
    fun `a deleted report cannot be reassigned`() {
        val area = givenRoutedArea()
        val mod = givenGlobalModerator()
        val original = givenServiceUser()
        val target = givenServiceUser()
        grantArea(original.id, area.areaId)
        grantArea(target.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(original), report.publicId, 0).statusCode() == 200)
        check(delete(bearerFor(mod), report.publicId, 1).statusCode() == 204)

        val response = reassign(bearerFor(mod), report.publicId, 2, target.serviceId)
        check(response.statusCode() == 404) { response.body() }
        check(errorCode(response) == "REPORT_NOT_FOUND")
    }

    @Test
    fun `a restored report reappears in the ordinary queue and detail exactly as before`() {
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        val admin = adminBearer()
        check(delete(admin, report.publicId, 0).statusCode() == 204)
        check(restore(admin, report.publicId, 1).statusCode() == 204)

        check(json(newQueue(admin)).get("items").asList().map { it.get("publicReportId").asText() }.contains(report.publicId.toString()))
        val detailResponse = detail(admin, report.publicId)
        check(detailResponse.statusCode() == 200) { detailResponse.body() }
    }
}
