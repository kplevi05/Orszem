package hu.orszembejelento.backend.audit

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.audit.support.AuditTestSupport
import hu.orszembejelento.backend.auth.domain.RevocationReason
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode

/**
 * Mandatory security regression tests (Phase 12 brief §34/§35/§33): proves the Phase 12 API is
 * whitelist-based, not a raw-metadata pass-through, and that reading it can never itself mutate
 * the trail. The one intentionally direct call to [JdbcAuditRepository.record] here is exactly
 * the exception the brief itself carves out (§42/§68) - isolated security fixtures only, never
 * used for the behavioural/cross-phase proofs elsewhere in this package.
 */
class AuditSecurityIT : AuditTestSupport() {

    @Autowired
    private lateinit var auditRepository: JdbcAuditRepository

    @Test
    fun `an unknown metadata key on an otherwise-valid known event never reaches list or detail`() {
        val superAdmin = givenSuperAdmin()
        val target = givenServiceUser()
        val marker = "THIS_MUST_NEVER_LEAK_SECRET_PROBE"

        val event = auditRepository.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = superAdmin.id,
            eventType = AuditEventType.USER_ROLE_CHANGED,
            targetType = AuditTargetType.USER,
            targetId = target.id,
            metadata = mapOf(
                "serviceId" to target.serviceId.value,
                "oldRole" to "SERVICE_USER",
                "newRole" to "MODERATOR",
                // The synthetic marker (brief §34) - a future writer's hypothetical new key.
                "secret_probe" to marker,
            ),
        )

        val bearer = bearerFor(superAdmin)

        val listResponse = auditEvents(bearer, query = target.serviceId.value)
        assertEquals(200, listResponse.statusCode())
        assertFalse(listResponse.body().contains(marker), "list response leaked an unwhitelisted metadata value")
        assertFalse(listResponse.body().contains("secret_probe"), "list response leaked an unwhitelisted metadata key")

        val detailResponse = auditDetail(bearer, event.id)
        assertEquals(200, detailResponse.statusCode())
        assertFalse(detailResponse.body().contains(marker), "detail response leaked an unwhitelisted metadata value")
        assertFalse(detailResponse.body().contains("secret_probe"), "detail response leaked an unwhitelisted metadata key")

        // The known, whitelisted fields from the very same row are still present - this proves
        // the marker's absence is the whitelist working, not the whole row being suppressed.
        assertTrue(detailResponse.body().contains("MODERATOR"), "the legitimately whitelisted newRole value should still be present")

        // Structural proof, not just a substring search: parse the JSON and confirm the marker
        // key genuinely never became a field name anywhere in the tree (a `.contains()` check
        // above could in principle be fooled by escaping; a parsed-tree walk cannot be).
        val listJson = json(listResponse)
        val detailJson = json(detailResponse)
        assertFalse(collectAllFieldNames(listJson).contains("secret_probe"), "the marker key must never become a JSON field name")
        assertFalse(collectAllFieldNames(detailJson).contains("secret_probe"), "the marker key must never become a JSON field name")
        assertFalse(collectAllStringValues(listJson).any { it.contains(marker) }, "the marker value must never appear as a JSON string value")
        assertFalse(collectAllStringValues(detailJson).any { it.contains(marker) }, "the marker value must never appear as a JSON string value")
    }

    @Test
    fun `list and detail JSON have a closed field shape - no raw metadata object is ever serialized`() {
        val superAdmin = givenSuperAdmin()
        val target = givenServiceUser()
        changeRole(bearerFor(superAdmin), target.serviceId.value, "MODERATOR")
        val bearer = bearerFor(superAdmin)

        val listJson = json(auditEvents(bearer, size = 20))
        val itemsNode = listJson["items"]
        assertTrue(itemsNode != null && itemsNode.isArray && itemsNode.size() > 0, "expected at least one list item to assert the shape against")

        val expectedListItemFields = setOf(
            "auditEventId", "occurredAt", "eventType", "actorServiceId", "targetType", "targetDisplayLabel", "summary",
        )
        itemsNode.forEach { item ->
            assertEquals(
                expectedListItemFields,
                item.propertyNames().toSet(),
                "a list item exposed an unexpected field - a raw metadata object or an internal field may have leaked: $item",
            )
            item["summary"].forEach { detail ->
                assertEquals(setOf("code", "value"), detail.propertyNames().toSet(), "a summary detail exposed an unexpected field: $detail")
            }
        }

        val ids = itemsNode.toList().map { UUID.fromString(it["auditEventId"].asString()) }
        val expectedDetailFields = setOf(
            "auditEventId", "occurredAt", "eventType", "actorServiceId", "targetType", "targetDisplayLabel", "details",
        )
        ids.take(10).forEach { id ->
            val detailJson = json(auditDetail(bearer, id))
            assertEquals(
                expectedDetailFields,
                detailJson.propertyNames().toSet(),
                "a detail response exposed an unexpected field - a raw metadata object or an internal field may have leaked: $detailJson",
            )
            detailJson["details"].forEach { detail ->
                assertEquals(setOf("code", "value"), detail.propertyNames().toSet(), "a detail item exposed an unexpected field: $detail")
            }
        }
    }

    @Test
    fun `no internal actor or target UUID is ever exposed as display data - only the event's own opaque id may look like one`() {
        val superAdmin = givenSuperAdmin()
        val target = givenServiceUser()
        changeRole(bearerFor(superAdmin), target.serviceId.value, "MODERATOR")
        val area = givenRoutedArea()
        grantArea(bearerFor(superAdmin), target.serviceId.value, area.areaId)
        val bearer = bearerFor(superAdmin)

        val listJson = json(auditEvents(bearer, size = 50))
        val itemsNode = listJson["items"]
        val displayFields = listOf("occurredAt", "eventType", "actorServiceId", "targetType", "targetDisplayLabel")

        itemsNode.forEach { item ->
            displayFields.forEach { field ->
                val value = item[field]
                if (value != null && !value.isNull && value.isTextual) {
                    assertFalse(UUID_REGEX.matches(value.asString()), "field '$field' leaked a raw UUID as display data: ${value.asString()}")
                }
            }
            item["summary"].forEach { detail ->
                val value = detail["value"].asString()
                assertFalse(UUID_REGEX.matches(value), "a summary detail value leaked a raw UUID: $value")
            }
        }

        val ids = itemsNode.toList().map { UUID.fromString(it["auditEventId"].asString()) }
        ids.take(10).forEach { id ->
            val detailJson = json(auditDetail(bearer, id))
            displayFields.forEach { field ->
                val value = detailJson[field]
                if (value != null && !value.isNull && value.isTextual) {
                    assertFalse(UUID_REGEX.matches(value.asString()), "field '$field' leaked a raw UUID as display data: ${value.asString()}")
                }
            }
            detailJson["details"].forEach { detail ->
                val value = detail["value"].asString()
                assertFalse(UUID_REGEX.matches(value), "a detail value leaked a raw UUID: $value")
            }
        }
    }

    @Test
    fun `no field structurally contains a credential-shaped forbidden term outside the closed, known-safe codes`() {
        val superAdmin = givenSuperAdmin()
        val target = givenServiceUser()
        changeRole(bearerFor(superAdmin), target.serviceId.value, "MODERATOR")
        val area = givenRoutedArea()
        grantArea(bearerFor(superAdmin), target.serviceId.value, area.areaId)
        val bearer = bearerFor(superAdmin)

        // "REFRESH_TOKEN_REUSE"/"PASSWORD_CHANGED"/etc are real, closed RevocationReason codes
        // (auth/domain/AuthSession.kt) and "INITIAL_PASSWORD_CHANGED" is a real AuditEventType -
        // both legitimately contain a forbidden-shaped substring as part of their own safe name,
        // not as a leaked secret. Every other field must never contain any of these terms.
        val knownSafeCodeValues = setOf(
            RevocationReason.LOGOUT, RevocationReason.LOGOUT_ALL, RevocationReason.PASSWORD_CHANGED,
            RevocationReason.INITIAL_PASSWORD_CHANGED, RevocationReason.ADMIN_PASSWORD_RESET,
            RevocationReason.ADMIN_USER_DEACTIVATED, RevocationReason.REFRESH_TOKEN_REUSE,
        ) + AuditEventType.entries.map { it.name }.toSet()
        val forbidden = listOf("password", "credential", "token", "hash", "secret", "capability", "authorization", "bearer")

        fun assertValueSafe(context: String, value: String) {
            if (value in knownSafeCodeValues) return
            forbidden.forEach { term ->
                assertFalse(value.lowercase().contains(term), "$context unexpectedly contained forbidden term '$term': $value")
            }
        }

        val listJson = json(auditEvents(bearer, size = 50))
        val itemsNode = listJson["items"]
        itemsNode.forEach { item ->
            listOf("actorServiceId", "targetType", "targetDisplayLabel", "eventType").forEach { field ->
                val value = item[field]
                if (value != null && !value.isNull && value.isTextual) assertValueSafe("list field '$field'", value.asString())
            }
            item["summary"].forEach { detail -> assertValueSafe("summary detail '${detail["code"].asString()}'", detail["value"].asString()) }
        }

        val ids = itemsNode.toList().map { UUID.fromString(it["auditEventId"].asString()) }
        ids.take(10).forEach { id ->
            val detailJson = json(auditDetail(bearer, id))
            listOf("actorServiceId", "targetType", "targetDisplayLabel", "eventType").forEach { field ->
                val value = detailJson[field]
                if (value != null && !value.isNull && value.isTextual) assertValueSafe("detail field '$field'", value.asString())
            }
            detailJson["details"].forEach { detail -> assertValueSafe("detail item '${detail["code"].asString()}'", detail["value"].asString()) }
        }
    }

    @Test
    fun `list and detail responses never contain a forbidden security field, across a representative set of real events`() {
        val superAdmin = givenSuperAdmin()
        val bearer = bearerFor(superAdmin)

        // A representative real event of several different shapes, all through real endpoints.
        val target = givenServiceUser()
        changeRole(bearer, target.serviceId.value, "MODERATOR")
        val area = givenRoutedArea()
        createArea(bearer, "Biztonsagi Teszt Terulet ${UUID.randomUUID()}")
        grantArea(bearer, target.serviceId.value, area.areaId)

        val listBody = auditEvents(bearer, size = 100).body()
        val detailIds = extractAuditEventIds(listBody).take(10)
        val detailBodies = detailIds.map { auditDetail(bearer, it).body() }
        val allBodies = listOf(listBody) + detailBodies

        val forbiddenSubstrings = listOf(
            "password", "passwordHash", "credential", "refreshToken", "accessToken",
            "sessionSecret", "capabilityHash", "bearer ", "authorization", "rawMetadata",
        )
        allBodies.forEach { body ->
            val lower = body.lowercase()
            forbiddenSubstrings.forEach { forbidden ->
                assertFalse(lower.contains(forbidden.lowercase()), "response unexpectedly contained '$forbidden': $body")
            }
        }
    }

    @Test
    fun `list and detail queries never alter audit row count or content - reading is not writing`() {
        val superAdmin = givenSuperAdmin()
        val target = givenServiceUser()
        changeRole(bearerFor(superAdmin), target.serviceId.value, "MODERATOR")

        val bearer = bearerFor(superAdmin)
        val countBefore = auditRowCount()
        val typesBefore = auditEventTypes()

        // Query the same data repeatedly, in every shape Phase 12 exposes.
        repeat(3) {
            auditEvents(bearer, page = 0, size = 20)
            auditOptions(bearer)
        }
        val anyId = extractAuditEventIds(auditEvents(bearer, size = 1).body()).firstOrNull()
        if (anyId != null) {
            repeat(3) { auditDetail(bearer, anyId) }
        }

        assertEquals(countBefore, auditRowCount(), "a read must never change the audit row count")
        assertEquals(typesBefore, auditEventTypes(), "a read must never change the audit row content/order")
    }

    private fun auditRowCount(): Int = jdbc.sql("SELECT COUNT(*) FROM audit_events").query(Int::class.java).single()

    private fun extractAuditEventIds(body: String): List<UUID> =
        Regex("\"auditEventId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"").findAll(body).map { UUID.fromString(it.groupValues[1]) }.toList()

    /** Recursively collects every JSON object field name in the tree - used to structurally prove an unwhitelisted key (e.g. a synthetic metadata marker) never became a response field. */
    private fun collectAllFieldNames(node: JsonNode): Set<String> {
        val names = mutableSetOf<String>()
        fun walk(n: JsonNode) {
            if (n.isObject) {
                n.propertyNames().forEach { names.add(it) }
            }
            n.forEach { walk(it) }
        }
        walk(node)
        return names
    }

    /** Recursively collects every JSON string leaf value in the tree - used to structurally prove an unwhitelisted value never leaked into any field, at any depth. */
    private fun collectAllStringValues(node: JsonNode): List<String> {
        val values = mutableListOf<String>()
        fun walk(n: JsonNode) {
            if (n.isTextual) values.add(n.asString())
            n.forEach { walk(it) }
        }
        walk(node)
        return values
    }

    companion object {
        private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
