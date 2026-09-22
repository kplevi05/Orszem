package hu.orszembejelento.backend.reportworkflow.domain

import hu.orszembejelento.backend.scope.domain.AreaActor
import hu.orszembejelento.backend.scope.domain.AreaScopePolicy
import hu.orszembejelento.backend.scope.domain.AreaSnapshot

/**
 * Cross-phase invariant guard: would a *hypothetical* post-mutation scope still authorise
 * every one of a user's current open report assignments?
 *
 * Used only by Phase 6 user-management mutations that narrow a target's scope (area revoke,
 * global-access revoke) — never by anything in the report-workflow module itself, which
 * already re-validates the *current* scope fresh, under lock, at the moment it creates a
 * new assignment ([ClaimReportUseCase][hu.orszembejelento.backend.reportworkflow.application.ClaimReportUseCase],
 * [ReassignReportUseCase][hu.orszembejelento.backend.reportworkflow.application.ReassignReportUseCase]).
 *
 * Deliberately reuses [AreaScopePolicy.canAccessArea] rather than duplicating its rule: an
 * assignment is authorised exactly when the *hypothetical* actor described by
 * [postMutationActor] would currently be able to access the assignment's area — global
 * access covers it, or it is one of the actor's own explicit area grants, and the area
 * itself is ACTIVE (an assignment sitting in an already-inactive area is not this guard's
 * concern; the same [AreaScopePolicy] rule already treats that as out of scope for everyone
 * but SUPER_ADMIN, and a report's assignee is never SUPER_ADMIN).
 */
class AssignmentEligibilityGuard(private val areaScopePolicy: AreaScopePolicy = AreaScopePolicy()) {

    /**
     * True if at least one of [openAssignments] would no longer be authorised for
     * [postMutationActor] — the caller's signal to reject the mutation that would produce
     * that actor, rather than silently orphan the assignment.
     *
     * An assignment with a null [OpenAssignmentAreaSnapshot.serviceAreaId] — an open claim
     * on an UNCLASSIFIED report, only possible under the Nationwide KSH Settlement Fallback
     * policy — is never "outside scope" here: it was never inside an *area* scope to begin
     * with, so an area/global-access-narrowing mutation this guard exists to check can never
     * be the thing that invalidates it.
     */
    fun anyAssignmentOutsideScope(openAssignments: List<OpenAssignmentAreaSnapshot>, postMutationActor: AreaActor): Boolean =
        openAssignments.any { assignment ->
            val areaId = assignment.serviceAreaId ?: return@any false
            !areaScopePolicy.canAccessArea(postMutationActor, AreaSnapshot(areaId, assignment.serviceAreaActive))
        }
}
