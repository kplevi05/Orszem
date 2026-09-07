package hu.orszembejelento.backend.identity

import hu.orszembejelento.backend.identity.application.ResetSuperAdminPasswordUseCase
import hu.orszembejelento.backend.identity.domain.TemporaryCredentialGenerator
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.domain.UserStatus
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import

@Import(AbstractAuthIntegrationTest.Containers::class)
class SuperAdminMaintenanceIT : AbstractAuthIntegrationTest() {

    @Autowired
    private lateinit var resetSuperAdminPassword: ResetSuperAdminPasswordUseCase

    @Test
    fun `creation produces an active administrator owing a password change`() {
        val provisioned = createSuperAdmin.create()

        val user = users.findByServiceId(provisioned.serviceId)!!
        check(user.role == UserRole.SUPER_ADMIN)
        check(user.status == UserStatus.ACTIVE)
        check(user.mustChangePassword) { "a provisioned account must change its password first" }
        check(user.passwordChangedAt == null)
    }

    @Test
    fun `the temporary credential is never persisted in plaintext`() {
        val provisioned = createSuperAdmin.create()
        val canonical = TemporaryCredentialGenerator.normalize(provisioned.temporaryCredential)

        val user = users.findByServiceId(provisioned.serviceId)!!
        check(user.passwordHash.startsWith("\$argon2id\$")) { "must be an Argon2id hash" }
        check(!user.passwordHash.contains(canonical)) { "the credential must not appear in the hash" }
        check(!user.passwordHash.contains(provisioned.temporaryCredential))

        // Nowhere else in the database either — not in audit metadata, not in any column.
        val everything = jdbc.sql(
            "SELECT COALESCE(string_agg(metadata::text, ' '), '') FROM audit_events",
        ).query(String::class.java).single().orEmpty() +
            jdbc.sql("SELECT COALESCE(string_agg(password_hash, ' '), '') FROM users")
                .query(String::class.java).single().orEmpty()

        check(!everything.contains(canonical)) { "the credential leaked into stored data" }
        check(!everything.contains(provisioned.temporaryCredential))
    }

    @Test
    fun `creation is audited as a system action without the credential`() {
        val provisioned = createSuperAdmin.create()

        val rows = jdbc.sql(
            """
            SELECT event_type, actor_type, actor_user_id, metadata::text AS metadata
              FROM audit_events WHERE event_type = 'SUPER_ADMIN_CREATED'
            """.trimIndent(),
        ).query { rs, _ ->
            listOf(
                rs.getString("event_type"),
                rs.getString("actor_type"),
                rs.getString("actor_user_id") ?: "null",
                rs.getString("metadata"),
            )
        }.list()

        check(rows.size == 1) { "expected one creation event, got $rows" }
        val (eventType, actorType, actorUserId, metadata) = listOf(rows[0][0], rows[0][1], rows[0][2], rows[0][3])

        check(eventType == "SUPER_ADMIN_CREATED")
        // Created by the maintenance CLI, so there is no acting user.
        check(actorType == "SYSTEM")
        check(actorUserId == "null")
        check(metadata.contains("MAINTENANCE_CLI"))
        check(metadata.contains(provisioned.serviceId.value))
        check(!metadata.contains(TemporaryCredentialGenerator.normalize(provisioned.temporaryCredential)))
    }

    @Test
    fun `the provisioned credential actually works end to end`() {
        val provisioned = createSuperAdmin.create()

        // Exactly what an operator would do: read the printed credential and sign in.
        val login = post(
            "/api/v1/service/auth/login",
            """{"serviceId":"${provisioned.serviceId.value}","password":${
                objectMapper.writeValueAsString(provisioned.temporaryCredential)
            }}""",
        )
        check(login.statusCode() == 403) { "expected the forced change, got ${login.body()}" }
        check(errorCode(login) == "PASSWORD_CHANGE_REQUIRED")

        val completed = post(
            "/api/v1/service/auth/complete-password-change",
            """{"serviceId":"${provisioned.serviceId.value}","temporaryPassword":${
                objectMapper.writeValueAsString(provisioned.temporaryCredential)
            },"newPassword":${objectMapper.writeValueAsString(STRONG_PASSWORD)}}""",
        )
        check(completed.statusCode() == 200) { "expected 200, got ${completed.body()}" }

        val me = get("/api/v1/service/account/me", credentialsFrom(completed).accessToken)
        check(json(me).get("role").asText() == "SUPER_ADMIN")
    }

    @Test
    fun `reset issues a new credential, forces a change and revokes every session`() {
        val provisioned = createSuperAdmin.create()
        post(
            "/api/v1/service/auth/complete-password-change",
            """{"serviceId":"${provisioned.serviceId.value}","temporaryPassword":${
                objectMapper.writeValueAsString(provisioned.temporaryCredential)
            },"newPassword":${objectMapper.writeValueAsString(STRONG_PASSWORD)}}""",
        )
        val active = loginSuccessfully(provisioned.serviceId, STRONG_PASSWORD)
        val userId = users.findByServiceId(provisioned.serviceId)!!.id
        // Two sessions: one returned by completing the password change, one from the login.
        check(activeSessionCount(userId) == 2) { "expected two live sessions before the reset" }

        val reset = resetSuperAdminPassword.reset(provisioned.serviceId.value)

        check(reset.serviceId == provisioned.serviceId) { "reset must not change the identity" }
        check(reset.temporaryCredential != provisioned.temporaryCredential)

        val user = users.findByServiceId(provisioned.serviceId)!!
        check(user.mustChangePassword)
        check(user.role == UserRole.SUPER_ADMIN) { "reset must not change the role" }

        // Every existing session dies, and the old password stops working.
        check(activeSessionCount(userId) == 0)
        check(get("/api/v1/service/account/me", active.accessToken).statusCode() == 401)
        check(login(provisioned.serviceId, STRONG_PASSWORD).statusCode() == 401)

        check(auditEventTypes().contains("SUPER_ADMIN_PASSWORD_RESET"))
    }

    @Test
    fun `reset refuses any account that is not a super admin`() {
        val ordinary = givenUser(role = UserRole.SERVICE_USER)
        val moderator = givenUser(role = UserRole.MODERATOR)

        listOf(ordinary, moderator).forEach { user ->
            assertThrows<ResetSuperAdminPasswordUseCase.NotASuperAdminException> {
                resetSuperAdminPassword.reset(user.serviceId.value)
            }
            // The refusal must change nothing at all.
            check(!users.findById(user.id)!!.mustChangePassword) { "a refused reset must not touch the account" }
        }
    }

    @Test
    fun `reset refuses an unknown or malformed service id`() {
        assertThrows<ResetSuperAdminPasswordUseCase.NotASuperAdminException> {
            resetSuperAdminPassword.reset("SZ-999999")
        }
        assertThrows<ResetSuperAdminPasswordUseCase.NotASuperAdminException> {
            resetSuperAdminPassword.reset("not-a-service-id")
        }
    }

    @Test
    fun `reset does not reactivate a deactivated administrator`() {
        val provisioned = createSuperAdmin.create()
        jdbc.sql("UPDATE users SET status = 'DEACTIVATED' WHERE service_id = :sid")
            .param("sid", provisioned.serviceId.value).update()

        // Recovering a password must not quietly re-enable an account somebody disabled.
        assertThrows<ResetSuperAdminPasswordUseCase.NotASuperAdminException> {
            resetSuperAdminPassword.reset(provisioned.serviceId.value)
        }
        check(users.findByServiceId(provisioned.serviceId)!!.status == UserStatus.DEACTIVATED)
    }

    @Test
    fun `there is no HTTP route to any maintenance action`() {
        // Administrator creation and recovery are gated on shell access, never exposed.
        listOf(
            "/api/v1/service/auth/create-super-admin",
            "/api/v1/service/admin/create-super-admin",
            "/api/v1/admin/super-admin",
            "/api/v1/service/account/reset-super-admin-password",
            "/api/v1/maintenance/create-super-admin",
        ).forEach { path ->
            val response = post(path, "{}")
            check(response.statusCode() in listOf(401, 403, 404, 405)) {
                "$path must not be a working endpoint, got ${response.statusCode()}"
            }
            check(!response.body().contains("temporaryCredential")) {
                "$path returned something credential-shaped"
            }
        }

        // And no administrator was created by any of those probes.
        val admins = jdbc.sql("SELECT COUNT(*) FROM users WHERE role = 'SUPER_ADMIN'")
            .query(Int::class.java).single()
        check(admins == 0) { "no maintenance action may be reachable over HTTP" }
    }
}
