package hu.orszembejelento.app.ui.newreport

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.orszembejelento.app.location.LocationAssist
import hu.orszembejelento.app.report.data.CatalogRepository
import hu.orszembejelento.app.report.data.CatalogResult
import hu.orszembejelento.app.report.data.ReferenceRepository
import hu.orszembejelento.app.report.data.RailwayLineLookupResult
import hu.orszembejelento.app.report.data.ReportDisplaySnapshot
import hu.orszembejelento.app.report.data.ReportRepository
import hu.orszembejelento.app.report.data.SettlementOption
import hu.orszembejelento.app.report.data.SettlementSearchResult
import hu.orszembejelento.app.report.data.SubmitOutcome
import hu.orszembejelento.app.report.data.network.ApiErrorCode
import hu.orszembejelento.app.report.domain.LineAnswer
import hu.orszembejelento.app.report.domain.RailwayLineOption
import hu.orszembejelento.app.report.domain.RailwayLineStep
import hu.orszembejelento.app.report.domain.RailwayLinesForSettlement
import hu.orszembejelento.app.report.domain.ReportDraft
import hu.orszembejelento.app.report.domain.railwayLineStepFor
import hu.orszembejelento.app.report.domain.resolvedRailwayLineId
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Minimum query length the backend accepts - mirrors `SettlementQueryTooShortException`. */
private const val MIN_SETTLEMENT_QUERY_LENGTH = 2
private const val SETTLEMENT_SEARCH_DEBOUNCE_MS = 300L

/**
 * Owns the whole two-step report flow (Phase 5 brief §4, §40-45).
 *
 * Settlement search and railway-line lookup each use a single replaceable [Job]: starting
 * a new search/lookup cancels whatever the previous one was doing, which is what makes a
 * stale, slow response impossible to apply after a newer request has started (§40 "cancel
 * stale requests", §43 "ignore a stale line-list response").
 */
class NewReportViewModel(
    private val reportRepository: ReportRepository,
    private val catalogRepository: CatalogRepository,
    private val referenceRepository: ReferenceRepository,
    private val locationAssist: LocationAssist,
) : ViewModel() {

    private val _state = MutableStateFlow(NewReportUiState())
    val state: StateFlow<NewReportUiState> = _state.asStateFlow()

    private var settlementSearchJob: Job? = null
    private var lineLookupJob: Job? = null
    private var locateJob: Job? = null

    // -------------------------------------------------------------------- Step 1

    fun onOccurredAtChanged(instant: Instant) {
        _state.update { it.copy(occurredAt = instant) }
    }

    fun onTrainIdentifierChanged(value: String) {
        _state.update { it.copy(trainIdentifierInput = value) }
    }

    /** Editing the settlement text after a selection clears both the selection and any line decision (§40). */
    fun onSettlementQueryChanged(query: String) {
        _state.update {
            it.copy(
                settlementQuery = query,
                selectedSettlement = null,
                settlementResults = if (query.length < MIN_SETTLEMENT_QUERY_LENGTH) emptyList() else it.settlementResults,
                lineStep = RailwayLineStep.NotApplicable,
                lineAnswer = LineAnswer.NotYetAnswered,
            )
        }
        settlementSearchJob?.cancel()
        lineLookupJob?.cancel()
        if (query.length < MIN_SETTLEMENT_QUERY_LENGTH) {
            _state.update { it.copy(settlementSearching = false, settlementResults = emptyList()) }
            return
        }
        settlementSearchJob = viewModelScope.launch {
            delay(SETTLEMENT_SEARCH_DEBOUNCE_MS)
            _state.update { it.copy(settlementSearching = true) }
            when (val result = referenceRepository.searchSettlements(query)) {
                is SettlementSearchResult.Loaded -> _state.update {
                    it.copy(settlementSearching = false, settlementResults = result.settlements)
                }
                SettlementSearchResult.Failed -> _state.update {
                    it.copy(settlementSearching = false, settlementResults = emptyList())
                }
            }
        }
    }

    fun onSettlementSelected(settlement: SettlementOption) {
        lineLookupJob?.cancel()
        _state.update {
            it.copy(
                selectedSettlement = settlement,
                settlementQuery = settlement.name,
                settlementResults = emptyList(),
                lineStep = RailwayLineStep.Loading,
                lineAnswer = LineAnswer.NotYetAnswered,
            )
        }
        val requestedFor = settlement.id
        lineLookupJob = viewModelScope.launch {
            val result = referenceRepository.railwayLinesOfSettlement(requestedFor)
            // A settlement change while this was in flight already replaced this job with
            // a new one (cancelling this one) - but as a second, cheap guard, only apply
            // the result if it is still for the currently-selected settlement.
            if (_state.value.selectedSettlement?.id != requestedFor) return@launch
            _state.update {
                it.copy(
                    lineStep = when (result) {
                        is RailwayLineLookupResult.Loaded -> railwayLineStepFor(result.response)
                        RailwayLineLookupResult.Failed -> RailwayLineStep.LoadFailed
                    },
                )
            }
        }
    }

    fun onLineOptionChosen(option: RailwayLineOption) {
        _state.update { it.copy(lineAnswer = LineAnswer.Chosen(option)) }
    }

    fun onLineUnsure() {
        _state.update { it.copy(lineAnswer = LineAnswer.Unsure) }
    }

    fun onLocateMeRequested() {
        if (!locationAssist.locationServicesEnabled()) {
            _state.update { it.copy(locateStatus = LocateStatus.SERVICES_DISABLED) }
            return
        }
        locateJob?.cancel()
        _state.update { it.copy(locateStatus = LocateStatus.LOCATING) }
        locateJob = viewModelScope.launch {
            val location: Location? = locationAssist.currentLocation()
            if (location == null) {
                _state.update { it.copy(locateStatus = LocateStatus.FAILED) }
                return@launch
            }
            val hint = locationAssist.reverseGeocodeHint(location)
            if (hint == null) {
                _state.update { it.copy(locateStatus = LocateStatus.FAILED) }
                return@launch
            }
            _state.update { it.copy(locateStatus = LocateStatus.IDLE) }
            // Feeds the normal server-backed search - never selects a settlement by itself.
            onSettlementQueryChanged(hint)
        }
    }

    /** Called by the UI once it has determined the location permission was denied (not "permanently"). */
    fun onLocationPermissionDenied(permanently: Boolean) {
        _state.update {
            it.copy(locateStatus = if (permanently) LocateStatus.PERMISSION_PERMANENTLY_DENIED else LocateStatus.PERMISSION_DENIED)
        }
    }

    fun onLocateDialogDismissed() {
        _state.update { it.copy(locateStatus = LocateStatus.IDLE) }
    }

    fun onProceedToStep2() {
        if (!_state.value.step1Valid) return
        _state.update { it.copy(step = ReportStep.ESEMENY) }
        loadCatalogIfNeeded()
    }

    fun onBackToStep1() {
        _state.update { it.copy(step = ReportStep.ALAPADATOK) }
    }

    // -------------------------------------------------------------------- Step 2

    private fun loadCatalogIfNeeded() {
        if (_state.value.catalog.isNotEmpty() || _state.value.catalogLoading) return
        viewModelScope.launch {
            _state.update { it.copy(catalogLoading = true, catalogFailed = false) }
            when (val result = catalogRepository.catalog()) {
                is CatalogResult.Loaded -> _state.update {
                    it.copy(catalogLoading = false, catalog = result.categories, catalogFailed = false)
                }
                CatalogResult.Failed -> _state.update { it.copy(catalogLoading = false, catalogFailed = true) }
            }
        }
    }

    fun onRetryCatalog() {
        viewModelScope.launch {
            _state.update { it.copy(catalogLoading = true, catalogFailed = false) }
            when (val result = catalogRepository.catalog(forceRefresh = true)) {
                is CatalogResult.Loaded -> _state.update {
                    it.copy(catalogLoading = false, catalog = result.categories, catalogFailed = false)
                }
                CatalogResult.Failed -> _state.update { it.copy(catalogLoading = false, catalogFailed = true) }
            }
        }
    }

    fun onCategorySelected(categoryCode: String) {
        _state.update { it.copy(selectedCategoryCode = categoryCode, selectedEventTypeCode = null) }
    }

    fun onEventTypeSelected(eventTypeCode: String) {
        _state.update { it.copy(selectedEventTypeCode = eventTypeCode) }
    }

    // -------------------------------------------------------------------- Submission

    fun onSubmit() {
        val current = _state.value
        val settlement = current.selectedSettlement ?: return
        val eventTypeCode = current.selectedEventTypeCode ?: return
        if (!current.step1Valid || !current.step2Valid) return

        val category = current.catalog.firstOrNull { it.code == current.selectedCategoryCode }
        val eventType = category?.eventTypes?.firstOrNull { it.code == eventTypeCode }
        val railwayLineId = current.lineStep.resolvedRailwayLineId(current.lineAnswer)
        val railwayLineDisplay = (current.lineAnswer as? LineAnswer.Chosen)?.option?.displayName
            ?: (current.lineStep as? RailwayLineStep.SingleInferred)?.option?.displayName

        val draft = ReportDraft(
            occurredAt = current.occurredAt,
            trainIdentifierInput = current.trainIdentifierInput,
            settlementId = settlement.id,
            railwayLineId = railwayLineId,
            eventTypeCode = eventTypeCode,
        )
        val display = ReportDisplaySnapshot(
            settlementName = settlement.name,
            railwayLineDisplay = railwayLineDisplay,
            categoryDisplay = category?.displayName ?: "",
            eventTypeDisplay = eventType?.displayName ?: eventTypeCode,
        )

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (val outcome = reportRepository.submit(draft, display)) {
                is SubmitOutcome.Created -> onSubmitSucceeded(outcome.entity.publicReportId, display, "RECEIVED")
                is SubmitOutcome.Replayed -> onSubmitSucceeded(outcome.entity.publicReportId, display, "RECEIVED")
                is SubmitOutcome.ValidationFailed -> _state.update {
                    it.copy(submitting = false, error = UiErrorReason.VALIDATION, step = ReportStep.ALAPADATOK)
                }
                is SubmitOutcome.AmbiguousFailure -> _state.update {
                    it.copy(
                        submitting = false,
                        error = if (outcome.code == ApiErrorCode.REFERENCE_DATASET_UNAVAILABLE) {
                            UiErrorReason.REFERENCE_UNAVAILABLE
                        } else {
                            UiErrorReason.NETWORK
                        },
                    )
                }
                SubmitOutcome.Conflict -> _state.update { it.copy(submitting = false, error = UiErrorReason.CONFLICT) }
                SubmitOutcome.AccessLost -> _state.update { it.copy(submitting = false, error = UiErrorReason.ACCESS_LOST) }
                SubmitOutcome.LocalPersistenceFailed -> _state.update {
                    it.copy(submitting = false, error = UiErrorReason.LOCAL_STORAGE)
                }
            }
        }
    }

    /** Retries an existing PENDING/CONFLICT record with its exact frozen identity - never a new one. */
    fun onRetrySubmission(clientSubmissionId: UUID) {
        viewModelScope.launch {
            reportRepository.retry(clientSubmissionId)
        }
    }

    private fun onSubmitSucceeded(publicReportId: UUID?, display: ReportDisplaySnapshot, status: String) {
        _state.update {
            it.copy(
                submitting = false,
                step = ReportStep.SUCCESS,
                success = SuccessInfo(
                    publicReportId = publicReportId?.toString().orEmpty(),
                    eventTypeDisplay = display.eventTypeDisplay,
                    trainIdentifier = it.trainIdentifierInput.trim().ifEmpty { null },
                    settlementName = display.settlementName,
                    status = status,
                ),
            )
        }
    }

    fun onStartNewReport() {
        _state.value = NewReportUiState()
    }
}
