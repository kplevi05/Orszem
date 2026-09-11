package hu.orszembejelento.service.reports.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.moderation.data.ModerationRepository
import hu.orszembejelento.service.reports.data.ReportDetailResponse
import hu.orszembejelento.service.reports.data.ReportWorkflowRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class WorkflowMutationKind { CLAIM, RETURN, CLOSE, REASSIGN }

/**
 * How a moderation delete finished (correction pass §3) - both outcomes mean the report is
 * gone from ordinary workflow and the screen navigates away, but they are **not** the same
 * event: [DELETED_NOW] is this request's own doing and earns the normal success copy;
 * [ALREADY_DELETED] means someone else's delete (or an earlier attempt by this same actor)
 * already committed, so showing "A bejelentés törölve." here would misattribute a
 * deletion this call never performed. The caller picks the copy; this type only carries the
 * fact.
 */
enum class ModerationDeleteOutcome { DELETED_NOW, ALREADY_DELETED }

/**
 * One report's full workflow detail and the four mutations (brief §28-34).
 *
 * Every mutation follows the same shape: send [ReportDetailResponse.workflowVersion] as
 * `expectedVersion`, and on **any** rejection - stale version, already-claimed,
 * already-archived, forbidden, whatever the stable code - silently re-fetch the current
 * server state instead of guessing or blindly retrying the mutation (brief §24/§77). The
 * committed response from a successful mutation replaces local state directly; nothing here
 * ever synthesizes a guessed result (brief §22).
 */
class ReportDetailViewModel(
    private val publicReportId: String,
    private val repository: ReportWorkflowRepository,
    private val onSessionEnded: () -> Unit,
    // Null for a SERVICE_USER (mirrors userManagementRepository's identical null-for-SERVICE_USER
    // shape) - moderation deletion is a MODERATOR/SUPER_ADMIN-only action (brief §2).
    private val moderationRepository: ModerationRepository? = null,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val detail: ReportDetailResponse? = null,
        val error: ApiResult<Nothing>? = null,
        val mutationInFlight: Boolean = false,
        val mutationError: ApiResult<Nothing>? = null,
        val lastMutation: WorkflowMutationKind? = null,
        // Phase 9 moderation-delete state (brief §42/§43) - deliberately separate from the
        // ordinary mutation fields above: a successful (or REPORT_ALREADY_DELETED) delete
        // means the report is now hidden from ordinary detail, so the screen navigates away
        // instead of trying to show a refreshed detail the way every other mutation does.
        // [moderationDeleteOutcome] carries *which* of those two cases it was, so the caller
        // never shows the "I just deleted it" copy for a delete this request did not perform
        // (correction pass §3).
        val moderationDeleteInFlight: Boolean = false,
        val moderationDeleteError: ApiResult<Nothing>? = null,
        val moderationDeleteOutcome: ModerationDeleteOutcome? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true, error = null) }
            when (val result = repository.detail(publicReportId)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, detail = result.value, error = null) }
                ApiResult.SessionEnded -> {
                    onSessionEnded()
                    _state.update { it.copy(loading = false) }
                }
                else -> _state.update { current ->
                    @Suppress("UNCHECKED_CAST")
                    current.copy(loading = false, error = if (silent) current.error else result as ApiResult<Nothing>)
                }
            }
        }
    }

    fun claim() = mutate(WorkflowMutationKind.CLAIM) { version -> repository.claim(publicReportId, version) }
    fun returnToNew() = mutate(WorkflowMutationKind.RETURN) { version -> repository.returnToNew(publicReportId, version) }
    fun close() = mutate(WorkflowMutationKind.CLOSE) { version -> repository.close(publicReportId, version) }
    fun reassign(targetServiceId: String) =
        mutate(WorkflowMutationKind.REASSIGN) { version -> repository.reassign(publicReportId, version, targetServiceId) }

    fun consumeToast() {
        _state.update { it.copy(lastMutation = null) }
    }

    fun consumeMutationError() {
        _state.update { it.copy(mutationError = null) }
    }

    /**
     * Moderation-delete (brief §38-43). Single-flight, never blindly retried. On success -
     * or on the specific REPORT_ALREADY_DELETED conflict, which means the report is gone
     * either way - [UiState.moderationDeleteOutcome] is set and the screen navigates away
     * rather than trying to display a now-hidden report's detail. The two cases are
     * deliberately distinct outcomes (correction pass §3): `REPORT_ALREADY_DELETED` means
     * this call never performed a deletion - someone else's delete already committed - so
     * it must never be presented with the "A bejelentés törölve." success copy, which would
     * misattribute a deletion this request did not do. Any other rejection (a stale
     * [ReportDetailResponse.workflowVersion], scope loss) surfaces the natural error copy
     * and silently refreshes current state instead.
     */
    fun deleteReport(reason: String) {
        val detail = _state.value.detail ?: return
        val moderation = moderationRepository ?: return
        if (_state.value.moderationDeleteInFlight) return

        viewModelScope.launch {
            _state.update { it.copy(moderationDeleteInFlight = true, moderationDeleteError = null) }
            when (val result = moderation.delete(publicReportId, detail.workflowVersion, reason)) {
                is ApiResult.Success -> _state.update {
                    it.copy(moderationDeleteInFlight = false, moderationDeleteOutcome = ModerationDeleteOutcome.DELETED_NOW)
                }
                ApiResult.SessionEnded -> {
                    onSessionEnded()
                    _state.update { it.copy(moderationDeleteInFlight = false) }
                }
                is ApiResult.Failure -> if (result.code == "REPORT_ALREADY_DELETED") {
                    _state.update {
                        it.copy(moderationDeleteInFlight = false, moderationDeleteOutcome = ModerationDeleteOutcome.ALREADY_DELETED)
                    }
                } else {
                    _state.update { it.copy(moderationDeleteInFlight = false, moderationDeleteError = result) }
                    load(silent = true)
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    _state.update { it.copy(moderationDeleteInFlight = false, moderationDeleteError = result as ApiResult<Nothing>) }
                }
            }
        }
    }

    fun consumeModerationDeleteError() {
        _state.update { it.copy(moderationDeleteError = null) }
    }

    private fun mutate(kind: WorkflowMutationKind, call: suspend (Long) -> ApiResult<ReportDetailResponse>) {
        val version = _state.value.detail?.workflowVersion ?: return
        if (_state.value.mutationInFlight) return // single-flight per screen (brief §76)

        viewModelScope.launch {
            _state.update { it.copy(mutationInFlight = true, mutationError = null) }
            when (val result = call(version)) {
                is ApiResult.Success -> _state.update {
                    it.copy(mutationInFlight = false, detail = result.value, lastMutation = kind)
                }
                ApiResult.SessionEnded -> {
                    onSessionEnded()
                    _state.update { it.copy(mutationInFlight = false) }
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    _state.update { it.copy(mutationInFlight = false, mutationError = result as ApiResult<Nothing>) }
                    // Never blindly retry the mutation (brief §24/§77) - always refresh instead.
                    load(silent = true)
                }
            }
        }
    }
}
