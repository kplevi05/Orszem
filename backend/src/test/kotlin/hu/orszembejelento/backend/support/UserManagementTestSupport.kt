package hu.orszembejelento.backend.support

import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.reference.domain.ServiceArea
import hu.orszembejelento.backend.reference.domain.ServiceAreaStatus
import hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import org.springframework.beans.factory.annotation.Autowired

/**
 * Adds service-area fixtures and the Phase 6 user-management HTTP surface to
 * [AbstractAuthIntegrationTest], for the same real-PostgreSQL reasons that base class
 * documents: row locking order, partial unique indexes and `IN`/array parameter expansion
 * are all genuine PostgreSQL behaviour an H2 suite would not actually exercise.
 */
abstract class AbstractUserManagementIntegrationTest : AbstractAuthIntegrationTest() {

    @Autowired
    protected lateinit var serviceAreas: JdbcServiceAreaRepository

    // ------------------------------------------------------------------------------ fixtures

    protected fun givenArea(name: String = "Area-${UUID.randomUUID()}", status: ServiceAreaStatus = ServiceAreaStatus.ACTIVE): ServiceArea {
        val now = clock.instant()
        val area = ServiceArea(id = UUID.randomUUID(), name = name, status = status, createdAt = now, updatedAt = now)
        serviceAreas.insert(area)
        return area
    }

    protected fun assignArea(userId: UUID, areaId: UUID) {
        serviceAreas.grantAreaIfAbsent(userId, areaId)
    }

    protected fun setGlobalAccess(userId: UUID, value: Boolean) {
        serviceAreas.setGlobalAreaAccess(userId, value)
    }

    // ---------------------------------------------------------------------------- HTTP: users

    protected fun listUsers(
        bearer: String,
        page: Int? = null,
        size: Int? = null,
        role: String? = null,
        status: String? = null,
        query: String? = null,
        areaId: UUID? = null,
    ): HttpResponse<String> {
        val params = buildList {
            page?.let { add("page=$it") }
            size?.let { add("size=$it") }
            role?.let { add("role=$it") }
            status?.let { add("status=$it") }
            query?.let { add("query=${java.net.URLEncoder.encode(it, Charsets.UTF_8)}") }
            areaId?.let { add("areaId=$it") }
        }
        val qs = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return get("/api/v1/service/user-management/users$qs", bearer)
    }

    protected fun userDetail(bearer: String, serviceId: String): HttpResponse<String> =
        get("/api/v1/service/user-management/users/$serviceId", bearer)

    protected fun assignableAreas(bearer: String): HttpResponse<String> =
        get("/api/v1/service/user-management/areas", bearer)

    protected fun createUser(
        bearer: String,
        role: String,
        areaIds: List<UUID> = emptyList(),
        globalAreaAccess: Boolean = false,
    ): HttpResponse<String> = post(
        "/api/v1/service/user-management/users",
        """{"role":"$role","areaIds":${areaIds.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }},"globalAreaAccess":$globalAreaAccess}""",
        bearer,
    )

    protected fun resetPassword(bearer: String, serviceId: String): HttpResponse<String> =
        post("/api/v1/service/user-management/users/$serviceId/password-reset", "", bearer)

    protected fun deactivateUser(bearer: String, serviceId: String): HttpResponse<String> =
        post("/api/v1/service/user-management/users/$serviceId/deactivate", "", bearer)

    protected fun reactivateUser(bearer: String, serviceId: String): HttpResponse<String> =
        post("/api/v1/service/user-management/users/$serviceId/reactivate", "", bearer)

    protected fun changeRole(bearer: String, serviceId: String, role: String): HttpResponse<String> =
        post("/api/v1/service/user-management/users/$serviceId/role", """{"role":"$role"}""", bearer)

    protected fun grantGlobalAccess(bearer: String, serviceId: String): HttpResponse<String> =
        post("/api/v1/service/user-management/users/$serviceId/global-access/grant", "", bearer)

    protected fun revokeGlobalAccess(bearer: String, serviceId: String): HttpResponse<String> =
        post("/api/v1/service/user-management/users/$serviceId/global-access/revoke", "", bearer)

    protected fun grantArea(bearer: String, serviceId: String, areaId: UUID): HttpResponse<String> =
        post("/api/v1/service/user-management/users/$serviceId/areas/$areaId/grant", "", bearer)

    protected fun revokeArea(bearer: String, serviceId: String, areaId: UUID): HttpResponse<String> =
        post("/api/v1/service/user-management/users/$serviceId/areas/$areaId/revoke", "", bearer)

    // ------------------------------------------------------------------------- assertion helpers

    protected fun temporaryCredentialOf(response: HttpResponse<String>): String = json(response).get("temporaryCredential").asText()

    /** Completes the forced initial change for a user created/reset through this module, so tests can then log in normally. */
    protected fun completeInitialChange(serviceId: ServiceId, temporaryCredential: String, newPassword: String = STRONG_PASSWORD): HttpResponse<String> =
        post(
            "/api/v1/service/auth/complete-password-change",
            """{"serviceId":"${serviceId.value}","temporaryPassword":${objectMapper.writeValueAsString(temporaryCredential)},"newPassword":${objectMapper.writeValueAsString(newPassword)}}""",
        )
}
