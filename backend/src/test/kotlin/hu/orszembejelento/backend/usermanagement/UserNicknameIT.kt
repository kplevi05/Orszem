package hu.orszembejelento.backend.usermanagement

import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import hu.orszembejelento.backend.support.AbstractUserManagementIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

@Import(AbstractAuthIntegrationTest.Containers::class)
class UserNicknameIT : AbstractUserManagementIntegrationTest() {

    @Test
    fun `every SUPER_ADMIN may set and remove own nickname from account profile`() {
        val admin = createSuperAdmin.create()
        completeInitialChange(admin.serviceId, admin.temporaryCredential)
        val bearer = loginSuccessfully(admin.serviceId).accessToken

        val set = changeOwnNickname(bearer, "  Levente 🚂  ")
        check(set.statusCode() == 200) { set.body() }
        check(json(set).get("serviceId").asText() == admin.serviceId.value)
        check(json(set).get("nickname").asText() == "Levente 🚂")
        check(users.findByServiceId(admin.serviceId)?.nickname == "Levente 🚂")

        val auditItems = json(
            get("/api/v1/service/audit/events?period=ALL&eventType=USER_NICKNAME_CHANGED", bearer),
        ).get("items")
        check(auditItems.size() == 1)
        check(auditItems.get(0).get("actorServiceId").asText() == "${admin.serviceId.value}(Levente 🚂)")
        check(auditItems.get(0).get("targetDisplayLabel").asText() == "${admin.serviceId.value}(Levente 🚂)")

        val me = get("/api/v1/service/account/me", bearer)
        check(json(me).get("nickname").asText() == "Levente 🚂")

        val removed = changeOwnNickname(bearer, "   ")
        check(removed.statusCode() == 200)
        check(json(removed).get("nickname").isNull)
        check(users.findByServiceId(admin.serviceId)?.nickname == null)
    }

    @Test
    fun `MODERATOR and SERVICE_USER cannot change their own nickname`() {
        listOf(UserRole.MODERATOR, UserRole.SERVICE_USER).forEach { role ->
            val actor = givenUser(role = role)
            val response = changeOwnNickname(loginSuccessfully(actor.serviceId).accessToken, "Saját")
            check(response.statusCode() == 403)
            check(errorCode(response) == "USER_MANAGEMENT_FORBIDDEN")
            check(users.findById(actor.id)?.nickname == null)
        }
    }

    @Test
    fun `SUPER_ADMIN may change lower ranks but not a peer SUPER_ADMIN`() {
        val actor = createSuperAdmin.create()
        completeInitialChange(actor.serviceId, actor.temporaryCredential)
        val bearer = loginSuccessfully(actor.serviceId).accessToken
        val moderator = givenUser(role = UserRole.MODERATOR)
        val serviceUser = givenUser()
        val peer = createSuperAdmin.create()

        val modResponse = changeManagedNickname(bearer, moderator.serviceId.value, "Modi")
        val userResponse = changeManagedNickname(bearer, serviceUser.serviceId.value, "Levente")
        val peerResponse = changeManagedNickname(bearer, peer.serviceId.value, "Másik admin")

        check(modResponse.statusCode() == 200)
        check(userResponse.statusCode() == 200)
        check(json(userResponse).get("nickname").asText() == "Levente")
        check(peerResponse.statusCode() == 403)
        check(errorCode(peerResponse) == "USER_NOT_MANAGEABLE")
        check(users.findByServiceId(peer.serviceId)?.nickname == null)
    }

    @Test
    fun `MODERATOR may change only an in-scope SERVICE_USER and never a peer`() {
        val ownArea = givenArea()
        val otherArea = givenArea()
        val moderator = givenUser(role = UserRole.MODERATOR)
        assignArea(moderator.id, ownArea.id)
        val inScope = givenUser().also { assignArea(it.id, ownArea.id) }
        val outOfScope = givenUser().also { assignArea(it.id, otherArea.id) }
        val peer = givenUser(role = UserRole.MODERATOR).also { assignArea(it.id, ownArea.id) }
        val bearer = loginSuccessfully(moderator.serviceId).accessToken

        check(changeManagedNickname(bearer, inScope.serviceId.value, "Sas").statusCode() == 200)

        val outside = changeManagedNickname(bearer, outOfScope.serviceId.value, "Nem")
        check(outside.statusCode() == 404)
        check(errorCode(outside) == "USER_NOT_FOUND")

        val peerResponse = changeManagedNickname(bearer, peer.serviceId.value, "Nem")
        check(peerResponse.statusCode() == 403)
        check(errorCode(peerResponse) == "USER_NOT_MANAGEABLE")
    }

    @Test
    fun `nickname length is bounded and idempotent retry does not pad the audit trail`() {
        val admin = createSuperAdmin.create()
        completeInitialChange(admin.serviceId, admin.temporaryCredential)
        val bearer = loginSuccessfully(admin.serviceId).accessToken
        val target = givenUser()

        val tooLong = changeManagedNickname(bearer, target.serviceId.value, "🚂".repeat(65))
        check(tooLong.statusCode() == 400)
        check(errorCode(tooLong) == "VALIDATION_ERROR")

        check(changeManagedNickname(bearer, target.serviceId.value, "Levente").statusCode() == 200)
        check(changeManagedNickname(bearer, target.serviceId.value, "Levente").statusCode() == 200)

        val count = jdbc.sql(
            "SELECT COUNT(*) FROM audit_events WHERE event_type = 'USER_NICKNAME_CHANGED' AND target_id = :targetId",
        ).param("targetId", target.id).query(Int::class.java).single()
        check(count == 1)

        val metadata = jdbc.sql(
            "SELECT metadata::text FROM audit_events WHERE event_type = 'USER_NICKNAME_CHANGED' AND target_id = :targetId",
        ).param("targetId", target.id).query(String::class.java).single()
        check(metadata.contains("Levente"))
    }

    @Test
    fun `nicknames PostgreSQL cannot store are rejected as validation errors and change nothing`() {
        val admin = createSuperAdmin.create()
        completeInitialChange(admin.serviceId, admin.temporaryCredential)
        val bearer = loginSuccessfully(admin.serviceId).accessToken
        val target = givenUser()

        // Raw JSON text on purpose (`\\u0000` is a JSON escape, not a Kotlin one): a NUL and an unpaired
        // UTF-16 surrogate cannot be stored in a PostgreSQL text/jsonb value, so they must be refused up
        // front as validation errors instead of surfacing as a 500 from the database.
        listOf("a\\u0000b", "a\\ud800b", "\\udc00").forEach { escaped ->
            val managed = post(
                "/api/v1/service/user-management/users/${target.serviceId.value}/nickname",
                """{"nickname":"$escaped"}""",
                bearer,
            )
            check(managed.statusCode() == 400) { "$escaped: ${managed.statusCode()} ${managed.body()}" }
            check(errorCode(managed) == "VALIDATION_ERROR")

            val own = post("/api/v1/service/account/nickname", """{"nickname":"$escaped"}""", bearer)
            check(own.statusCode() == 400) { "$escaped: ${own.statusCode()} ${own.body()}" }
            check(errorCode(own) == "VALIDATION_ERROR")
        }

        check(users.findById(target.id)?.nickname == null)
        check(users.findByServiceId(admin.serviceId)?.nickname == null)
        val events = jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE event_type = 'USER_NICKNAME_CHANGED'")
            .query(Int::class.java).single()
        check(events == 0)
    }

    @Test
    fun `removing a nickname through the managed endpoint audits one change and a repeated removal is idempotent`() {
        val admin = createSuperAdmin.create()
        completeInitialChange(admin.serviceId, admin.temporaryCredential)
        val bearer = loginSuccessfully(admin.serviceId).accessToken
        val target = givenUser()

        check(changeManagedNickname(bearer, target.serviceId.value, "Levente").statusCode() == 200)
        val removed = changeManagedNickname(bearer, target.serviceId.value, "  ")
        check(removed.statusCode() == 200)
        check(json(removed).get("nickname").isNull)
        check(changeManagedNickname(bearer, target.serviceId.value, null).statusCode() == 200)
        check(users.findById(target.id)?.nickname == null)

        val count = jdbc.sql(
            "SELECT COUNT(*) FROM audit_events WHERE event_type = 'USER_NICKNAME_CHANGED' AND target_id = :targetId",
        ).param("targetId", target.id).query(Int::class.java).single()
        check(count == 2) { "set + remove = 2 audit events, a repeated removal adds none, got $count" }
    }

    @Test
    fun `the managed endpoint never lets an actor change their own nickname`() {
        val admin = createSuperAdmin.create()
        completeInitialChange(admin.serviceId, admin.temporaryCredential)
        val adminBearer = loginSuccessfully(admin.serviceId).accessToken
        val self = changeManagedNickname(adminBearer, admin.serviceId.value, "Én")
        check(self.statusCode() == 403)
        check(errorCode(self) == "USER_NOT_MANAGEABLE")

        val moderator = givenUser(role = UserRole.MODERATOR)
        val modSelf = changeManagedNickname(loginSuccessfully(moderator.serviceId).accessToken, moderator.serviceId.value, "Én")
        // 403 (visible but not manageable) or 404 (a peer outside the actor's scope is not visible): never 200.
        check(modSelf.statusCode() == 403 || modSelf.statusCode() == 404) { "${modSelf.statusCode()} ${modSelf.body()}" }
        check(users.findById(moderator.id)?.nickname == null)
        check(users.findByServiceId(admin.serviceId)?.nickname == null)
    }

    private fun changeOwnNickname(bearer: String, nickname: String?) = post(
        "/api/v1/service/account/nickname",
        objectMapper.writeValueAsString(mapOf("nickname" to nickname)),
        bearer,
    )

    private fun changeManagedNickname(bearer: String, serviceId: String, nickname: String?) = post(
        "/api/v1/service/user-management/users/$serviceId/nickname",
        objectMapper.writeValueAsString(mapOf("nickname" to nickname)),
        bearer,
    )
}
