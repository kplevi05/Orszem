package hu.orszem.reporting

import hu.orszem.catalog.domain.EventCatalogPort
import hu.orszem.catalog.domain.EventTypeRef
import hu.orszem.catalog.domain.EventTypeView
import hu.orszem.reporting.application.CreatePublicReportCommand
import hu.orszem.reporting.application.CreatePublicReportUseCase
import hu.orszem.reporting.domain.PublicReportRepository
import hu.orszem.reporting.domain.ReportBusinessFields
import hu.orszem.reporting.domain.ReportStatus
import hu.orszem.shared.config.OrszemProperties
import hu.orszem.shared.error.EventTypeInvalidException
import hu.orszem.shared.error.OccurredAtInFutureException
import hu.orszem.shared.error.ReportIdConflictException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

class CreatePublicReportUseCaseTest {

    private val knifeId = UUID.fromString("12b90cf8-da85-5ed1-9113-627b81ad2214")
    private val now = Instant.parse("2026-09-01T18:42:08Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val catalog = object : EventCatalogPort {
        override fun listActiveEventTypes(): List<EventTypeView> = emptyList()
        override fun findActiveByCode(code: String): EventTypeRef? =
            if (code == "KNIFE_ATTACK") {
                EventTypeRef(knifeId, "KNIFE_ATTACK", "Késelés", "VIOLENCE_DANGER", "Erőszak és közvetlen veszély", true)
            } else null
        override fun findById(id: UUID): EventTypeRef? = null
    }

    private class FakeRepo : PublicReportRepository {
        val rows = mutableMapOf<UUID, ReportBusinessFields>()
        override fun findBusinessFieldsById(id: UUID) = rows[id]
        override fun insertNew(
            id: UUID, eventTypeId: UUID, trainIdentifier: String, settlement: String,
            occurredAt: Instant, receivedAt: Instant,
        ): Boolean {
            if (rows.containsKey(id)) return false
            rows[id] = ReportBusinessFields(eventTypeId, trainIdentifier, settlement, occurredAt, receivedAt, ReportStatus.NEW)
            return true
        }
    }

    private fun useCase(repo: PublicReportRepository) =
        CreatePublicReportUseCase(catalog, repo, OrszemProperties(), clock)

    private fun command(
        id: UUID = UUID.randomUUID(),
        code: String = "KNIFE_ATTACK",
        train: String = "  IC   123 ",
        settlement: String = " Budapest ",
        occurredAt: Instant = now.minusSeconds(120),
    ) = CreatePublicReportCommand(id, code, train, settlement, occurredAt)

    @Test
    fun `normalizes whitespace and stores a NEW report`() {
        val repo = FakeRepo()
        val result = useCase(repo).execute(command())

        assertThat(result.status).isEqualTo(ReportStatus.NEW)
        assertThat(result.idempotentReplay).isFalse()
        val stored = repo.rows.values.single()
        assertThat(stored.trainIdentifier).isEqualTo("IC 123")
        assertThat(stored.settlement).isEqualTo("Budapest")
    }

    @Test
    fun `identical resubmission is an idempotent replay`() {
        val repo = FakeRepo()
        val id = UUID.randomUUID()
        val first = useCase(repo).execute(command(id = id))
        val second = useCase(repo).execute(command(id = id))

        assertThat(second.idempotentReplay).isTrue()
        assertThat(second.receivedAt).isEqualTo(first.receivedAt)
        assertThat(repo.rows).hasSize(1)
    }

    @Test
    fun `same id with a different settlement conflicts`() {
        val repo = FakeRepo()
        val id = UUID.randomUUID()
        useCase(repo).execute(command(id = id, settlement = "Budapest"))

        assertThatThrownBy { useCase(repo).execute(command(id = id, settlement = "Vác")) }
            .isInstanceOf(ReportIdConflictException::class.java)
    }

    @Test
    fun `unknown event type is rejected`() {
        assertThatThrownBy { useCase(FakeRepo()).execute(command(code = "NOPE")) }
            .isInstanceOf(EventTypeInvalidException::class.java)
    }

    @Test
    fun `occurredAt more than 5 minutes ahead is rejected`() {
        assertThatThrownBy {
            useCase(FakeRepo()).execute(command(occurredAt = now.plus(6, ChronoUnit.MINUTES)))
        }.isInstanceOf(OccurredAtInFutureException::class.java)
    }

    @Test
    fun `occurredAt within 5 minutes ahead is allowed`() {
        val result = useCase(FakeRepo()).execute(command(occurredAt = now.plus(4, ChronoUnit.MINUTES)))
        assertThat(result.status).isEqualTo(ReportStatus.NEW)
    }
}
