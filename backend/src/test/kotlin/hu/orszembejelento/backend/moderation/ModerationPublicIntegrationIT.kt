package hu.orszembejelento.backend.moderation

import hu.orszembejelento.backend.moderation.support.ModerationTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Public status mapping while a report is currently moderation-deleted, and after a restore
 * (Phase 9 brief §14/§15/§54, FROZEN). The Public response shape is otherwise untouched, and
 * carries no hint that moderation exists at all.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class ModerationPublicIntegrationIT : ModerationTestSupport() {

    @Test
    fun `a currently-deleted NEW report shows Public CLOSED, then RECEIVED again once restored`() {
        val area = givenRoutedArea()
        val created = givenCreatedReport(settlementId = area.settlementId, railwayLineId = area.lineId)
        val admin = adminBearer()

        check(json(getPublicReport(created.publicId, created.credential)).get("status").asText() == "RECEIVED")

        check(delete(admin, created.publicId, 0).statusCode() == 204)
        check(json(getPublicReport(created.publicId, created.credential)).get("status").asText() == "CLOSED")

        check(restore(admin, created.publicId, 1).statusCode() == 204)
        check(json(getPublicReport(created.publicId, created.credential)).get("status").asText() == "RECEIVED")
    }

    @Test
    fun `a currently-deleted IN_PROGRESS report shows Public CLOSED, then RECEIVED - not PROCESSING - once restored`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val created = givenCreatedReport(settlementId = area.settlementId, railwayLineId = area.lineId)
        check(claim(bearerFor(user), created.publicId, 0).statusCode() == 200)
        check(json(getPublicReport(created.publicId, created.credential)).get("status").asText() == "PROCESSING")

        val admin = adminBearer()
        check(delete(admin, created.publicId, 1).statusCode() == 204)
        check(json(getPublicReport(created.publicId, created.credential)).get("status").asText() == "CLOSED")

        // Restored IN_PROGRESS -> NEW/unassigned (brief §9), so Public shows RECEIVED, never
        // PROCESSING again - the prior assignment is never resurrected.
        check(restore(admin, created.publicId, 2).statusCode() == 204)
        check(json(getPublicReport(created.publicId, created.credential)).get("status").asText() == "RECEIVED")
    }

    @Test
    fun `a currently-deleted ARCHIVED report shows Public CLOSED both before and after restore`() {
        val area = givenRoutedArea()
        val user = givenServiceUser()
        grantArea(user.id, area.areaId)
        val created = givenCreatedReport(settlementId = area.settlementId, railwayLineId = area.lineId)
        check(claim(bearerFor(user), created.publicId, 0).statusCode() == 200)
        check(close(bearerFor(user), created.publicId, 1).statusCode() == 200)
        check(json(getPublicReport(created.publicId, created.credential)).get("status").asText() == "CLOSED")

        val admin = adminBearer()
        check(delete(admin, created.publicId, 2).statusCode() == 204)
        check(json(getPublicReport(created.publicId, created.credential)).get("status").asText() == "CLOSED")

        check(restore(admin, created.publicId, 3).statusCode() == 204)
        check(json(getPublicReport(created.publicId, created.credential)).get("status").asText() == "CLOSED")
    }

    @Test
    fun `the Public response for a deleted report carries no moderation metadata at all`() {
        val area = givenRoutedArea()
        val created = givenCreatedReport(settlementId = area.settlementId, railwayLineId = area.lineId)
        val admin = adminBearer()
        check(delete(admin, created.publicId, 0, "TROLL_OR_FALSE_REPORT").statusCode() == 204)

        val response = getPublicReport(created.publicId, created.credential)
        check(response.statusCode() == 200) { response.body() }
        val bodyText = response.body()

        for (leak in listOf(
            "reason", "TROLL_OR_FALSE_REPORT", "deletedBy", "deletedAt", "moderation",
            "episode", "statusBeforeDelete", "restoreTargetStatus", "workflowVersion",
        )) {
            check(!bodyText.contains(leak, ignoreCase = true)) { "Public response leaked '$leak': $bodyText" }
        }
        // Confirms this is the same, unchanged response shape - not a stripped-down variant.
        val body = json(response)
        check(body.get("reportId").asText() == created.publicId.toString())
        check(body.has("settlement") && body.has("category") && body.has("eventType"))
    }

    @Test
    fun `the Public response shape for a deleted report is identical to a normal one, field for field`() {
        val area = givenRoutedArea()
        val deleted = givenCreatedReport(settlementId = area.settlementId, railwayLineId = area.lineId)
        val normal = givenCreatedReport(settlementId = area.settlementId, railwayLineId = area.lineId)
        val admin = adminBearer()
        check(delete(admin, deleted.publicId, 0).statusCode() == 204)

        @Suppress("UNCHECKED_CAST")
        val deletedFields = (objectMapper.readValue(getPublicReport(deleted.publicId, deleted.credential).body(), Map::class.java) as Map<String, Any?>).keys
        @Suppress("UNCHECKED_CAST")
        val normalFields = (objectMapper.readValue(getPublicReport(normal.publicId, normal.credential).body(), Map::class.java) as Map<String, Any?>).keys
        check(deletedFields == normalFields) { "field sets differ: deleted=$deletedFields normal=$normalFields" }
    }
}
