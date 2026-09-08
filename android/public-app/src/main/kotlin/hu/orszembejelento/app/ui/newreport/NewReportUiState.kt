package hu.orszembejelento.app.ui.newreport

import hu.orszembejelento.app.report.data.CatalogCategory
import hu.orszembejelento.app.report.data.SettlementOption
import hu.orszembejelento.app.report.domain.LineAnswer
import hu.orszembejelento.app.report.domain.RailwayLineStep
import hu.orszembejelento.app.report.domain.isResolved
import java.time.Instant

enum class ReportStep { ALAPADATOK, ESEMENY, SUCCESS }

/** A single, stable, non-sensitive error reason the UI maps to Hungarian copy - never a raw message. */
enum class UiErrorReason {
    VALIDATION, REFERENCE_UNAVAILABLE, NETWORK, CONFLICT, LOCAL_STORAGE, ACCESS_LOST, GENERIC
}

enum class LocateStatus { IDLE, LOCATING, PERMISSION_DENIED, PERMISSION_PERMANENTLY_DENIED, SERVICES_DISABLED, FAILED, GEOCODER_UNAVAILABLE }

data class SuccessInfo(
    val publicReportId: String,
    val eventTypeDisplay: String,
    val trainIdentifier: String?,
    val settlementName: String,
    val status: String,
)

data class NewReportUiState(
    val step: ReportStep = ReportStep.ALAPADATOK,

    // Step 1
    val occurredAt: Instant = Instant.now(),
    val trainIdentifierInput: String = "",
    val settlementQuery: String = "",
    val settlementResults: List<SettlementOption> = emptyList(),
    val settlementSearching: Boolean = false,
    val selectedSettlement: SettlementOption? = null,
    val lineStep: RailwayLineStep = RailwayLineStep.NotApplicable,
    val lineAnswer: LineAnswer = LineAnswer.NotYetAnswered,
    val locateStatus: LocateStatus = LocateStatus.IDLE,

    // Step 2
    val catalog: List<CatalogCategory> = emptyList(),
    val catalogLoading: Boolean = false,
    val catalogFailed: Boolean = false,
    val selectedCategoryCode: String? = null,
    val selectedEventTypeCode: String? = null,

    // Submission
    val submitting: Boolean = false,
    val error: UiErrorReason? = null,
    val success: SuccessInfo? = null,
) {
    val step1Valid: Boolean
        get() = selectedSettlement != null && lineStep.isResolved(lineAnswer)

    val step2Valid: Boolean
        get() = selectedEventTypeCode != null
}
