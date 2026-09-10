package hu.orszembejelento.backend.usermanagement

import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import hu.orszembejelento.backend.support.AbstractUserManagementIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Regression: request-DTO deserialization must honour Kotlin primary-constructor default
 * values when a JSON field is omitted.
 *
 * Without Jackson's Kotlin module, a data class is bound as a plain Java bean with no
 * knowledge of constructor defaults, so an omitted field arrives as Java `null`. The
 * primary constructor then rejects it - a primitive slot (`Boolean`) fails databind with
 * "Cannot map `null` into type `boolean`", a non-null reference slot (`List<UUID>`,
 * `String`) trips Kotlin's generated non-null check - and a request that should have
 * succeeded with the default value is refused instead.
 *
 * These go through the real HTTP + controller + message-converter path (not an isolated
 * ObjectMapper), because that converter chain is exactly what the fix has to cover.
 *
 * The exhaustive positive/negative matrix for each endpoint lives in the other *IT classes
 * in this package; this class only pins the "field omitted" shape they never send.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class KotlinJsonDefaultsRegressionIT : AbstractUserManagementIntegrationTest() {

    private fun adminBearer(): String {
        val admin = createSuperAdmin.create()
        completeInitialChange(admin.serviceId, admin.temporaryCredential)
        return loginSuccessfully(admin.serviceId).accessToken
    }

    private fun globalFlagOf(serviceId: String): Boolean =
        jdbc.sql("SELECT global_area_access FROM users WHERE service_id = :id").param("id", serviceId).query(Boolean::class.java).single()

    private fun areaCountOf(serviceId: String): Int =
        jdbc.sql("SELECT COUNT(*) FROM user_service_areas usa JOIN users u ON u.id = usa.user_id WHERE u.service_id = :id")
            .param("id", serviceId).query(Int::class.java).single()

    @Test
    fun `create user - globalAreaAccess sent as false succeeds (baseline)`() {
        val bearer = adminBearer()
        val area = givenArea()

        val response = post(
            "/api/v1/service/user-management/users",
            """{"role":"SERVICE_USER","areaIds":["${area.id}"],"globalAreaAccess":false}""",
            bearer,
        )

        check(response.statusCode() == 200) { response.body() }
        val serviceId = json(response).get("serviceId").asText()
        check(!globalFlagOf(serviceId))
        check(areaCountOf(serviceId) == 1)
    }

    @Test
    fun `create user - globalAreaAccess omitted succeeds and defaults to false`() {
        val bearer = adminBearer()
        val area = givenArea()

        // Byte-for-byte the baseline request minus the globalAreaAccess field - exactly what
        // kotlinx.serialization on the clients emits, since false is that property's default.
        val response = post(
            "/api/v1/service/user-management/users",
            """{"role":"SERVICE_USER","areaIds":["${area.id}"]}""",
            bearer,
        )

        check(response.statusCode() == 200) { "omitting globalAreaAccess must behave as false, got: ${response.statusCode()} ${response.body()}" }
        val serviceId = json(response).get("serviceId").asText()
        check(!globalFlagOf(serviceId)) { "the omitted default must persist as global_area_access = false" }
        check(areaCountOf(serviceId) == 1) { "the requested area must still be granted" }
        check(json(response).get("mustChangePassword").asBoolean())
    }

    @Test
    fun `create user - areaIds omitted behaves as an empty list`() {
        val adminBearer = adminBearer()

        // A SUPER_ADMIN creating a MODERATOR with global access needs no areas; omitting
        // areaIds must resolve to emptyList(), not null (which would NPE downstream).
        val moderator = post(
            "/api/v1/service/user-management/users",
            """{"role":"MODERATOR","globalAreaAccess":true}""",
            adminBearer,
        )
        check(moderator.statusCode() == 200) { "omitting areaIds must behave as an empty list, got: ${moderator.statusCode()} ${moderator.body()}" }
        check(areaCountOf(json(moderator).get("serviceId").asText()) == 0)

        // And emptyList() still flows into the MODERATOR-actor "at least one area" rule
        // (§55) - i.e. the omitted field reaches the domain as [], not as a null NPE.
        val area = givenArea()
        val moderatorActor = givenUser(role = UserRole.MODERATOR)
        assignArea(moderatorActor.id, area.id)
        val moderatorBearer = loginSuccessfully(moderatorActor.serviceId).accessToken

        val unscoped = post(
            "/api/v1/service/user-management/users",
            """{"role":"SERVICE_USER"}""",
            moderatorBearer,
        )
        check(unscoped.statusCode() == 409) { unscoped.body() }
        check(errorCode(unscoped) == "USER_REQUIRES_SERVICE_AREA")
    }

    @Test
    fun `create user - role omitted is a clean validation error, never a 500`() {
        val bearer = adminBearer()
        val area = givenArea()

        val response = post(
            "/api/v1/service/user-management/users",
            """{"areaIds":["${area.id}"],"globalAreaAccess":false}""",
            bearer,
        )

        // role defaults to "" -> @Pattern rejects it up front. The point is that an omitted
        // field never reaches the use case as a Kotlin null.
        check(response.statusCode() == 400) { "omitted role must be a 400, got: ${response.statusCode()} ${response.body()}" }
        check(errorCode(response) == "VALIDATION_ERROR")
    }

    @Test
    fun `change role - role omitted is a clean validation error`() {
        val bearer = adminBearer()
        val target = givenUser()

        val response = post(
            "/api/v1/service/user-management/users/${target.serviceId.value}/role",
            """{}""",
            bearer,
        )

        check(response.statusCode() == 400) { "omitted role must be a 400, got: ${response.statusCode()} ${response.body()}" }
        check(errorCode(response) == "VALIDATION_ERROR")
        check(jdbc.sql("SELECT role FROM users WHERE id = :id").param("id", target.id).query(String::class.java).single() == "SERVICE_USER") {
            "a rejected role change must not have mutated the user"
        }
    }
}
