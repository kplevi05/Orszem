package hu.orszembejelento.backend.analytics

import hu.orszembejelento.backend.analytics.support.AnalyticsTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Summary shape correctness (Phase 11 brief §16-20/§57/§59): status counts, the empty case,
 * the daily trend's zero-fill and sum invariant, category/event-type breakdowns and their
 * filters, and the response's privacy/caching contract.
 */
class AnalyticsSummaryIT : AnalyticsTestSupport() {

    @Test
    fun `an empty period returns zero totals, empty trend buckets, and empty breakdowns`() {
        val bearer = bearerFor(givenSuperAdmin())
        val body = json(summary(bearer, period = "LAST_7_DAYS"))

        assertEquals(0, body.get("totalReports").asInt())
        assertEquals(0, body.get("statusCounts").get("new").asInt())
        assertEquals(0, body.get("statusCounts").get("inProgress").asInt())
        assertEquals(0, body.get("statusCounts").get("archived").asInt())
        assertTrue(body.get("categories").asList().isEmpty())
        assertTrue(body.get("topEventTypes").asList().isEmpty())
        // Every requested local date is still present, each at count 0 (brief §18).
        assertEquals(7, body.get("trend").asList().size)
        assertTrue(body.get("trend").asList().all { it.get("count").asInt() == 0 })
    }

    @Test
    fun `status counts sum to totalReports for a mix of NEW, IN_PROGRESS and ARCHIVED`() {
        val area = givenRoutedArea()
        val admin = givenSuperAdmin()
        val adminBearer = bearerFor(admin)

        val newReport = givenRoutedReport(area) // stays NEW
        val inProgress = givenRoutedReport(area)
        val archived = givenRoutedReport(area)

        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val userBearer = bearerFor(user)
        claim(userBearer, inProgress.publicId, reportRow(inProgress.publicId).workflowVersion)
        close(adminBearer, archived.publicId, reportRow(archived.publicId).workflowVersion)

        val body = json(summary(adminBearer, areaId = area.areaId))
        assertEquals(3, body.get("totalReports").asInt())
        assertEquals(1, body.get("statusCounts").get("new").asInt())
        assertEquals(1, body.get("statusCounts").get("inProgress").asInt())
        assertEquals(1, body.get("statusCounts").get("archived").asInt())
        assertEquals(
            body.get("totalReports").asInt(),
            body.get("statusCounts").let { it.get("new").asInt() + it.get("inProgress").asInt() + it.get("archived").asInt() },
        )
        // ids not needed further, only exercised for the claim/close calls above
        check(newReport.publicId != inProgress.publicId)
    }

    @Test
    fun `the daily trend sums to totalReports`() {
        val area = givenRoutedArea()
        val admin = givenSuperAdmin()
        repeat(3) { givenRoutedReport(area) }

        val body = json(summary(bearerFor(admin), period = "LAST_7_DAYS", areaId = area.areaId))
        val trendSum = body.get("trend").asList().sumOf { it.get("count").asInt() }
        assertEquals(body.get("totalReports").asInt(), trendSum)
        assertEquals(3, trendSum)
    }

    @Test
    fun `category breakdown is sorted count DESC then code ASC, and sums to totalReports when unfiltered`() {
        val area = givenRoutedArea()
        val admin = givenSuperAdmin()
        // 2x VIOLENCE_DANGER (FIGHT), 1x DISTURBANCE_HARASSMENT (LOUD_BEHAVIOR), 1x THEFT_PROPERTY (THEFT)
        givenCreatedReport(settlementId = area.settlementId, railwayLineId = area.lineId, eventTypeCode = "FIGHT")
        givenCreatedReport(settlementId = area.settlementId, railwayLineId = area.lineId, eventTypeCode = "FIGHT")
        givenCreatedReport(settlementId = area.settlementId, railwayLineId = area.lineId, eventTypeCode = "LOUD_BEHAVIOR")
        givenCreatedReport(settlementId = area.settlementId, railwayLineId = area.lineId, eventTypeCode = "THEFT")

        val body = json(summary(bearerFor(admin), areaId = area.areaId))
        val categories = body.get("categories").asList()
        assertEquals("VIOLENCE_DANGER", categories[0].get("code").asText())
        assertEquals(2, categories[0].get("count").asInt())
        // The remaining two both have count 1 - tie-broken by code ascending.
        val tied = categories.drop(1).map { it.get("code").asText() }
        assertEquals(tied.sorted(), tied)
        assertEquals(4, categories.sumOf { it.get("count").asInt() })
        assertEquals(body.get("totalReports").asInt(), categories.sumOf { it.get("count").asInt() })
    }

    @Test
    fun `a categoryCode filter narrows both the summary and the category breakdown to that one category`() {
        val area = givenRoutedArea()
        val admin = givenSuperAdmin()
        givenCreatedReport(settlementId = area.settlementId, railwayLineId = area.lineId, eventTypeCode = "FIGHT")
        givenCreatedReport(settlementId = area.settlementId, railwayLineId = area.lineId, eventTypeCode = "LOUD_BEHAVIOR")

        val body = json(summary(bearerFor(admin), areaId = area.areaId, categoryCode = "VIOLENCE_DANGER"))
        assertEquals(1, body.get("totalReports").asInt())
        val categories = body.get("categories").asList()
        assertEquals(1, categories.size)
        assertEquals("VIOLENCE_DANGER", categories[0].get("code").asText())
    }

    @Test
    fun `an unknown categoryCode is rejected as 404, not silently ignored`() {
        val response = summary(bearerFor(givenSuperAdmin()), categoryCode = "NOT_A_REAL_CATEGORY")
        assertEquals(404, response.statusCode())
        assertEquals("ANALYTICS_CATEGORY_NOT_FOUND", errorCode(response))
    }

    @Test
    fun `top event types returns at most 5, sorted count DESC then code ASC`() {
        val area = givenRoutedArea()
        val admin = givenSuperAdmin()
        val codes = listOf("FIGHT", "KNIFE_ATTACK", "PHYSICAL_ASSAULT", "THREAT", "WEAPON_THREAT", "ROBBERY", "PASSENGER_ASSAULT")
        codes.forEach { givenCreatedReport(settlementId = area.settlementId, railwayLineId = area.lineId, eventTypeCode = it) }
        // One extra FIGHT so it clearly sorts first.
        givenCreatedReport(settlementId = area.settlementId, railwayLineId = area.lineId, eventTypeCode = "FIGHT")

        val body = json(summary(bearerFor(admin), areaId = area.areaId))
        val top = body.get("topEventTypes").asList()
        assertEquals(5, top.size)
        assertEquals("FIGHT", top[0].get("code").asText())
        assertEquals(2, top[0].get("count").asInt())
        val restCodes = top.drop(1).map { it.get("code").asText() }
        assertEquals(restCodes.sorted(), restCodes)
    }

    @Test
    fun `the response never contains report ids, user ids, or any personnel field`() {
        val area = givenRoutedArea()
        val admin = givenSuperAdmin()
        givenRoutedReport(area)
        val raw = summary(bearerFor(admin), areaId = area.areaId).body()

        listOf("reportId", "publicId", "userId", "assigneeServiceId", "assignedUserId", "deletedByUserId", "capability", "token", "adminVersion", "workflowVersion").forEach { forbidden ->
            assertFalse(raw.contains(forbidden), "response leaked a forbidden field: $forbidden")
        }
    }

    @Test
    fun `the summary response is never cached`() {
        val response = summary(bearerFor(givenSuperAdmin()))
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null))
    }

    @Test
    fun `the areas response is never cached either`() {
        val response = analyticsAreas(bearerFor(givenSuperAdmin()))
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null))
    }

    @Test
    fun `generatedAt is present and period echoes the resolved window and zone`() {
        val body = json(summary(bearerFor(givenSuperAdmin()), period = "TODAY"))
        assertTrue(body.has("generatedAt"))
        assertNull(body.get("period").get("zoneId").asText().takeIf { it != "Europe/Budapest" })
        assertEquals("TODAY", body.get("period").get("code").asText())
    }
}
