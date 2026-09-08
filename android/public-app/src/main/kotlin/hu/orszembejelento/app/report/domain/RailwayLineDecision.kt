package hu.orszembejelento.app.report.domain

import java.util.UUID

enum class LineCoverage { COMPLETE, PARTIAL }

/** One railway line with a verified relation to the selected settlement. */
data class RailwayLineOption(val id: UUID, val code: String, val displayName: String)

/** The backend's `{coverage, items}` contract for one settlement (ADR 0007). */
data class RailwayLinesForSettlement(val coverage: LineCoverage, val items: List<RailwayLineOption>)

/**
 * The COMPLETE/PARTIAL railway-line UX matrix (Phase 5 brief §42), computed once the
 * railway-line lookup for a settlement resolves. `coverage` is never used to infer
 * completeness from item count - it is read directly from the backend response.
 */
sealed class RailwayLineStep {
    /** No settlement selected yet, or its line list has not been requested. */
    data object NotApplicable : RailwayLineStep()
    data object Loading : RailwayLineStep()
    data object LoadFailed : RailwayLineStep()

    /**
     * Zero verified candidates, under either coverage. The UI explains that nothing is
     * currently on record - never that no railway physically exists (ADR 0006/0007's own
     * distinction, carried into the client). No selector; the report submits with
     * `railwayLineId = null`.
     */
    data class NoVerifiedCandidate(val coverage: LineCoverage) : RailwayLineStep()

    /**
     * Exactly one COMPLETE candidate. May be displayed for reassurance, but the user is
     * never required to select it, and the client does not invent routing: `railwayLineId`
     * is still sent as `null`, letting the backend's own COMPLETE inference resolve it
     * (§42 - "prefer sending null unless a clearly stronger reason exists").
     */
    data class SingleInferred(val coverage: LineCoverage, val option: RailwayLineOption) : RailwayLineStep()

    /**
     * Two-or-more COMPLETE candidates, or one-or-more PARTIAL candidates. The user must
     * explicitly pick one, or explicitly say they are unsure - never a default first item,
     * and PARTIAL coverage never auto-selects even a single candidate.
     */
    data class RequiresChoice(val coverage: LineCoverage, val options: List<RailwayLineOption>) : RailwayLineStep()
}

fun railwayLineStepFor(response: RailwayLinesForSettlement): RailwayLineStep = when {
    response.items.isEmpty() -> RailwayLineStep.NoVerifiedCandidate(response.coverage)
    response.coverage == LineCoverage.COMPLETE && response.items.size == 1 ->
        RailwayLineStep.SingleInferred(response.coverage, response.items.single())
    else -> RailwayLineStep.RequiresChoice(response.coverage, response.items)
}

/** What the user has answered for a [RailwayLineStep.RequiresChoice] step. */
sealed class LineAnswer {
    data object NotYetAnswered : LineAnswer()
    data class Chosen(val option: RailwayLineOption) : LineAnswer()
    /** "Nem tudom / nem vagyok biztos benne" (COMPLETE) or "Nem tudom / másik vonal" (PARTIAL) - explicit, valid, sends null. */
    data object Unsure : LineAnswer()
}

/** The `railwayLineId` this step contributes to the submission, given the current answer. */
fun RailwayLineStep.resolvedRailwayLineId(answer: LineAnswer): UUID? = when (this) {
    is RailwayLineStep.RequiresChoice -> (answer as? LineAnswer.Chosen)?.option?.id
    else -> null
}

/** Whether Step 1 may proceed to Step 2 given the current railway-line state. */
fun RailwayLineStep.isResolved(answer: LineAnswer): Boolean = when (this) {
    RailwayLineStep.NotApplicable, RailwayLineStep.Loading, RailwayLineStep.LoadFailed -> false
    is RailwayLineStep.NoVerifiedCandidate, is RailwayLineStep.SingleInferred -> true
    is RailwayLineStep.RequiresChoice -> answer != LineAnswer.NotYetAnswered
}
