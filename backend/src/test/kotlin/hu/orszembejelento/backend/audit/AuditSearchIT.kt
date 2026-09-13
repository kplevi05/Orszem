package hu.orszembejelento.backend.audit

import hu.orszembejelento.backend.audit.support.AuditTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The safe, server-side search surface (Phase 12 brief §17/§39) - actor/target Service ID,
 * public report identifier, ServiceArea name, RailwayLine code, all through named,
 * parameterised columns. Never a search over the raw `metadata` blob.
 */
class AuditSearchIT : AuditTestSupport() {

    @Test
    fun `searching by the actor Service ID finds their own mutation`() {
        val admin = givenSuperAdmin()
        val target = givenServiceUser()
        val bearer = bearerFor(admin)
        changeRole(bearer, target.serviceId.value, "MODERATOR")

        val body = json(auditEvents(bearerFor(admin), query = admin.serviceId.value, eventType = "USER_ROLE_CHANGED"))
        assertTrue(body.get("totalElements").asInt() >= 1)
        assertTrue(body.get("items").asList().all { it.get("actorServiceId").asText() == admin.serviceId.value })
    }

    @Test
    fun `searching by the target Service ID finds the mutation performed on them`() {
        val admin = givenSuperAdmin()
        val target = givenServiceUser()
        changeRole(bearerFor(admin), target.serviceId.value, "MODERATOR")

        val body = json(auditEvents(bearerFor(admin), query = target.serviceId.value, eventType = "USER_ROLE_CHANGED"))
        assertEquals(1, body.get("totalElements").asInt())
        assertEquals(target.serviceId.value, body.get("items").asList().single().get("targetDisplayLabel").asText())
    }

    @Test
    fun `searching by a ServiceArea name finds its own creation event`() {
        val admin = givenSuperAdmin()
        val bearer = bearerFor(admin)
        val name = "Keresesi Teszt Terulet ${java.util.UUID.randomUUID().toString().take(8)}"
        createArea(bearer, name)

        val body = json(auditEvents(bearerFor(admin), query = name, eventType = "SERVICE_AREA_CREATED"))
        assertEquals(1, body.get("totalElements").asInt())
    }

    @Test
    fun `searching by a RailwayLine code finds an assignment event`() {
        val admin = givenSuperAdmin()
        val bearer = bearerFor(admin)
        val areaId = java.util.UUID.fromString(json(createArea(bearer, "Vonal Kereses Terulet ${java.util.UUID.randomUUID().toString().take(8)}")).get("id").asText())
        val lineCode = "VK-${java.util.UUID.randomUUID().toString().take(6)}"
        val lineId = insertLine(lineCode)

        assignLine(bearer, lineId, areaId, null)

        val body = json(auditEvents(bearerFor(admin), query = lineCode, eventType = "RAILWAY_LINE_SERVICE_AREA_ASSIGNED"))
        assertEquals(1, body.get("totalElements").asInt())
    }

    @Test
    fun `a query with no matches returns an empty page, not an error`() {
        val body = json(auditEvents(bearerFor(givenSuperAdmin()), query = "SZ-000000"))
        assertEquals(0, body.get("totalElements").asInt())
    }

    @Test
    fun `a 1-character query is rejected as invalid, not silently ignored`() {
        val response = auditEvents(bearerFor(givenSuperAdmin()), query = "S")
        assertEquals(400, response.statusCode())
        assertEquals("VALIDATION_ERROR", errorCode(response))
    }

    @Test
    fun `a blank query is treated as no search at all, not an error`() {
        val response = auditEvents(bearerFor(givenSuperAdmin()), query = "   ")
        assertEquals(200, response.statusCode())
    }

    @Test
    fun `SQL wildcard characters in a query are treated as literal text`() {
        val admin = givenSuperAdmin()
        val target = givenServiceUser()
        changeRole(bearerFor(admin), target.serviceId.value, "MODERATOR")

        // "%" / "_" are real characters in SQL LIKE unless escaped - a search for a string that
        // isn't actually present but merely *looks* like a wildcard pattern must find nothing.
        val body = json(auditEvents(bearerFor(admin), query = "SZ%______", eventType = "USER_ROLE_CHANGED"))
        assertEquals(0, body.get("totalElements").asInt())
    }

    @Test
    fun `search never matches raw metadata content that isn't a whitelisted searchable column`() {
        // "MODERATOR" is a legitimate metadata value (newRole) on the role-change event, but it
        // is not one of the whitelisted searchable columns (brief §17) - a search for it must
        // not accidentally match through the forbidden generic metadata path.
        val admin = givenSuperAdmin()
        val target = givenServiceUser()
        changeRole(bearerFor(admin), target.serviceId.value, "MODERATOR")

        val body = json(auditEvents(bearerFor(admin), query = "MODERATOR", eventType = "USER_ROLE_CHANGED"))
        assertEquals(0, body.get("totalElements").asInt())
    }
}
