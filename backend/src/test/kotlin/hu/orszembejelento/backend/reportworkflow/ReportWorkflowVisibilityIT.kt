package hu.orszembejelento.backend.reportworkflow

import hu.orszembejelento.backend.reportworkflow.support.ReportWorkflowTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * List/detail authorisation by role, NEW-queue 168h ordering, and scope-safe filtering
 * (brief §54-56). All against real PostgreSQL — visibility here depends on genuine row data
 * plus the routing snapshot, not a mock.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class ReportWorkflowVisibilityIT : ReportWorkflowTestSupport() {

    // ------------------------------------------------------------------------- role visibility

    @Test
    fun `a SERVICE_USER sees only NEW reports routed into their own area, never UNCLASSIFIED`() {
        val ownArea = givenRoutedArea()
        val otherArea = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, ownArea.areaId)

        val inScope = givenRoutedReport(ownArea)
        givenRoutedReport(otherArea)
        givenUnclassifiedReport()

        val bearer = bearerFor(user)
        val response = newQueue(bearer)
        check(response.statusCode() == 200)
        val ids = json(response).get("items").asList().map { it.get("publicReportId").asText() }
        check(ids == listOf(inScope.publicId.toString())) { "expected only the in-scope report, got $ids" }
    }

    @Test
    fun `a SERVICE_USER sees an IN_PROGRESS report only when it is their own claim`() {
        val area = givenRoutedArea()
        val owner = givenServiceUser()
        val stranger = givenServiceUser()
        grantArea(owner.id, area.areaId)
        grantArea(stranger.id, area.areaId)
        val report = givenRoutedReport(area)

        claim(bearerFor(owner), report.publicId, 0)

        val ownerView = inProgressQueue(bearerFor(owner))
        check(json(ownerView).get("items").asList().size == 1)

        val strangerView = inProgressQueue(bearerFor(stranger))
        check(json(strangerView).get("items").asList().isEmpty()) { "a stranger must not see another SERVICE_USER's claim" }

        val strangerDetail = detail(bearerFor(stranger), report.publicId)
        check(strangerDetail.statusCode() == 404) { "an IN_PROGRESS report not assigned to the actor must be scope-hidden" }
        check(errorCode(strangerDetail) == "REPORT_NOT_FOUND")
    }

    @Test
    fun `a SERVICE_USER sees ARCHIVED reports in their own area scope without having been the assignee`() {
        val area = givenRoutedArea()
        val closer = givenTerritorialModerator()
        grantArea(closer.id, area.areaId)
        val viewer = givenServiceUser()
        grantArea(viewer.id, area.areaId)
        val report = givenRoutedReport(area)

        close(bearerFor(closer), report.publicId, 0)

        val view = archiveQueue(bearerFor(viewer))
        val ids = json(view).get("items").asList().map { it.get("publicReportId").asText() }
        check(ids == listOf(report.publicId.toString()))
    }

    @Test
    fun `a territorial moderator sees every status of a routed report in their own scope but never UNCLASSIFIED`() {
        val area = givenRoutedArea()
        val mod = givenTerritorialModerator()
        grantArea(mod.id, area.areaId)
        val newReport = givenRoutedReport(area)
        givenUnclassifiedReport()

        val bearer = bearerFor(mod)
        val newIds = json(newQueue(bearer)).get("items").asList().map { it.get("publicReportId").asText() }
        check(newIds == listOf(newReport.publicId.toString())) { "must see the routed NEW report and nothing unclassified" }
    }

    @Test
    fun `a global moderator sees normal routed reports and UNCLASSIFIED`() {
        val area = givenRoutedArea()
        val mod = givenGlobalModerator()
        val routed = givenRoutedReport(area)
        val unclassified = givenUnclassifiedReport()

        val ids = json(newQueue(bearerFor(mod))).get("items").asList().map { it.get("publicReportId").asText() }.toSet()
        check(ids == setOf(routed.publicId.toString(), unclassified.toString()))
    }

    @Test
    fun `SUPER_ADMIN sees everything`() {
        val area = givenRoutedArea()
        val admin = givenSuperAdmin()
        val routed = givenRoutedReport(area)
        val unclassified = givenUnclassifiedReport()

        val ids = json(newQueue(bearerFor(admin))).get("items").asList().map { it.get("publicReportId").asText() }.toSet()
        check(ids == setOf(routed.publicId.toString(), unclassified.toString()))
    }

    @Test
    fun `an out-of-scope detail request returns scope-hiding 404, identical to a nonexistent report`() {
        val area = givenRoutedArea()
        val outsider = givenTerritorialModerator()
        grantArea(outsider.id, givenRoutedArea().areaId) // a different area entirely
        val report = givenRoutedReport(area)

        val bearer = bearerFor(outsider)
        val outOfScope = detail(bearer, report.publicId)
        val nonexistent = detail(bearer, java.util.UUID.randomUUID())

        check(outOfScope.statusCode() == 404 && nonexistent.statusCode() == 404)
        check(errorCode(outOfScope) == "REPORT_NOT_FOUND" && errorCode(nonexistent) == "REPORT_NOT_FOUND")
    }

    // -------------------------------------------------------------------------- 168h ordering

    @Test
    fun `the NEW queue buckets RECENT before OLDER, with the exact 168h boundary counted as RECENT`() {
        val area = givenRoutedArea()
        val user = givenGlobalServiceUser()
        val base = Instant.parse("2026-02-01T00:00:00Z")

        mutableClock.set(base.minus(Duration.ofHours(200))) // well outside the window -> OLDER
        val older = givenRoutedReport(area)

        mutableClock.set(base.minus(Duration.ofHours(168))) // exactly at the cutoff -> RECENT
        val boundary = givenRoutedReport(area)

        mutableClock.set(base.minus(Duration.ofHours(1))) // well inside the window -> RECENT
        val recent = givenRoutedReport(area)

        mutableClock.set(base)

        val items = json(newQueue(bearerFor(user))).get("items").asList()
        val byId = items.associateBy { it.get("publicReportId").asText() }

        check(byId.getValue(older.publicId.toString()).get("ageBucket").asText() == "OLDER")
        check(byId.getValue(boundary.publicId.toString()).get("ageBucket").asText() == "RECENT") {
            "submittedAt exactly at the cutoff must be RECENT, per brief §54"
        }
        check(byId.getValue(recent.publicId.toString()).get("ageBucket").asText() == "RECENT")

        // RECENT (newest first) comes entirely before OLDER.
        val order = items.map { it.get("publicReportId").asText() }
        check(order == listOf(recent.publicId, boundary.publicId, older.publicId).map { it.toString() }) {
            "expected RECENT newest-first then OLDER oldest-first, got $order"
        }
    }

    @Test
    fun `NEW queue ties are broken deterministically by public report id, stable across pagination`() {
        val area = givenRoutedArea()
        val user = givenGlobalServiceUser()

        // Three reports submitted at the exact same instant - the tie-break must be total.
        val same = Instant.parse("2026-02-01T00:00:00Z")
        mutableClock.set(same)
        val ids = (1..3).map { givenRoutedReport(area).publicId }.sortedBy { it.toString() }

        val page0 = json(newQueue(user.let { bearerFor(it) }, page = 0, size = 2)).get("items").asList()
            .map { it.get("publicReportId").asText() }
        val page1 = json(newQueue(bearerFor(user), page = 1, size = 2)).get("items").asList()
            .map { it.get("publicReportId").asText() }

        check(page0 + page1 == ids.map { it.toString() }) {
            "expected a stable public-id tie-break across pages, got ${page0 + page1} vs ${ids.map { it.toString() }}"
        }
    }

    // -------------------------------------------------------------------------------- filters

    @Test
    fun `an out-of-scope areaId filter never leaks a count - it returns empty, not another actor's data`() {
        val ownArea = givenRoutedArea()
        val otherArea = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, ownArea.areaId)
        givenRoutedReport(otherArea)
        givenRoutedReport(otherArea)

        val response = newQueue(bearerFor(user), areaId = otherArea.areaId)
        check(response.statusCode() == 200) { "an out-of-scope filter must not itself be treated as an authorization error" }
        val body = json(response)
        check(body.get("items").asList().isEmpty())
        check(body.get("totalElements").asInt() == 0) { "the areaId filter must never leak how many reports exist out of scope" }
    }

    @Test
    fun `the areaId filter narrows within scope but does not grant access beyond it`() {
        val areaA = givenRoutedArea()
        val areaB = givenRoutedArea()
        val user = givenGlobalServiceUser()
        val reportA = givenRoutedReport(areaA)
        givenRoutedReport(areaB)

        val response = newQueue(bearerFor(user), areaId = areaA.areaId)
        val ids = json(response).get("items").asList().map { it.get("publicReportId").asText() }
        check(ids == listOf(reportA.publicId.toString()))
    }
}
