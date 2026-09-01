package hu.orszem.publicapp.feature.reportcreate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.orszem.core.common.ApiErrorCode
import hu.orszem.core.common.Outcome
import hu.orszem.core.model.EventType
import hu.orszem.publicapp.data.CatalogRepository
import hu.orszem.publicapp.data.ReportRepository
import hu.orszem.publicapp.location.SettlementLocationProvider
import hu.orszem.publicapp.location.SettlementLookupResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

enum class FormErrorReason { NETWORK, GENERIC, CONFLICT }

data class ReportFormState(
    val catalogLoading: Boolean = true,
    val catalogError: Boolean = false,
    val categories: List<CatalogCategory> = emptyList(),
    val occurredAt: Instant = Instant.now(),
    val trainIdentifier: String = "",
    val settlement: String = "",
    val selectedEventType: EventType? = null,
    val trainError: Boolean = false,
    val settlementError: Boolean = false,
    val eventError: Boolean = false,
    val timeError: Boolean = false,
    val locating: Boolean = false,
    val locationMessage: LocationMessage? = null,
    val submitting: Boolean = false,
    val submitError: FormErrorReason? = null,
    val submitted: Boolean = false,
) {
    val canSubmit: Boolean
        get() = !submitting && trainIdentifier.isNotBlank() && settlement.isNotBlank() && selectedEventType != null
}

enum class LocationMessage { DENIED, FAILED }

data class CatalogCategory(val code: String, val label: String, val eventTypes: List<EventType>)

@HiltViewModel
class ReportFormViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val reportRepository: ReportRepository,
    private val locationProvider: SettlementLocationProvider,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(ReportFormState(occurredAt = clock.instant()))
    val state: StateFlow<ReportFormState> = _state.asStateFlow()

    /** Stable across retries so resubmission is idempotent. */
    private var pendingReportId: UUID = UUID.randomUUID()

    init {
        loadCatalog()
    }

    fun loadCatalog() {
        _state.update { it.copy(catalogLoading = true, catalogError = false) }
        viewModelScope.launch {
            when (val outcome = catalogRepository.loadEventTypes()) {
                is Outcome.Success -> _state.update {
                    it.copy(catalogLoading = false, categories = groupByCategory(outcome.value))
                }
                is Outcome.Failure -> _state.update { it.copy(catalogLoading = false, catalogError = true) }
            }
        }
    }

    fun onTrainChanged(value: String) = _state.update { it.copy(trainIdentifier = value.take(64), trainError = false) }

    fun onSettlementChanged(value: String) =
        _state.update { it.copy(settlement = value.take(128), settlementError = false, locationMessage = null) }

    fun onEventTypeSelected(eventType: EventType) =
        _state.update { it.copy(selectedEventType = eventType, eventError = false) }

    fun onOccurredAtChanged(instant: Instant) = _state.update { it.copy(occurredAt = instant, timeError = false) }

    fun onLocationPermissionDenied() =
        _state.update { it.copy(locating = false, locationMessage = LocationMessage.DENIED) }

    fun onLocateRequested() {
        _state.update { it.copy(locating = true, locationMessage = null) }
        viewModelScope.launch {
            val result = locationProvider.currentSettlement()
            _state.update {
                when (result) {
                    is SettlementLookupResult.Success ->
                        it.copy(locating = false, settlement = result.settlement, settlementError = false)
                    SettlementLookupResult.PermissionDenied ->
                        it.copy(locating = false, locationMessage = LocationMessage.DENIED)
                    SettlementLookupResult.Failed ->
                        it.copy(locating = false, locationMessage = LocationMessage.FAILED)
                }
            }
        }
    }

    fun onSubmit() {
        if (!validate()) return
        val current = _state.value
        _state.update { it.copy(submitting = true, submitError = null) }
        viewModelScope.launch {
            val outcome = reportRepository.submit(
                reportId = pendingReportId,
                eventTypeCode = current.selectedEventType!!.code,
                trainIdentifier = current.trainIdentifier.trim(),
                settlement = current.settlement.trim(),
                occurredAt = current.occurredAt,
            )
            _state.update {
                when (outcome) {
                    is Outcome.Success -> it.copy(submitting = false, submitted = true)
                    is Outcome.Failure -> it.copy(
                        submitting = false,
                        submitError = when (outcome.error.code) {
                            ApiErrorCode.NETWORK, ApiErrorCode.RATE_LIMITED -> FormErrorReason.NETWORK
                            ApiErrorCode.REPORT_ID_CONFLICT -> FormErrorReason.CONFLICT
                            else -> FormErrorReason.GENERIC
                        },
                    )
                }
            }
        }
    }

    fun onRetry() {
        // A conflict means the previous body already landed; start a fresh id.
        if (_state.value.submitError == FormErrorReason.CONFLICT) pendingReportId = UUID.randomUUID()
        onSubmit()
    }

    fun dismissSubmitError() = _state.update { it.copy(submitError = null) }

    private fun validate(): Boolean {
        val s = _state.value
        val futureLimit = clock.instant().plus(Duration.ofMinutes(5))
        val timeInvalid = s.occurredAt.isAfter(futureLimit)
        val updated = s.copy(
            trainError = s.trainIdentifier.isBlank(),
            settlementError = s.settlement.isBlank(),
            eventError = s.selectedEventType == null,
            timeError = timeInvalid,
        )
        _state.value = updated
        return !updated.trainError && !updated.settlementError && !updated.eventError && !updated.timeError
    }

    private fun groupByCategory(eventTypes: List<EventType>): List<CatalogCategory> =
        eventTypes.groupBy { it.categoryCode to (it.categoryLabel to it.categorySortOrder) }
            .toList()
            .sortedBy { it.first.second.second }
            .map { (key, types) ->
                CatalogCategory(key.first, key.second.first, types.sortedWith(compareBy({ it.sortOrder }, { it.label })))
            }
}
