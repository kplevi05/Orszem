package hu.orszembejelento.backend.areaadmin

import hu.orszembejelento.backend.areaadmin.support.AreaAdminTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Phase 10 brief §25/§26/§28/§31/§32/§70/§71: real PostgreSQL concurrency proving the shared
 * routing advisory lock ([hu.orszembejelento.backend.common.ReferenceStateLock]) genuinely
 * serialises Phase 10's routing-affecting admin mutations against a Public submission and
 * against each other - never a half-moved configuration, never a duplicate mapping, never a
 * partial area-version increment.
 *
 * These races double as the rollback-invariant proof brief §42 asks for (mirroring
 * [hu.orszembejelento.backend.moderation.ModerationRollbackInvariantIT]'s own reasoning):
 * a loser here is left with **zero** trace by real database contention, which is a stronger
 * guarantee than "a single-threaded fault-injected partial write is undone" and exercises the
 * exact same commit/rollback machinery.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class AreaAdminConcurrencyIT : AreaAdminTestSupport() {

    @Test
    fun `race 1 - a line move racing a Public submission on that line never produces a half-moved or corrupted snapshot`() {
        repeat(5) {
            val admin = adminBearer()
            val a = givenRoutedArea()
            val b = givenRoutedArea()

            val results = runConcurrently(2) { index ->
                if (index == 0) {
                    assignLine(admin, a.lineId, b.areaId, a.areaId)
                } else {
                    submitReport(submitReportBody(settlementId = a.settlementId, railwayLineId = a.lineId))
                }
            }
            val moveResult = results[0].getOrThrow()
            val submitResult = results[1].getOrThrow()

            assertEquals(204, moveResult.statusCode())
            assertEquals(b.areaId, currentAreaOfLine(a.lineId))
            assertEquals(1, mappingCount(a.lineId))

            assertEquals(201, submitResult.statusCode())
            val publicId = java.util.UUID.fromString(json(submitResult).get("reportId").asText())
            val snapshotArea = routingSnapshotArea(publicId)
            // Whichever side won the lock first, the submission must resolve to a REAL,
            // currently-consistent configuration - either the pre-move area A or the
            // post-move area B - never null, never a third value, never a state that
            // existed only transiently mid-transaction.
            assertTrue(snapshotArea == a.areaId || snapshotArea == b.areaId) {
                "snapshot must be exactly A or B, was $snapshotArea"
            }
        }
    }

    @Test
    fun `race 2 - two moves of the same line to different targets leave exactly one coherent winner`() {
        val admin = adminBearer()
        val source = givenRoutedArea()
        val targetOne = givenRoutedArea()
        val targetTwo = givenRoutedArea()

        val results = runConcurrently(2) { index ->
            if (index == 0) {
                assignLine(admin, source.lineId, targetOne.areaId, source.areaId)
            } else {
                assignLine(admin, source.lineId, targetTwo.areaId, source.areaId)
            }
        }.map { it.getOrThrow() }

        val winners = results.count { it.statusCode() == 204 }
        val losers = results.count { it.statusCode() == 409 }
        assertEquals(1, winners) { "exactly one coherent winner" }
        assertEquals(1, losers)
        assertEquals("RAILWAY_LINE_ASSIGNMENT_CHANGED", errorCode(results.first { it.statusCode() == 409 }))

        val finalArea = currentAreaOfLine(source.lineId)
        assertTrue(finalArea == targetOne.areaId || finalArea == targetTwo.areaId)
        assertEquals(1, mappingCount(source.lineId)) { "never two committed mappings for the same line" }
    }

    @Test
    fun `race 3 - area deactivation racing a line assignment into it never ends with an inactive area holding a committed line`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        // Clear the fixture's own line so the area starts eligible for deactivation.
        check(unassignLine(admin, area.lineId, area.areaId).statusCode() == 204)
        val freeLine = insertLine("L${(100000..999999).random()}")

        val results = runConcurrently(2) { index ->
            if (index == 0) deactivateArea(admin, area.areaId, 1) else assignLine(admin, freeLine, area.areaId, null)
        }.map { it.getOrThrow() }
        val deactivateResult = results[0]
        val assignResult = results[1]

        val finalStatus = areaStatus(area.areaId)
        val finalMapping = currentAreaOfLine(freeLine)

        if (deactivateResult.statusCode() == 200) {
            // Deactivation won: the area is inactive, and the racing assignment must have
            // been rejected against that now-inactive target - the line stays unmapped.
            assertEquals("INACTIVE", finalStatus)
            assertEquals(409, assignResult.statusCode())
            assertEquals(null, finalMapping)
        } else {
            // The assignment won (it ran first, entirely, under the exclusive routing lock):
            // the area still holds a mapped line, so deactivation is correctly rejected -
            // never silently deactivated out from under a line that was just assigned to it.
            // The exact 409 code depends on which of deactivation's own guards fires first:
            // because assignment always bumps this area's adminVersion too (brief §8), and
            // deactivation checks its own `expectedVersion` before the line-count blocker,
            // the deactivation call (built with the version captured before the race) is
            // just as likely to see SERVICE_AREA_STATE_CHANGED as SERVICE_AREA_HAS_RAILWAY_LINES
            // - both are legitimate rejections proving the same invariant.
            assertEquals(204, assignResult.statusCode())
            assertEquals(409, deactivateResult.statusCode())
            assertTrue(errorCode(deactivateResult) in setOf("SERVICE_AREA_HAS_RAILWAY_LINES", "SERVICE_AREA_STATE_CHANGED"))
            assertEquals("ACTIVE", finalStatus)
            assertEquals(area.areaId, finalMapping)
        }
    }

    @Test
    fun `race 4 - area deactivation racing moderation-delete of its only open report never deadlocks and never reroutes`() {
        val admin = adminBearer()
        val area = givenRoutedArea()
        // The report must be created *while the line is still mapped*, so it actually routes
        // into `area` - only then is the line unassigned, leaving the open report as the
        // area's one remaining deactivation blocker.
        val report = givenRoutedReport(area)
        check(unassignLine(admin, area.lineId, area.areaId).statusCode() == 204)

        val results = runConcurrently(2) { index ->
            if (index == 0) deactivateArea(admin, area.areaId, 1) else delete(admin, report.publicId, 0)
        }.map { it.getOrThrow() }
        val deactivateResult = results[0]
        val deleteResult = results[1]

        // Both outcomes are acceptable per brief §32 - the only hard requirements are no
        // deadlock (already implied by both calls returning at all) and no rerouting.
        assertEquals(area.areaId, routingSnapshotArea(report.publicId)) { "moderation delete must never reroute a report, whichever side wins" }
        assertTrue(deleteResult.statusCode() == 204) { "the delete itself is never blocked by area administration - eventually succeeds either way: ${deleteResult.statusCode()}" }
        assertTrue(deactivateResult.statusCode() == 200 || deactivateResult.statusCode() == 409)
    }

    @Test
    fun `race 5 - two stale mutations sharing the same expectedVersion leave exactly one winner and one 409`() {
        val admin = adminBearer()
        val area = givenRoutedArea()

        val results = runConcurrently(2) { index ->
            renameArea(admin, area.areaId, 0, "Verseny atnevezes $index ${System.nanoTime()}")
        }.map { it.getOrThrow() }

        assertEquals(1, results.count { it.statusCode() == 200 })
        assertEquals(1, results.count { it.statusCode() == 409 })
        assertEquals("SERVICE_AREA_STATE_CHANGED", errorCode(results.first { it.statusCode() == 409 }))
        assertEquals(1L, areaAdminVersion(area.areaId)) { "exactly one version increment from the single winning rename" }
    }
}
