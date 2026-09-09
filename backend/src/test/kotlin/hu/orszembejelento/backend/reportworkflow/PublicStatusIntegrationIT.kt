package hu.orszembejelento.backend.reportworkflow

import hu.orszembejelento.backend.reportworkflow.support.ReportWorkflowTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Proves the Phase 4 anonymous Public report lookup reflects the Phase 7 workflow
 * automatically (brief §49-51) - zero Public client or Public API code changes were made in
 * Phase 7; [hu.orszembejelento.backend.reports.api.PublicReportController] already renders
 * `report.status.toPublic()` generically off whatever status is currently stored.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class PublicStatusIntegrationIT : ReportWorkflowTestSupport() {

    @Test
    fun `a freshly created report is RECEIVED, becomes PROCESSING on claim, RECEIVED again on return, and CLOSED on close - visible anonymously throughout`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)

        val created = givenCreatedReport(settlementId = area.settlementId, railwayLineId = area.lineId)

        fun publicStatus(): String {
            val response = getPublicReport(created.publicId, created.credential)
            check(response.statusCode() == 200) { "anonymous lookup must keep working throughout the workflow: ${response.body()}" }
            return json(response).get("status").asText()
        }

        check(publicStatus() == "RECEIVED") { "a freshly created report must be RECEIVED" }

        check(claim(bearerFor(user), created.publicId, 0).statusCode() == 200)
        check(publicStatus() == "PROCESSING") { "a claimed report must be PROCESSING" }

        check(returnToNew(bearerFor(user), created.publicId, 1).statusCode() == 200)
        check(publicStatus() == "RECEIVED") { "a returned report must go back to RECEIVED" }

        check(claim(bearerFor(user), created.publicId, 2).statusCode() == 200)
        check(close(bearerFor(user), created.publicId, 3).statusCode() == 200)
        check(publicStatus() == "CLOSED") { "a closed report must be CLOSED" }
    }

    @Test
    fun `MODERATOR closing a NEW report directly still surfaces as CLOSED to the anonymous submitter`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val created = givenCreatedReport(settlementId = area.settlementId, railwayLineId = area.lineId)

        check(close(bearerFor(mod), created.publicId, 0).statusCode() == 200)

        val response = getPublicReport(created.publicId, created.credential)
        check(response.statusCode() == 200)
        check(json(response).get("status").asText() == "CLOSED")
    }
}
