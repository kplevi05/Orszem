package hu.orszembejelento.backend.areaadmin

import hu.orszembejelento.backend.areaadmin.support.AreaAdminTestSupport
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import java.util.UUID

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

    /**
     * Phase 13 forensic-recovery gap A: race 1 above already proves a Public submission
     * racing a line **move** (`AssignRailwayLineUseCase`). Nothing previously exercised a
     * submission racing a line **unassign** (`UnassignRailwayLineUseCase`) by name - the two
     * use cases are different code paths (unassign never re-locks a target area, never bumps
     * a second area's version, and the resulting routing outcome is `UNCLASSIFIED` rather
     * than "routed to a different area"), so this is not safely inferable from race 1.
     *
     * Uses the real Public submission production path (`submitReport`, exactly as
     * `PublicReportConcurrencyIT` does) and the real `UnassignRailwayLineUseCase` HTTP
     * endpoint, overlapped with a `CountDownLatch`-backed [runConcurrently] - never
     * `Thread.sleep`. `submitReport` holds [hu.orszembejelento.backend.common.ReferenceStateLock]
     * in its *shared* mode while it resolves and snapshots routing; `unassignLine` holds the
     * same key's *exclusive* mode for its whole transaction - so the two are fully linearised
     * by PostgreSQL itself, and this test proves the result is one of exactly the two
     * legitimate serialised orders, never a third value.
     */
    @Test
    fun `race 6 - a line unassign racing a Public submission on that line never produces a half-unassigned or corrupted snapshot`() {
        repeat(5) {
            val admin = adminBearer()
            val a = givenRoutedArea()

            val results = runConcurrently(2) { index ->
                if (index == 0) {
                    unassignLine(admin, a.lineId, a.areaId)
                } else {
                    submitReport(submitReportBody(settlementId = a.settlementId, railwayLineId = a.lineId))
                }
            }
            val unassignResult = results[0].getOrThrow()
            val submitResult = results[1].getOrThrow()

            // The unassign itself is never blocked or rejected by a racing submission - a
            // submission only ever takes the *shared* half of the lock.
            assertEquals(204, unassignResult.statusCode())
            assertEquals(null, currentAreaOfLine(a.lineId)) { "RailwayLine final mapping state: exactly unmapped" }
            assertEquals(0, mappingCount(a.lineId))

            assertEquals(201, submitResult.statusCode())
            val publicId = UUID.fromString(json(submitResult).get("reportId").asText())

            // Exactly one report, exactly one routing snapshot - no partial/duplicate rows
            // from either side of the race.
            assertEquals(1, reportRowCount(publicId))
            assertEquals(1, routingSnapshotCount(publicId))

            val snapshot = routingSnapshot(publicId)
            // Whichever side's transaction actually committed first, the snapshot is one of
            // exactly two legitimate, internally-consistent shapes - the database's own
            // `ck_report_routing_snapshots_coherence` CHECK constraint already forbids
            // anything else from having been committed at all, so this is a second,
            // behavioural confirmation of that same invariant, not a redundant one:
            if (snapshot.serviceAreaId != null) {
                // The submission's shared-lock snapshot was taken before the unassign's
                // exclusive-lock transaction committed - still routed to the (soon to be
                // former) area, exactly as it legitimately was at that instant.
                assertEquals(a.areaId, snapshot.serviceAreaId)
                assertEquals("ROUTED", snapshot.routingStatus)
                assertEquals(null, snapshot.routingReason)
                assertEquals(a.lineId, snapshot.resolvedRailwayLineId)
            } else {
                // The unassign committed first - the submission correctly falls back to the
                // existing, unchanged Phase 3 UNCLASSIFIED/RAILWAY_LINE_UNASSIGNED outcome,
                // never a null/half-resolved value.
                assertEquals("UNCLASSIFIED", snapshot.routingStatus)
                assertEquals("RAILWAY_LINE_UNASSIGNED", snapshot.routingReason)
                assertEquals(a.lineId, snapshot.resolvedRailwayLineId)
            }
        }
    }

    /**
     * Phase 13 forensic-recovery gap B (part 1): race 3 above proves deactivation racing an
     * **assignment into** an area. It does not prove deactivation racing the **unassign of its
     * own currently-mapped line** - the operation that would actually make the area eligible
     * for deactivation in the first place, which is a materially different scenario (here
     * deactivation's own blocker is the thing in flight, not an unrelated third party).
     *
     * Both [DeactivateServiceAreaUseCase][hu.orszembejelento.backend.areaadmin.application.DeactivateServiceAreaUseCase]
     * and [UnassignRailwayLineUseCase][hu.orszembejelento.backend.areaadmin.application.UnassignRailwayLineUseCase]
     * take the *exclusive* half of [hu.orszembejelento.backend.common.ReferenceStateLock] for
     * their whole transaction, so the two never truly interleave - PostgreSQL fully
     * serialises them into one of exactly two orders. Both use cases also bump the area's own
     * `admin_version` (deactivation directly; unassign via `bumpAdminVersion` on the area it
     * unassigns from), and deactivation captures its `expectedVersion` before the race - so
     * this test proves a real, previously-undocumented finding: deactivation can **never**
     * win this specific race, in *either* order. If unassign commits first, deactivation's
     * stale version is rejected (`SERVICE_AREA_STATE_CHANGED`); if deactivation's transaction
     * runs first, it still observes the not-yet-removed mapping and is rejected on its own
     * blocker check (`SERVICE_AREA_HAS_RAILWAY_LINES`), after which the unassign proceeds
     * and always succeeds. No committed state ever has an INACTIVE area still holding a
     * mapped line, and neither order deadlocks.
     */
    @Test
    fun `race 7 - area deactivation racing the unassignment of its only mapped line never leaves an inactive area holding a mapped line`() {
        repeat(5) {
            val admin = adminBearer()
            val area = givenRoutedArea()

            val results = runConcurrently(2) { index ->
                if (index == 0) deactivateArea(admin, area.areaId, 0) else unassignLine(admin, area.lineId, area.areaId)
            }.map { it.getOrThrow() }
            val deactivateResult = results[0]
            val unassignResult = results[1]

            assertEquals(204, unassignResult.statusCode()) { "unassigning the area's own line is never blocked by a racing deactivation attempt" }
            assertEquals(409, deactivateResult.statusCode()) { "deactivation racing the very operation that would make it eligible never itself wins that race" }
            assertTrue(errorCode(deactivateResult) in setOf("SERVICE_AREA_HAS_RAILWAY_LINES", "SERVICE_AREA_STATE_CHANGED"))

            assertEquals("ACTIVE", areaStatus(area.areaId)) { "forbidden state: an INACTIVE area still holding a mapped line" }
            assertEquals(0, mappingCount(area.lineId)) { "the unassign itself always commits regardless of interleaving" }
            assertEquals(null, currentAreaOfLine(area.lineId))
        }
    }

    /**
     * Phase 13 forensic-recovery gap B (part 2): the same reasoning as race 7, for a **move**
     * of the area's only mapped line to a different target rather than a plain unassign -
     * `AssignRailwayLineUseCase`'s move path bumps *both* the source and target area's
     * versions (see its own KDoc), so this is worth proving independently rather than
     * assuming it collapses to the unassign case.
     */
    @Test
    fun `race 8 - area deactivation racing a move of its only mapped line elsewhere never leaves an inactive area holding a mapped line`() {
        repeat(5) {
            val admin = adminBearer()
            val source = givenRoutedArea()
            val target = givenRoutedArea()

            val results = runConcurrently(2) { index ->
                if (index == 0) deactivateArea(admin, source.areaId, 0) else assignLine(admin, source.lineId, target.areaId, source.areaId)
            }.map { it.getOrThrow() }
            val deactivateResult = results[0]
            val moveResult = results[1]

            assertEquals(204, moveResult.statusCode()) { "moving the source area's own line away is never blocked by a racing deactivation attempt on the source" }
            assertEquals(409, deactivateResult.statusCode()) { "deactivation racing the very operation that would make it eligible never itself wins that race" }
            assertTrue(errorCode(deactivateResult) in setOf("SERVICE_AREA_HAS_RAILWAY_LINES", "SERVICE_AREA_STATE_CHANGED"))

            assertEquals("ACTIVE", areaStatus(source.areaId)) { "forbidden state: an INACTIVE area still holding a mapped line" }
            assertEquals(target.areaId, currentAreaOfLine(source.lineId)) { "the move itself always commits regardless of interleaving" }
            assertEquals(1, mappingCount(source.lineId))
        }
    }
}
