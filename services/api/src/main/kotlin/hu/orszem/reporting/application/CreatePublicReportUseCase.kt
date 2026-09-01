package hu.orszem.reporting.application

import hu.orszem.catalog.domain.EventCatalogPort
import hu.orszem.reporting.domain.CreatedReport
import hu.orszem.reporting.domain.PublicReportRepository
import hu.orszem.reporting.domain.ReportStatus
import hu.orszem.reporting.domain.TextNormalization
import hu.orszem.shared.config.OrszemProperties
import hu.orszem.shared.error.EventTypeInvalidException
import hu.orszem.shared.error.FieldErrorDetail
import hu.orszem.shared.error.OccurredAtInFutureException
import hu.orszem.shared.error.ReportIdConflictException
import hu.orszem.shared.error.ValidationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Raw client input as received by the controller (pre-normalization). */
data class CreatePublicReportCommand(
    val id: UUID,
    val eventTypeCode: String,
    val trainIdentifier: String,
    val settlement: String,
    val occurredAt: Instant,
)

@Service
class CreatePublicReportUseCase(
    private val catalog: EventCatalogPort,
    private val repository: PublicReportRepository,
    private val properties: OrszemProperties,
    private val clock: Clock,
) {

    @Transactional
    fun execute(command: CreatePublicReportCommand): CreatedReport {
        val train = TextNormalization.normalize(command.trainIdentifier)
        val settlement = TextNormalization.normalize(command.settlement)
        validateText(train, settlement)

        // PostgreSQL `timestamptz` has microsecond precision; normalize so an
        // idempotent replay reports exactly what was stored.
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val occurredAt = command.occurredAt.truncatedTo(ChronoUnit.MICROS)
        if (occurredAt.isAfter(now.plus(properties.publicReport.maxFutureSkew))) {
            throw OccurredAtInFutureException()
        }

        val eventType = catalog.findActiveByCode(command.eventTypeCode)
            ?: throw EventTypeInvalidException()

        // Idempotency: same id + identical business body -> replay; different body -> 409.
        repository.findBusinessFieldsById(command.id)?.let { existing ->
            val identical = existing.eventTypeId == eventType.id &&
                existing.trainIdentifier == train &&
                existing.settlement == settlement &&
                existing.occurredAt == occurredAt
            if (!identical) throw ReportIdConflictException()
            return CreatedReport(command.id, ReportStatus.NEW, existing.receivedAt, idempotentReplay = true)
        }

        val inserted = repository.insertNew(command.id, eventType.id, train, settlement, occurredAt, now)
        if (!inserted) {
            // Lost an insert race: re-read and re-apply the idempotency rule.
            val existing = repository.findBusinessFieldsById(command.id)
                ?: throw ReportIdConflictException()
            val identical = existing.eventTypeId == eventType.id &&
                existing.trainIdentifier == train &&
                existing.settlement == settlement &&
                existing.occurredAt == occurredAt
            if (!identical) throw ReportIdConflictException()
            return CreatedReport(command.id, ReportStatus.NEW, existing.receivedAt, idempotentReplay = true)
        }
        return CreatedReport(command.id, ReportStatus.NEW, now, idempotentReplay = false)
    }

    private fun validateText(train: String, settlement: String) {
        val errors = buildList {
            if (train.isEmpty()) add(FieldErrorDetail("trainIdentifier", "NOT_BLANK", "A vonat megadása kötelező."))
            else if (train.length > 64) add(FieldErrorDetail("trainIdentifier", "TOO_LONG", "A vonat legfeljebb 64 karakter."))
            if (settlement.isEmpty()) add(FieldErrorDetail("settlement", "NOT_BLANK", "A település megadása kötelező."))
            else if (settlement.length > 128) add(FieldErrorDetail("settlement", "TOO_LONG", "A település legfeljebb 128 karakter."))
        }
        if (errors.isNotEmpty()) throw ValidationException(fieldErrors = errors)
    }
}
