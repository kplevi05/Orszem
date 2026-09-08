package hu.orszembejelento.app.report

import hu.orszembejelento.app.report.domain.LineAnswer
import hu.orszembejelento.app.report.domain.LineCoverage
import hu.orszembejelento.app.report.domain.RailwayLineOption
import hu.orszembejelento.app.report.domain.RailwayLineStep
import hu.orszembejelento.app.report.domain.RailwayLinesForSettlement
import hu.orszembejelento.app.report.domain.isResolved
import hu.orszembejelento.app.report.domain.railwayLineStepFor
import hu.orszembejelento.app.report.domain.resolvedRailwayLineId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** The exact §42 COMPLETE/PARTIAL UI matrix. */
class RailwayLineDecisionTest {

    private fun option(name: String = "1") = RailwayLineOption(UUID.randomUUID(), name, "Line $name")

    @Test
    fun `COMPLETE with zero items - no selector, sends null`() {
        val step = railwayLineStepFor(RailwayLinesForSettlement(LineCoverage.COMPLETE, emptyList()))
        assertTrue(step is RailwayLineStep.NoVerifiedCandidate)
        assertTrue(step.isResolved(LineAnswer.NotYetAnswered))
        assertNull(step.resolvedRailwayLineId(LineAnswer.NotYetAnswered))
    }

    @Test
    fun `PARTIAL with zero items - no selector, sends null, never claims no railway exists`() {
        val step = railwayLineStepFor(RailwayLinesForSettlement(LineCoverage.PARTIAL, emptyList()))
        assertTrue(step is RailwayLineStep.NoVerifiedCandidate)
        assertEquals(LineCoverage.PARTIAL, (step as RailwayLineStep.NoVerifiedCandidate).coverage)
    }

    @Test
    fun `COMPLETE with exactly one item - auto-displayed, still sends null, never required to select`() {
        val single = option()
        val step = railwayLineStepFor(RailwayLinesForSettlement(LineCoverage.COMPLETE, listOf(single)))
        assertTrue(step is RailwayLineStep.SingleInferred)
        assertEquals(single, (step as RailwayLineStep.SingleInferred).option)
        assertTrue(step.isResolved(LineAnswer.NotYetAnswered)) // no user action required
        assertNull(step.resolvedRailwayLineId(LineAnswer.NotYetAnswered)) // never invents routing client-side
    }

    @Test
    fun `PARTIAL with exactly one item - NEVER auto-selected, user must explicitly answer`() {
        val single = option()
        val step = railwayLineStepFor(RailwayLinesForSettlement(LineCoverage.PARTIAL, listOf(single)))
        assertTrue("a single PARTIAL candidate must require an explicit choice", step is RailwayLineStep.RequiresChoice)
        assertFalse(step.isResolved(LineAnswer.NotYetAnswered))
        assertTrue(step.isResolved(LineAnswer.Unsure))
        assertTrue(step.isResolved(LineAnswer.Chosen(single)))
    }

    @Test
    fun `COMPLETE with two or more items requires an explicit choice, no default first item`() {
        val a = option("1")
        val b = option("2")
        val step = railwayLineStepFor(RailwayLinesForSettlement(LineCoverage.COMPLETE, listOf(a, b)))
        assertTrue(step is RailwayLineStep.RequiresChoice)
        assertFalse(step.isResolved(LineAnswer.NotYetAnswered))
        assertNull(step.resolvedRailwayLineId(LineAnswer.NotYetAnswered))
    }

    @Test
    fun `choosing a specific option resolves to that option's id`() {
        val a = option("1")
        val b = option("2")
        val step = railwayLineStepFor(RailwayLinesForSettlement(LineCoverage.COMPLETE, listOf(a, b)))
        assertEquals(a.id, step.resolvedRailwayLineId(LineAnswer.Chosen(a)))
        assertEquals(b.id, step.resolvedRailwayLineId(LineAnswer.Chosen(b)))
    }

    @Test
    fun `choosing unsure resolves to null but is still a valid, resolved answer`() {
        val step = railwayLineStepFor(RailwayLinesForSettlement(LineCoverage.PARTIAL, listOf(option())))
        assertTrue(step.isResolved(LineAnswer.Unsure))
        assertNull(step.resolvedRailwayLineId(LineAnswer.Unsure))
    }

    @Test
    fun `loading and not-applicable states are never resolved`() {
        assertFalse(RailwayLineStep.Loading.isResolved(LineAnswer.NotYetAnswered))
        assertFalse(RailwayLineStep.NotApplicable.isResolved(LineAnswer.NotYetAnswered))
        assertFalse(RailwayLineStep.LoadFailed.isResolved(LineAnswer.NotYetAnswered))
    }
}
