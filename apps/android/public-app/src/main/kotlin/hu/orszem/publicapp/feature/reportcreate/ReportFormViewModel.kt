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

/**
 * User-facing submission failure reasons (Demo v1.1 §F).
 *
 * Every value maps to a fixed Hungarian string resource. Backend/Retrofit/OkHttp
 * messages, exception class names and hostnames are never surfaced.
 */
enum class FormErrorReason {
    /** No connection, DNS failure, connection refused/reset. */
    NETWORK,
    TIMEOUT,
    /** Backend reachable but failing (HTTP 5xx). */
    SERVER,
    RATE_LIMITED,
    /** The id already exists with different content; a retry needs a fresh id. */
    CONFLICT,
    VALIDATION,
    GENERIC,
    ;

    /** Whether offering a Retry action makes sense for this reason. */
    val retryable: Boolean
        get() = this != VALIDATION
}

/** What the user can do about the current location problem. */
enum class LocationAction { NONE, REQUEST_PERMISSION, OPEN_APP_SETTINGS, OPEN_LOCATION_SETTINGS, RETRY }

/**
 * Distinct, user-explainable location states (Demo v1.1 §A).
 *
 * None of these ever blocks submission: the settlement field stays editable and
 * a manually typed settlement is always accepted.
 */
enum class LocationMessage(val action: LocationAction) {
    PERMISSION_REQUIRED(LocationAction.REQUEST_PERMISSION),
    PERMISSION_DENIED_FOREVER(LocationAction.OPEN_APP_SETTINGS),
    SERVICES_DISABLED(LocationAction.OPEN_LOCATION_SETTINGS),
    UNAVAILABLE(LocationAction.RETRY),
    GEOCODING_FAILED(LocationAction.NONE),
}

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

    /**
     * The runtime permission prompt was answered with "deny".
     *
     * [permanently] comes from the Activity (`shouldShowRequestPermissionRationale`
     * is false after a denial), which is the only place that distinction is available.
     */
    fun onLocationPermissionDenied(permanently: Boolean = false) =
        _state.update {
            it.copy(
                locating = false,
                locationMessage = if (permanently) {
                    LocationMessage.PERMISSION_DENIED_FOREVER
                } else {
                    LocationMessage.PERMISSION_REQUIRED
                },
            )
        }

    fun dismissLocationMessage() = _state.update { it.copy(locationMessage = null) }

    fun onLocateRequested() {
        _state.update { it.copy(locating = true, locationMessage = null) }
        viewModelScope.launch {
            val result = locationProvider.currentSettlement()
            _state.update {
                when (result) {
                    is SettlementLookupResult.Success ->
                        it.copy(
                            locating = false,
                            settlement = result.settlement,
                            settlementError = false,
                            locationMessage = null,
                        )
                    SettlementLookupResult.PermissionMissing ->
                        it.copy(locating = false, locationMessage = LocationMessage.PERMISSION_REQUIRED)
                    SettlementLookupResult.LocationServicesDisabled ->
                        it.copy(locating = false, locationMessage = LocationMessage.SERVICES_DISABLED)
                    SettlementLookupResult.Unavailable ->
                        it.copy(locating = false, locationMessage = LocationMessage.UNAVAILABLE)
                    SettlementLookupResult.GeocodingFailed ->
                        it.copy(locating = false, locationMessage = LocationMessage.GEOCODING_FAILED)
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
                    // Every field the user typed is preserved so a retry costs nothing.
                    is Outcome.Failure -> it.copy(submitting = false, submitError = outcome.error.code.toFormReason())
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

    private fun ApiErrorCode.toFormReason(): FormErrorReason = when (this) {
        ApiErrorCode.NETWORK -> FormErrorReason.NETWORK
        ApiErrorCode.TIMEOUT -> FormErrorReason.TIMEOUT
        ApiErrorCode.INTERNAL_ERROR -> FormErrorReason.SERVER
        ApiErrorCode.RATE_LIMITED -> FormErrorReason.RATE_LIMITED
        ApiErrorCode.REPORT_ID_CONFLICT -> FormErrorReason.CONFLICT
        ApiErrorCode.VALIDATION_ERROR,
        ApiErrorCode.EVENT_TYPE_INVALID,
        ApiErrorCode.OCCURRED_AT_IN_FUTURE,
        -> FormErrorReason.VALIDATION
        else -> FormErrorReason.GENERIC
    }

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
