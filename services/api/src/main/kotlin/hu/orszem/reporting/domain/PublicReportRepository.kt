package hu.orszem.reporting.domain

import java.time.Instant
import java.util.UUID

/** Persistence port for anonymous public reports. */
interface PublicReportRepository {

    fun findBusinessFieldsById(id: UUID): ReportBusinessFields?

    /**
     * Insert a brand-new report in status NEW.
     * @return false if a row with the same id already exists (concurrent insert).
     */
    fun insertNew(
        id: UUID,
        eventTypeId: UUID,
        trainIdentifier: String,
        settlement: String,
        occurredAt: Instant,
        receivedAt: Instant,
    ): Boolean
}
