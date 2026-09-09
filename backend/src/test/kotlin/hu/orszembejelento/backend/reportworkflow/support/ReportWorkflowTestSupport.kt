package hu.orszembejelento.backend.reportworkflow.support

import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.User
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.reports.support.PublicReportTestSupport
import hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.annotation.Autowired

/**
 * Shared fixtures and HTTP helpers for the Phase 7 service report-workflow test battery
 * (brief §54-66). Builds on [PublicReportTestSupport] for report/reference fixtures (a
 * routed report needs a real settlement/line/area chain routed through the real
 * [hu.orszembejelento.backend.routing.application.RoutingService], never faked) and adds
 * service-area authorisation fixtures the same way
 * [hu.orszembejelento.backend.support.AbstractUserManagementIntegrationTest] does for Phase 6.
 */
abstract class ReportWorkflowTestSupport : PublicReportTestSupport() {

    @Autowired
    protected lateinit var serviceAreas: JdbcServiceAreaRepository

    // ------------------------------------------------------------------------- area fixtures

    protected fun grantArea(userId: UUID, areaId: UUID) {
        serviceAreas.grantAreaIfAbsent(userId, areaId)
    }

    protected fun setGlobalAccess(userId: UUID, value: Boolean) {
        serviceAreas.setGlobalAreaAccess(userId, value)
    }

    /** [setCurrentReferenceState] may only be called once per test - a second call would collide with its own unique `dataset_version`/`is_current` indexes. */
    private var referenceStateSeeded = false

    private fun ensureCurrentReferenceState() {
        if (referenceStateSeeded) return
        setCurrentReferenceState()
        referenceStateSeeded = true
    }

    /** One settlement + one active line + one active area, fully wired end to end. */
    protected fun givenRoutedArea(areaName: String = "Terulet-${UUID.randomUUID()}", areaStatus: String = "ACTIVE"): RoutedFixture {
        ensureCurrentReferenceState()
        val settlementId = insertSettlement("%05d".format((10000..99999).random()))
        val lineId = insertLine("L${(1000..9999).random()}")
        insertRelation(settlementId, lineId)
        val areaId = insertArea(areaName, areaStatus)
        assignLineToArea(areaId, lineId)
        return RoutedFixture(settlementId, lineId, areaId)
    }

    protected data class RoutedFixture(val settlementId: UUID, val lineId: UUID, val areaId: UUID)

    /** Creates one report routed into [area]'s area (or a fresh one if none is given). */
    protected fun givenRoutedReport(area: RoutedFixture = givenRoutedArea()): RoutedReport {
        val created = givenCreatedReport(settlementId = area.settlementId, railwayLineId = area.lineId)
        return RoutedReport(created.publicId, area)
    }

    protected data class RoutedReport(val publicId: UUID, val area: RoutedFixture)

    /** Creates one report with no verified line relation at all - always routes UNCLASSIFIED. */
    protected fun givenUnclassifiedReport(): UUID {
        ensureCurrentReferenceState()
        val settlementId = insertSettlement("%05d".format((10000..99999).random()))
        return givenCreatedReport(settlementId = settlementId).publicId
    }

    // ---------------------------------------------------------------------------- HTTP: queues

    protected fun newQueue(bearer: String, page: Int? = null, size: Int? = null, areaId: UUID? = null, assigneeServiceId: String? = null): HttpResponse<String> =
        get("/api/v1/service/reports/new${queryString(page, size, areaId)}", bearer)

    protected fun inProgressQueue(bearer: String, page: Int? = null, size: Int? = null, areaId: UUID? = null, assigneeServiceId: String? = null): HttpResponse<String> =
        get("/api/v1/service/reports/in-progress${queryString(page, size, areaId, assigneeServiceId)}", bearer)

    protected fun archiveQueue(bearer: String, page: Int? = null, size: Int? = null, areaId: UUID? = null): HttpResponse<String> =
        get("/api/v1/service/reports/archive${queryString(page, size, areaId)}", bearer)

    protected fun detail(bearer: String, publicReportId: UUID): HttpResponse<String> =
        get("/api/v1/service/reports/$publicReportId", bearer)

    private fun queryString(page: Int?, size: Int?, areaId: UUID?, assigneeServiceId: String? = null): String {
        val params = buildList {
            page?.let { add("page=$it") }
            size?.let { add("size=$it") }
            areaId?.let { add("areaId=$it") }
            assigneeServiceId?.let { add("assigneeServiceId=$it") }
        }
        return if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }

    // ------------------------------------------------------------------------- HTTP: mutations

    protected fun claim(bearer: String, publicReportId: UUID, expectedVersion: Long): HttpResponse<String> =
        post("/api/v1/service/reports/$publicReportId/claim", """{"expectedVersion":$expectedVersion}""", bearer)

    protected fun returnToNew(bearer: String, publicReportId: UUID, expectedVersion: Long): HttpResponse<String> =
        post("/api/v1/service/reports/$publicReportId/return", """{"expectedVersion":$expectedVersion}""", bearer)

    protected fun close(bearer: String, publicReportId: UUID, expectedVersion: Long): HttpResponse<String> =
        post("/api/v1/service/reports/$publicReportId/close", """{"expectedVersion":$expectedVersion}""", bearer)

    protected fun reassign(bearer: String, publicReportId: UUID, expectedVersion: Long, targetServiceId: ServiceId): HttpResponse<String> =
        post(
            "/api/v1/service/reports/$publicReportId/reassign",
            """{"expectedVersion":$expectedVersion,"targetServiceId":"${targetServiceId.value}"}""",
            bearer,
        )

    // ------------------------------------------------------------------------------- raw calls

    /** Fires a mutation without going through the standard [post] helper, for races that need multiple concurrent HTTP clients. */
    protected fun rawPost(path: String, body: String, bearer: String): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $bearer")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        return java.net.http.HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    // -------------------------------------------------------------------------- DB assertions

    protected data class ReportRow(val status: String, val assignedUserId: UUID?, val workflowVersion: Long, val archivedAt: Instant?)

    protected fun reportRow(publicId: UUID): ReportRow =
        jdbc.sql("SELECT status, assigned_user_id, workflow_version, archived_at FROM reports WHERE public_id = :id")
            .param("id", publicId)
            .query { rs, _ ->
                ReportRow(
                    status = rs.getString("status"),
                    assignedUserId = rs.getObject("assigned_user_id", UUID::class.java),
                    workflowVersion = rs.getLong("workflow_version"),
                    archivedAt = rs.getTimestamp("archived_at")?.toInstant(),
                )
            }
            .single()

    protected fun internalReportId(publicId: UUID): UUID =
        jdbc.sql("SELECT id FROM reports WHERE public_id = :id").param("id", publicId).query(UUID::class.java).single()

    protected data class AssignmentRow(val assigneeUserId: UUID, val assignedByUserId: UUID, val endedAt: Instant?, val endedByUserId: UUID?, val endReason: String?)

    protected fun assignmentHistory(publicId: UUID): List<AssignmentRow> {
        val reportId = internalReportId(publicId)
        return jdbc.sql(
            "SELECT assignee_user_id, assigned_by_user_id, ended_at, ended_by_user_id, end_reason " +
                "FROM report_assignments WHERE report_id = :rid ORDER BY assigned_at",
        )
            .param("rid", reportId)
            .query { rs, _ ->
                AssignmentRow(
                    assigneeUserId = rs.getObject("assignee_user_id", UUID::class.java),
                    assignedByUserId = rs.getObject("assigned_by_user_id", UUID::class.java),
                    endedAt = rs.getTimestamp("ended_at")?.toInstant(),
                    endedByUserId = rs.getObject("ended_by_user_id", UUID::class.java),
                    endReason = rs.getString("end_reason"),
                )
            }
            .list()
    }

    protected fun openAssignmentCount(publicId: UUID): Int {
        val reportId = internalReportId(publicId)
        return jdbc.sql("SELECT COUNT(*) FROM report_assignments WHERE report_id = :rid AND ended_at IS NULL")
            .param("rid", reportId).query(Int::class.java).single()
    }

    protected fun auditEventCount(eventType: String): Int =
        jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE event_type = :t")
            .param("t", eventType).query(Int::class.java).single()

    // ------------------------------------------------------------------------------ role users

    protected fun givenServiceUser() = givenUser(role = UserRole.SERVICE_USER)
    protected fun givenTerritorialModerator() = givenUser(role = UserRole.MODERATOR)
    protected fun givenSuperAdmin() = givenUser(role = UserRole.SUPER_ADMIN)

    protected fun givenGlobalModerator(): User {
        val mod = givenUser(role = UserRole.MODERATOR)
        setGlobalAccess(mod.id, true)
        return mod
    }

    protected fun givenGlobalServiceUser(): User {
        val user = givenUser(role = UserRole.SERVICE_USER)
        setGlobalAccess(user.id, true)
        return user
    }

    protected fun bearerFor(user: User): String = loginSuccessfully(user.serviceId).accessToken

    // ------------------------------------------------------------------------- concurrency

    /**
     * Releases [count] blocks from a shared latch so they genuinely overlap inside the
     * database - mirrors [hu.orszembejelento.backend.usermanagement.UserManagementConcurrencyIT]'s
     * helper exactly, reused here for the Phase 7 required races (brief §61-64).
     */
    protected fun <T> runConcurrently(count: Int, block: (Int) -> T): List<Result<T>> {
        val pool = Executors.newFixedThreadPool(count)
        val ready = CountDownLatch(count)
        val go = CountDownLatch(1)
        try {
            val futures = (0 until count).map { index ->
                pool.submit<Result<T>> {
                    ready.countDown()
                    go.await(10, TimeUnit.SECONDS)
                    runCatching { block(index) }
                }
            }
            check(ready.await(10, TimeUnit.SECONDS)) { "workers did not start" }
            go.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
