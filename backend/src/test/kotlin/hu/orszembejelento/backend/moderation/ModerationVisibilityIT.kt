package hu.orszembejelento.backend.moderation

import hu.orszembejelento.backend.moderation.support.ModerationTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Deleted list/detail scope matrix (Phase 9 brief §3/§21/§63): area-based, exactly mirroring
 * ordinary [hu.orszembejelento.backend.reportworkflow.ReportWorkflowVisibilityIT] - deleted or
 * not is irrelevant to *whose* scope decides visibility.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class ModerationVisibilityIT : ModerationTestSupport() {

    @Test
    fun `a territorial MODERATOR sees only deleted reports in their own area, never UNCLASSIFIED`() {
        val ownArea = givenRoutedArea()
        val otherArea = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, ownArea.areaId)

        val inScope = givenRoutedReport(ownArea)
        val outOfScope = givenRoutedReport(otherArea)
        val unclassified = givenUnclassifiedReport()

        val admin = adminBearer()
        check(delete(admin, inScope.publicId, 0).statusCode() == 204)
        check(delete(admin, outOfScope.publicId, 0).statusCode() == 204)
        check(delete(admin, unclassified, 0).statusCode() == 204)

        val response = deletedList(bearerFor(mod))
        check(response.statusCode() == 200) { response.body() }
        val ids = json(response).get("items").asList().map { it.get("publicReportId").asText() }
        check(ids == listOf(inScope.publicId.toString())) { "expected only the in-scope deleted report, got $ids" }
    }

    @Test
    fun `a global MODERATOR sees every deleted report, including UNCLASSIFIED`() {
        val area = givenRoutedArea()
        val mod = givenGlobalModerator()
        val routed = givenRoutedReport(area)
        val unclassified = givenUnclassifiedReport()

        val admin = adminBearer()
        check(delete(admin, routed.publicId, 0).statusCode() == 204)
        check(delete(admin, unclassified, 0).statusCode() == 204)

        val ids = json(deletedList(bearerFor(mod))).get("items").asList().map { it.get("publicReportId").asText() }.toSet()
        check(ids == setOf(routed.publicId.toString(), unclassified.toString()))
    }

    @Test
    fun `SUPER_ADMIN sees every deleted report, including one whose ServiceArea has since gone inactive`() {
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        val admin = adminBearer()
        check(delete(admin, report.publicId, 0).statusCode() == 204)
        jdbc.sql("UPDATE service_areas SET status = 'INACTIVE' WHERE id = :id").param("id", area.areaId).update()

        val ids = json(deletedList(admin)).get("items").asList().map { it.get("publicReportId").asText() }
        check(ids == listOf(report.publicId.toString()))
    }

    @Test
    fun `a MODERATOR sees a deleted report even when a different moderator deleted it - scope is area-based, not actor-history-based`() {
        val area = givenRoutedArea()
        val deleter = givenGlobalModerator()
        val viewer = givenTerritorialModerator()
        grantArea(viewer.id, area.areaId)
        val report = givenRoutedReport(area)
        check(delete(bearerFor(deleter), report.publicId, 0).statusCode() == 204)

        val ids = json(deletedList(bearerFor(viewer))).get("items").asList().map { it.get("publicReportId").asText() }
        check(ids == listOf(report.publicId.toString()))
    }

    @Test
    fun `SERVICE_USER cannot list deleted reports`() {
        val response = deletedList(bearerFor(givenServiceUser()))
        check(response.statusCode() == 403) { response.body() }
        check(errorCode(response) == "MODERATION_FORBIDDEN")
    }

    @Test
    fun `SERVICE_USER cannot fetch deleted detail`() {
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        val admin = adminBearer()
        check(delete(admin, report.publicId, 0).statusCode() == 204)

        val response = deletedDetail(bearerFor(givenServiceUser()), report.publicId)
        check(response.statusCode() == 403) { response.body() }
        check(errorCode(response) == "MODERATION_FORBIDDEN")
    }

    @Test
    fun `a territorial MODERATOR gets an existence-safe 404 for a deleted report outside their scope`() {
        val ownArea = givenRoutedArea()
        val otherArea = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, ownArea.areaId)
        val outOfScope = givenRoutedReport(otherArea)
        val admin = adminBearer()
        check(delete(admin, outOfScope.publicId, 0).statusCode() == 204)

        val response = deletedDetail(bearerFor(mod), outOfScope.publicId)
        check(response.statusCode() == 404) { response.body() }
        check(errorCode(response) == "REPORT_NOT_FOUND")
    }

    @Test
    fun `a nonexistent report id and one that is not currently deleted are both existence-safe 404 on deleted detail`() {
        val admin = adminBearer()
        val nonexistent = deletedDetail(admin, java.util.UUID.randomUUID())
        check(nonexistent.statusCode() == 404 && errorCode(nonexistent) == "REPORT_NOT_FOUND")

        val area = givenRoutedArea()
        val neverDeleted = givenRoutedReport(area)
        val notDeleted = deletedDetail(admin, neverDeleted.publicId)
        check(notDeleted.statusCode() == 404 && errorCode(notDeleted) == "REPORT_NOT_FOUND") {
            "a report that exists but was never deleted must be indistinguishable from a nonexistent one here: ${notDeleted.body()}"
        }
    }

    @Test
    fun `SUPER_ADMIN deleted detail carries the full moderation section`() {
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        val admin = adminBearer()
        check(delete(admin, report.publicId, 0, "TROLL_OR_FALSE_REPORT").statusCode() == 204)

        val response = deletedDetail(admin, report.publicId)
        check(response.statusCode() == 200) { response.body() }
        val body = json(response)
        check(body.get("reason").asText() == "TROLL_OR_FALSE_REPORT")
        check(body.get("statusBeforeDelete").asText() == "NEW")
        check(body.get("restoreTargetStatus").asText() == "NEW")
        check(body.get("deletedByServiceId").asText().isNotBlank())
        check(body.get("workflowVersion").asLong() == 1L)
    }

    @Test
    fun `an out-of-scope deleted report and a nonexistent one produce the same 404 shape - no existence leak`() {
        val ownArea = givenRoutedArea()
        val otherArea = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, ownArea.areaId)
        val outOfScope = givenRoutedReport(otherArea)
        val admin = adminBearer()
        check(delete(admin, outOfScope.publicId, 0).statusCode() == 204)

        val outOfScopeResponse = deletedDetail(bearerFor(mod), outOfScope.publicId)
        val nonexistentResponse = deletedDetail(bearerFor(mod), java.util.UUID.randomUUID())
        check(outOfScopeResponse.statusCode() == nonexistentResponse.statusCode())
        check(errorCode(outOfScopeResponse) == errorCode(nonexistentResponse))
    }
}
