package hu.orszembejelento.backend.moderation

import hu.orszembejelento.backend.moderation.support.ModerationTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/** Restore semantics, SUPER_ADMIN-only authorization, and the frozen restore-target rule (Phase 9 brief §9/§63). */
@Import(AbstractAuthIntegrationTest.Containers::class)
class ModerationRestoreIT : ModerationTestSupport() {

    @Test
    fun `restoring a formerly NEW report returns it to NEW`() {
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        val admin = adminBearer()
        check(delete(admin, report.publicId, 0).statusCode() == 204)

        val response = restore(admin, report.publicId, 1)
        check(response.statusCode() == 204) { response.body() }

        val row = reportRow(report.publicId)
        check(row.status == "NEW" && row.assignedUserId == null && row.workflowVersion == 2L)
        check(openModerationEpisodeCount(report.publicId) == 0)
        check(moderationEpisodeCount(report.publicId) == 1) { "the closed episode itself must still be kept, never hard-deleted" }
    }

    @Test
    fun `restoring a formerly IN_PROGRESS report returns it to NEW, unassigned - never resurrecting the old assignment`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)
        val admin = adminBearer()
        check(delete(admin, report.publicId, 1).statusCode() == 204)

        val response = restore(admin, report.publicId, 2)
        check(response.statusCode() == 204) { response.body() }

        val row = reportRow(report.publicId)
        check(row.status == "NEW" && row.assignedUserId == null) { "must never resurrect the terminated assignment: $row" }

        val history = assignmentHistory(report.publicId)
        check(history.size == 1) { "restore must not open a new assignment episode: $history" }
        check(history[0].endReason == "MODERATION_DELETED")
        check(openAssignmentCount(report.publicId) == 0)
    }

    @Test
    fun `restoring a formerly ARCHIVED report returns it to ARCHIVED`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val report = givenRoutedReport(area)
        check(claim(bearerFor(user), report.publicId, 0).statusCode() == 200)
        check(close(bearerFor(user), report.publicId, 1).statusCode() == 200)
        val archivedAtBefore = reportRow(report.publicId).archivedAt
        val admin = adminBearer()
        check(delete(admin, report.publicId, 2).statusCode() == 204)

        val response = restore(admin, report.publicId, 3)
        check(response.statusCode() == 204) { response.body() }

        val row = reportRow(report.publicId)
        check(row.status == "ARCHIVED" && row.assignedUserId == null)
        check(row.archivedAt == archivedAtBefore)
    }

    @Test
    fun `a MODERATOR - even global - may never restore`() {
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        val globalMod = givenGlobalModerator()
        check(delete(bearerFor(globalMod), report.publicId, 0).statusCode() == 204)

        val response = restore(bearerFor(globalMod), report.publicId, 1)
        check(response.statusCode() == 403) { response.body() }
        check(errorCode(response) == "MODERATION_FORBIDDEN")
        check(openModerationEpisodeCount(report.publicId) == 1) { "the rejected restore must not have partially applied" }
    }

    @Test
    fun `SERVICE_USER cannot restore`() {
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        val admin = adminBearer()
        check(delete(admin, report.publicId, 0).statusCode() == 204)

        val response = restore(bearerFor(givenServiceUser()), report.publicId, 1)
        check(response.statusCode() == 403) { response.body() }
        check(errorCode(response) == "MODERATION_FORBIDDEN")
    }

    @Test
    fun `restoring a report that is not currently deleted returns REPORT_NOT_DELETED`() {
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        val admin = adminBearer()

        val response = restore(admin, report.publicId, 0)
        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "REPORT_NOT_DELETED")
    }

    @Test
    fun `restoring twice returns REPORT_NOT_DELETED the second time, never a duplicate restore`() {
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        val admin = adminBearer()
        check(delete(admin, report.publicId, 0).statusCode() == 204)
        check(restore(admin, report.publicId, 1).statusCode() == 204)

        val response = restore(admin, report.publicId, 2)
        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "REPORT_NOT_DELETED")
    }

    @Test
    fun `a stale expectedVersion on restore is rejected as REPORT_STATE_CHANGED`() {
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        val admin = adminBearer()
        check(delete(admin, report.publicId, 0).statusCode() == 204)

        val response = restore(admin, report.publicId, 99)
        check(response.statusCode() == 409) { response.body() }
        check(errorCode(response) == "REPORT_STATE_CHANGED")
        check(openModerationEpisodeCount(report.publicId) == 1)
    }

    @Test
    fun `a nonexistent report id is existence-safe REPORT_NOT_FOUND on restore too`() {
        val admin = adminBearer()
        val response = restore(admin, java.util.UUID.randomUUID(), 0)
        check(response.statusCode() == 404) { response.body() }
        check(errorCode(response) == "REPORT_NOT_FOUND")
    }

    @Test
    fun `a report may be deleted and restored multiple times, keeping every historical episode`() {
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        val admin = adminBearer()

        check(delete(admin, report.publicId, 0, "SPAM").statusCode() == 204)
        check(restore(admin, report.publicId, 1).statusCode() == 204)
        check(delete(admin, report.publicId, 2, "DUPLICATE").statusCode() == 204)
        check(restore(admin, report.publicId, 3).statusCode() == 204)

        check(moderationEpisodeCount(report.publicId) == 2)
        check(openModerationEpisodeCount(report.publicId) == 0)
        val row = reportRow(report.publicId)
        check(row.status == "NEW" && row.workflowVersion == 4L)
    }
}
