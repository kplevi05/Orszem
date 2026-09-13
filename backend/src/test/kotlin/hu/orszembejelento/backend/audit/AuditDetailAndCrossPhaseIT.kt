package hu.orszembejelento.backend.audit

import hu.orszembejelento.backend.audit.support.AuditTestSupport
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Detail-endpoint safety (brief §22/§23/§36-38) and the real cross-phase proof (brief §42/§68):
 * every fixture event below is produced by the *real* existing business mutation through its
 * real HTTP endpoint - never `JdbcAuditRepository.record()` called directly (that path is
 * reserved for the isolated security fixtures in `AuditSecurityIT`).
 */
class AuditDetailAndCrossPhaseIT : AuditTestSupport() {

    @Test
    fun `an unknown audit event id is a 404`() {
        val response = auditDetail(bearerFor(givenSuperAdmin()), UUID.randomUUID())
        assertEquals(404, response.statusCode())
        assertEquals("AUDIT_EVENT_NOT_FOUND", errorCode(response))
    }

    @Test
    fun `real user creation is queryable - safe actor and target identity, no internal UUID`() {
        val admin = givenSuperAdmin()
        val bearer = bearerFor(admin)
        val created = json(createUser(bearer, role = "SERVICE_USER"))
        val newServiceId = created.get("serviceId").asText()

        val body = json(auditEvents(bearerFor(admin), eventType = "USER_CREATED", query = newServiceId))
        assertEquals(1, body.get("totalElements").asInt())
        val item = body.get("items").asList().single()
        assertEquals(admin.serviceId.value, item.get("actorServiceId").asText())
        assertEquals(newServiceId, item.get("targetDisplayLabel").asText())
        assertFalse(looksLikeRawUuid(item.get("targetDisplayLabel").asText()), "target display label must be the Service ID, never a UUID")
    }

    @Test
    fun `a real role change shows the old and new role from the stored event, never inferred from current state`() {
        val admin = givenSuperAdmin()
        val bearer = bearerFor(admin)
        val target = givenServiceUser()

        // Two real transitions: SERVICE_USER -> MODERATOR, then MODERATOR -> SERVICE_USER again
        // (the only pair `INVALID_ROLE_TRANSITION` allows - SUPER_ADMIN is never a valid
        // target/source, Phase 6 brief §26). The FIRST event's own stored detail must still say
        // SERVICE_USER -> MODERATOR even after the target's role has moved on again - never
        // "whatever the role is now" (brief §9/§38).
        changeRole(bearer, target.serviceId.value, "MODERATOR")
        // Without an explicit clock advance, two audit rows written in the same instant tie on
        // `created_at` and the `id DESC` tie-break (deterministic, but not chronological - see
        // AuditListIT's own "same instant" test) can make `events.last()` pick the WRONG row.
        // This bit the full-suite run once already (this test passed in isolation, then failed
        // under `./gradlew build` when the two real HTTP calls landed in the same tick) -
        // mirrors the same `mutableClock.advance` pattern AuditListIT.kt:79 already uses.
        mutableClock.advance(java.time.Duration.ofSeconds(1))
        changeRole(bearerFor(admin), target.serviceId.value, "SERVICE_USER")

        val events = json(auditEvents(bearerFor(admin), eventType = "USER_ROLE_CHANGED", query = target.serviceId.value, size = 10)).get("items").asList()
        assertEquals(2, events.size)
        val firstEventId = UUID.fromString(events.last().get("auditEventId").asText()) // oldest of the returned (list is newest-first)
        val detail = json(auditDetail(bearerFor(admin), firstEventId))
        val details = detail.get("details").asList().associate { it.get("code").asText() to it.get("value").asText() }
        assertEquals("SERVICE_USER", details["OLD_ROLE"])
        assertEquals("MODERATOR", details["NEW_ROLE"])
    }

    @Test
    fun `a real moderation delete then restore both surface with the true stored reason and status`() {
        val area = givenRoutedArea()
        val report = givenRoutedReport(area)
        val moderator = givenSuperAdmin()
        val bearer = bearerFor(moderator)

        delete(bearer, report.publicId, reportRow(report.publicId).workflowVersion, reason = "DUPLICATE")
        val afterDeleteVersion = reportRow(report.publicId).workflowVersion
        restore(bearerFor(moderator), report.publicId, afterDeleteVersion)

        val deleteEvent = json(auditEvents(bearerFor(moderator), eventType = "REPORT_MODERATION_DELETED", query = report.publicId.toString().take(8))).get("items").asList().single()
        val deleteDetails = json(auditDetail(bearerFor(moderator), UUID.fromString(deleteEvent.get("auditEventId").asText()))).get("details").asList()
            .associate { it.get("code").asText() to it.get("value").asText() }
        assertEquals("DUPLICATE", deleteDetails["REASON"])

        val restoreEvent = json(auditEvents(bearerFor(moderator), eventType = "REPORT_MODERATION_RESTORED", query = report.publicId.toString().take(8))).get("items").asList().single()
        assertEquals("#" + report.publicId.toString().take(8).uppercase(), restoreEvent.get("targetDisplayLabel").asText())
    }

    @Test
    fun `a real ServiceArea rename shows the true old and new name from the event, never the current name only`() {
        val admin = givenSuperAdmin()
        val bearer = bearerFor(admin)
        val oldName = "Atnevezes Elott ${UUID.randomUUID().toString().take(8)}"
        val newName = "Atnevezes Utan ${UUID.randomUUID().toString().take(8)}"
        val areaId = UUID.fromString(json(createArea(bearer, oldName)).get("id").asText())

        renameArea(bearerFor(admin), areaId, 0L, newName)

        val event = json(auditEvents(bearerFor(admin), eventType = "SERVICE_AREA_RENAMED", query = newName)).get("items").asList().single()
        val details = json(auditDetail(bearerFor(admin), UUID.fromString(event.get("auditEventId").asText()))).get("details").asList()
            .associate { it.get("code").asText() to it.get("value").asText() }
        assertEquals(oldName, details["OLD_NAME"])
        assertEquals(newName, details["NEW_NAME"])
        // The list-row target label is the CURRENT name (a live display label, brief §12) -
        // which happens to already be newName here, distinct from the historical OLD_NAME fact.
        assertEquals(newName, event.get("targetDisplayLabel").asText())
    }

    @Test
    fun `a real RailwayLine move shows the true from-area and to-area names from the event`() {
        val admin = givenSuperAdmin()
        val bearer = bearerFor(admin)
        val fromAreaName = "Kiindulo Terulet ${UUID.randomUUID().toString().take(8)}"
        val toAreaName = "Cel Terulet ${UUID.randomUUID().toString().take(8)}"
        val fromAreaId = UUID.fromString(json(createArea(bearer, fromAreaName)).get("id").asText())
        val toAreaId = UUID.fromString(json(createArea(bearerFor(admin), toAreaName)).get("id").asText())
        val lineCode = "MV-${UUID.randomUUID().toString().take(6)}"
        val lineId = insertLine(lineCode)

        assignLine(bearerFor(admin), lineId, fromAreaId, null)
        assignLine(bearerFor(admin), lineId, toAreaId, fromAreaId)

        val moveEvent = json(auditEvents(bearerFor(admin), eventType = "RAILWAY_LINE_SERVICE_AREA_MOVED", query = lineCode)).get("items").asList().single()
        val details = json(auditDetail(bearerFor(admin), UUID.fromString(moveEvent.get("auditEventId").asText()))).get("details").asList()
            .associate { it.get("code").asText() to it.get("value").asText() }
        assertEquals(fromAreaName, details["FROM_AREA"])
        assertEquals(toAreaName, details["TO_AREA"])
    }

    @Test
    fun `a real area grant is queryable with a safe resolved area name, never a raw area UUID`() {
        val admin = givenSuperAdmin()
        val bearer = bearerFor(admin)
        val target = givenServiceUser()
        val areaName = "Juttatas Terulet ${UUID.randomUUID().toString().take(8)}"
        val areaId = UUID.fromString(json(createArea(bearer, areaName)).get("id").asText())

        grantArea(bearerFor(admin), target.serviceId.value, areaId)

        val event = json(auditEvents(bearerFor(admin), eventType = "USER_AREA_GRANTED", query = target.serviceId.value)).get("items").asList().single()
        val details = json(auditDetail(bearerFor(admin), UUID.fromString(event.get("auditEventId").asText()))).get("details").asList()
            .associate { it.get("code").asText() to it.get("value").asText() }
        assertEquals(areaName, details["AREA"])
        assertFalse(details.values.any { looksLikeRawUuid(it) }, "no detail value should be a raw UUID")
    }

    // ------------------------------------------------------------------------------- private

    private fun looksLikeRawUuid(value: String): Boolean =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(value)
}
