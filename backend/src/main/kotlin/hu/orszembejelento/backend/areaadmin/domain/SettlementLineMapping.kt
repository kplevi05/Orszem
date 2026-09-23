package hu.orszembejelento.backend.areaadmin.domain

import java.util.UUID

/** Null target removes exactly this operational mapping, never its reference relation. */
data class SettlementLineChange(
    val kshCode: String,
    val lineCode: String,
    val targetServiceAreaId: UUID?,
    val expectedCurrentServiceAreaId: UUID?,
)

data class SettlementLineChangePreview(
    val kshCode: String,
    val lineCode: String,
    val currentServiceAreaId: UUID?,
    val targetServiceAreaId: UUID?,
    val changed: Boolean,
)

data class SettlementLineBatchResult(
    val referenceVersion: String,
    val applied: Boolean,
    val changedCount: Int,
    val items: List<SettlementLineChangePreview>,
)

data class SettlementLineMappingView(
    val kshCode: String,
    val settlementName: String,
    val settlementActive: Boolean,
    val lineCode: String,
    val lineActive: Boolean,
    val serviceAreaId: UUID,
    val serviceAreaName: String,
    val serviceAreaActive: Boolean,
)

enum class SettlementLineConfigurationProblem {
    INVALID_BATCH,
    REFERENCE_CHANGED,
    REFERENCE_NOT_AVAILABLE,
    ASSIGNMENT_CHANGED,
    MIXED_ROUTING_MODES,
}

class SettlementLineConfigurationException(val problem: SettlementLineConfigurationProblem) : RuntimeException()
