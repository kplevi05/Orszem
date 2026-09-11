package hu.orszembejelento.backend.usermanagement

import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.reference.domain.ServiceAreaStatus
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import hu.orszembejelento.backend.support.AbstractUserManagementIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * `GET /account/me` now also returns the caller's own service-area scope (Phase 8 §3), so the
 * Service app can drive its local "active work view" without any user-management endpoint. It
 * is self-account only: no other user, no `canManage`, no management permission, no audit.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class OwnAccountScopeIT : AbstractUserManagementIntegrationTest() {

    @Test
    fun `a SERVICE_USER sees their own assigned areas and the global flag, and no other fields`() {
        val north = givenArea(name = "Északi terület")
        val south = givenArea(name = "Déli terület")
        val user = givenUser()
        assignArea(user.id, north.id)
        assignArea(user.id, south.id)

        val raw = get("/api/v1/service/account/me", loginSuccessfully(user.serviceId).accessToken)
        val me = json(raw)

        check(me.get("role").asText() == "SERVICE_USER")
        check(!me.get("globalAreaAccess").asBoolean())
        val names = me.get("areas").asList().map { it.get("name").asText() }
        check(names == listOf("Déli terület", "Északi terület")) { "areas must be name-ordered, got $names" }
        me.get("areas").asList().forEach { area -> check(area.get("status").asText() == "ACTIVE") }

        @Suppress("UNCHECKED_CAST")
        val parsed = objectMapper.readValue(raw.body(), Map::class.java) as Map<String, Any?>
        check(parsed.keys == setOf("serviceId", "role", "globalAreaAccess", "areas")) {
            "/me must expose nothing beyond the self-account contract, got ${parsed.keys}"
        }
        val firstArea = (parsed["areas"] as List<Map<String, Any?>>).first()
        check(firstArea.keys == setOf("id", "name", "status")) {
            "an area entry must expose only id/name/status, got ${firstArea.keys}"
        }
    }

    @Test
    fun `an inactive assigned area is still listed, with its INACTIVE status`() {
        val inactive = givenArea(name = "Nyugati terület", status = ServiceAreaStatus.INACTIVE)
        val user = givenUser()
        assignArea(user.id, inactive.id)

        val areas = json(get("/api/v1/service/account/me", loginSuccessfully(user.serviceId).accessToken)).get("areas").asList()
        check(areas.size == 1)
        check(areas.single().get("status").asText() == "INACTIVE")
    }

    @Test
    fun `a globally-scoped MODERATOR reports globalAreaAccess true`() {
        val moderator = givenUser(role = UserRole.MODERATOR)
        setGlobalAccess(moderator.id, true)

        val me = json(get("/api/v1/service/account/me", loginSuccessfully(moderator.serviceId).accessToken))
        check(me.get("role").asText() == "MODERATOR")
        check(me.get("globalAreaAccess").asBoolean())
    }

    @Test
    fun `the scope is read from current state - a just-granted area appears on the next call, same token`() {
        val area = givenArea(name = "Keleti terület")
        val user = givenUser()
        val token = loginSuccessfully(user.serviceId).accessToken

        check(json(get("/api/v1/service/account/me", token)).get("areas").isEmpty)

        assignArea(user.id, area.id)

        val areas = json(get("/api/v1/service/account/me", token)).get("areas").asList()
        check(areas.size == 1 && areas.single().get("name").asText() == "Keleti terület")
    }
}
