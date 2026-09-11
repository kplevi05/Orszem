package hu.orszembejelento.backend.moderation

import hu.orszembejelento.backend.moderation.support.ModerationTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/** Delete semantics by workflow state, authorization matrix, reason vocabulary and conflicts (Phase 9 brief §8/§63). */
@Import(AbstractAuthIntegrationTest.Containers::class)
class ModerationDeleteIT : ModerationTestSupport() {

    // ------------------------------------------------------------------------- NEW -> deleted

    @Test
    fun `a global MODERATOR can delete a NEW report, which remains stored with status NEW`() {
        val area = givenRoutedArea()
        val mod = givenGlobalModerator()
        val report = givenRoutedReport(area)

        val response = delete(bearerFor(mod), report.publicId, 0, "DUPLICATE")
        check(response.statusCode() == 204) { response.body() }

        val row = reportRow(report.publicId)
        check(row.status == "NEW" && row.assignedUserId == null && row.workflowVersion == 1L)
        val episode = openModerationEpisode(report.publicId)
        check(episode != null && episode.reason == "DUPLICATE" && episode.statusBeforeDelete == "NEW")
        check(openAssignmentCount(report.publicId) == 0)
    }

    // --------------------------------------------------------------------- IN_PROGRESS -> deleted

    @Test
    fun `deleting an IN_PROGRESS report ends its assignment, clears the assignee and moves status to NEW`() {
        val area = givenRoutedArea()
        val mod = givenGlobalModerator()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)

        val response = delete(bearerFor(mod), report.publicId, 1, "SPAM")
        check(response.statusCode() == 204) { response.body() }

        val row = reportRow(report.publicId)
        check(row.status == "NEW" && row.assignedUserId == null) { "must not leave a hidden current assignee: $row" }
        check(row.workflowVersion == 2L)

        val episode = openModerationEpisode(report.publicId)
        check(episode != null && episode.statusBeforeDelete == "IN_PROGRESS")

        val history = assignmentHistory(report.publicId)
        check(history.size == 1)
        check(history[0].endReason == "MODERATION_DELETED")
        check(openAssignmentCount(report.publicId) == 0)
    }

    // ------------------------------------------------------------------------- ARCHIVED -> deleted

    @Test
    fun `deleting an ARCHIVED report leaves it ARCHIVED with archivedAt unchanged`() {
        val area = givenRoutedArea()
        val mod = givenGlobalModerator()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)
        check(close(bearerFor(user), report.publicId, 1).statusCode() == 200)
        val archivedAtBefore = reportRow(report.publicId).archivedAt

        val response = delete(bearerFor(mod), report.publicId, 2, "IRRELEVANT")
        check(response.statusCode() == 204) { response.body() }

        val row = reportRow(report.publicId)
        check(row.status == "ARCHIVED" && row.assignedUserId == null)
        check(row.archivedAt == archivedAtBefore) { "archivedAt must not change: was $archivedAtBefore, now ${row.archivedAt}" }
        check(row.workflowVersion == 3L)
        check(openModerationEpisode(report.publicId)?.statusBeforeDelete == "ARCHIVED")
    }

    // ----------------------------------------------------------------------------- authorization

    @Test
    fun `SERVICE_USER cannot delete at all`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)

        val response = delete(bearerFor(user), report.publicId, 0)
        check(response.statusCode() == 403) { response.body() }
        check(errorCode(response) == "MODERATION_FORBIDDEN")
        check(openModerationEpisodeCount(report.publicId) == 0)
    }

    @Test
    fun `a territorial MODERATOR can delete a report in their own scope`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val report = givenRoutedReport(area)

        check(delete(bearerFor(mod), report.publicId, 0).statusCode() == 204)
    }

    @Test
    fun `a territorial MODERATOR cannot delete a report outside their own scope`() {
        val area = givenRoutedArea()
        val otherArea = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, otherArea.areaId)
        val report = givenRoutedReport(area)

        val response = delete(bearerFor(mod), report.publicId, 0)
        check(response.statusCode() == 404) { response.body() }
        check(errorCode(response) == "REPORT_NOT_FOUND")
        check(openModerationEpisodeCount(report.publicId) == 0)
    }

    @Test
    fun `a territorial MODERATOR cannot delete an UNCLASSIFIED report`() {
        val mod = givenTerritorialModerator()
        val publicId = givenUnclassifiedReport()

        val response = delete(bearerFor(mod), publicId, 0)
        check(response.statusCode() == 404) { response.body() }
        check(errorCode(response) == "REPORT_NOT_FOUND")
    }

    @Test
    fun `a global MODERATOR can delete an UNCLASSIFIED report`() {
        val mod = givenGlobalModerator()
        val publicId = givenUnclassifiedReport()

        check(delete(bearerFor(mod), publicId, 0).statusCode() == 204)
        check(openModerationEpisode(publicId) != null)
    }

    @Test
    fun `SUPER_ADMIN can delete anywhere, including a report whose ServiceArea has since gone inactive`() {
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        // The area retires after routing - the same SUPER_ADMIN carve-out ReportWorkflowPolicy documents.
        jdbc.sql("UPDATE service_areas SET status = 'INACTIVE' WHERE id = :id").param("id", area.areaId).update()

        val admin = adminBearer()
        check(delete(admin, report.publicId, 0).statusCode() == 204)
    }

    // ------------------------------------------------------------------------------ reasons

    @Test
    fun `every one of the six frozen reasons is accepted, including OTHER with no accompanying text`() {
        val area = givenRoutedArea()
        val mod = givenGlobalModerator()
        for (reason in listOf("SPAM", "TROLL_OR_FALSE_REPORT", "DUPLICATE", "INCORRECT", "IRRELEVANT", "OTHER")) {
            val report = givenRoutedReport(area)
            val response = delete(bearerFor(mod), report.publicId, 0, reason)
            check(response.statusCode() == 204) { "reason=$reason: ${response.body()}" }
            check(openModerationEpisode(report.publicId)?.reason == reason)
        }
    }

    @Test
    fun `an invalid reason code is rejected as a validation error, never stored`() {
        val area = givenRoutedArea()
        val mod = givenGlobalModerator()
        val report = givenRoutedReport(area)

        val response = delete(bearerFor(mod), report.publicId, 0, "NOT_A_REAL_REASON")
        check(response.statusCode() == 400) { response.body() }
        check(errorCode(response) == "VALIDATION_ERROR")
        check(openModerationEpisodeCount(report.publicId) == 0)
    }

    // ----------------------------------------------------------------------------- conflicts

    @Test
    fun `deleting an already-deleted report returns REPORT_ALREADY_DELETED, not a silent success`() {
        val area = givenRoutedArea()
        val mod = givenGlobalModerator()
        val report = givenRoutedReport(area)
        check(delete(bearerFor(mod), report.publicId, 0).statusCode() == 204)

        val response = delete(bearerFor(mod), report.publicId, 1)
        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "REPORT_ALREADY_DELETED")
        check(openModerationEpisodeCount(report.publicId) == 1) { "must not create a second open episode" }
    }

    @Test
    fun `a stale expectedVersion is rejected as REPORT_STATE_CHANGED`() {
        val area = givenRoutedArea()
        val mod = givenGlobalModerator()
        val report = givenRoutedReport(area)

        val response = delete(bearerFor(mod), report.publicId, 99)
        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "REPORT_STATE_CHANGED")
        check(openModerationEpisodeCount(report.publicId) == 0)
    }

    @Test
    fun `a nonexistent report id is existence-safe REPORT_NOT_FOUND`() {
        val mod = givenGlobalModerator()
        val response = delete(bearerFor(mod), java.util.UUID.randomUUID(), 0)
        check(response.statusCode() == 404) { response.body() }
        check(errorCode(response) == "REPORT_NOT_FOUND")
    }
}
