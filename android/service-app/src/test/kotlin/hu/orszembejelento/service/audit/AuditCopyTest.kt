package hu.orszembejelento.service.audit

import hu.orszembejelento.service.audit.data.AuditPeriod
import hu.orszembejelento.service.audit.ui.AUDIT_EVENT_TYPE_ORDER
import hu.orszembejelento.service.audit.ui.auditEventTypeLabelRes
import hu.orszembejelento.service.audit.ui.auditPeriodLabelRes
import hu.orszembejelento.service.audit.ui.auditTargetTypeLabelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The mandatory audit/UI-mapping contract test (Phase 12 brief §69/§20): fails if the backend's
 * *actual* current event/target type set contains a code without a known Android label. The two
 * fixture lists below are an explicit, hand-maintained mirror of
 * `hu.orszembejelento.backend.audit.domain.AuditEventType`/`AuditTargetType` (Android and the
 * backend are separate Gradle modules with no shared code, so this is the "explicit contract
 * test fixture" the brief itself anticipates) - adding a new backend event/target type is a
 * two-place, deliberate change: this fixture list, and a matching label branch in
 * `AuditCopy.kt`, never an automatic silent pass-through of the raw code.
 */
class AuditCopyTest {

    /** Mirrors every `AuditEventType` entry, backend/audit/domain/AuditEvent.kt, verbatim. */
    private val actualBackendEventTypes = listOf(
        "SUPER_ADMIN_CREATED", "SUPER_ADMIN_PASSWORD_RESET",
        "INITIAL_PASSWORD_CHANGED", "PASSWORD_CHANGED",
        "SESSION_CREATED", "SESSION_REVOKED", "LOGOUT_ALL", "REFRESH_TOKEN_REUSE_DETECTED",
        "REFERENCE_DATASET_IMPORTED",
        "USER_CREATED", "USER_PASSWORD_RESET", "USER_DEACTIVATED", "USER_REACTIVATED", "USER_ROLE_CHANGED",
        "USER_AREA_GRANTED", "USER_AREA_REVOKED", "USER_GLOBAL_ACCESS_GRANTED", "USER_GLOBAL_ACCESS_REVOKED",
        "REPORT_CLAIMED", "REPORT_RETURNED_TO_NEW", "REPORT_REASSIGNED", "REPORT_ARCHIVED",
        "REPORT_MODERATION_DELETED", "REPORT_MODERATION_RESTORED",
        "SERVICE_AREA_CREATED", "SERVICE_AREA_RENAMED", "SERVICE_AREA_ACTIVATED", "SERVICE_AREA_DEACTIVATED",
        "RAILWAY_LINE_SERVICE_AREA_ASSIGNED", "RAILWAY_LINE_SERVICE_AREA_MOVED", "RAILWAY_LINE_SERVICE_AREA_UNASSIGNED",
        "SETTLEMENT_LINE_SERVICE_AREA_CHANGED",
    )

    /** Mirrors every `AuditTargetType` entry, backend/audit/domain/AuditEvent.kt, verbatim. */
    private val actualBackendTargetTypes = listOf("USER", "SESSION", "REFERENCE_DATASET", "REPORT", "SERVICE_AREA", "RAILWAY_LINE")

    @Test
    fun `every actual backend event type has a known Android label`() {
        actualBackendEventTypes.forEach { code ->
            assertNotNull("missing Android label for event type '$code' - add a branch in auditEventTypeLabelRes", auditEventTypeLabelRes(code))
        }
    }

    @Test
    fun `every actual backend target type has a known Android label`() {
        actualBackendTargetTypes.forEach { code ->
            assertNotNull("missing Android label for target type '$code' - add a branch in auditTargetTypeLabelRes", auditTargetTypeLabelRes(code))
        }
    }

    @Test
    fun `the event-type filter-sheet order contains every actual backend event type exactly once`() {
        assertEquals(actualBackendEventTypes.toSet(), AUDIT_EVENT_TYPE_ORDER.toSet())
        assertEquals(actualBackendEventTypes.size, AUDIT_EVENT_TYPE_ORDER.size)
    }

    @Test
    fun `an unknown event type code has no label - the caller renders the generic fallback`() {
        assertNull(auditEventTypeLabelRes("NOT_A_REAL_EVENT_TYPE"))
    }

    @Test
    fun `an unknown target type code has no label - the caller renders the generic fallback`() {
        assertNull(auditTargetTypeLabelRes("NOT_A_REAL_TARGET_TYPE"))
    }

    @Test
    fun `every AuditPeriod value has a label, including ALL`() {
        AuditPeriod.entries.forEach { period -> auditPeriodLabelRes(period) } // no exception = exhaustive `when`, compiler-enforced
    }
}
