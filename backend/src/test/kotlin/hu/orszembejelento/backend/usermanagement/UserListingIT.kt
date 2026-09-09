package hu.orszembejelento.backend.usermanagement

import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import hu.orszembejelento.backend.support.AbstractUserManagementIntegrationTest
import hu.orszembejelento.backend.usermanagement.application.UserListingUseCase
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/** Real-PostgreSQL list/detail tests (brief §55). */
@Import(AbstractAuthIntegrationTest.Containers::class)
class UserListingIT : AbstractUserManagementIntegrationTest() {

    private fun adminBearer(): String {
        val admin = createSuperAdmin.create()
        completeInitialChange(admin.serviceId, admin.temporaryCredential)
        return loginSuccessfully(admin.serviceId).accessToken
    }

    @Test
    fun `listing is ordered by service id ascending and paginates deterministically`() {
        val fixtures = (1..5).map { givenUser() }
        val fixtureServiceIds: Set<String> = fixtures.map { it.serviceId.value }.toSet()
        val bearer = adminBearer()

        val page1 = listUsers(bearer, page = 0, size = 3)
        check(page1.statusCode() == 200) { page1.body() }
        val page1Ids: List<String> = json(page1).get("items").asList().map { it.get("serviceId").asText() }

        val page2 = listUsers(bearer, page = 1, size = 3)
        val page2Ids: List<String> = json(page2).get("items").asList().map { it.get("serviceId").asText() }

        // The admin itself is also in the users table (role SUPER_ADMIN) - filter to the
        // fixtures under test to avoid depending on where the admin's own id sorts.
        val allSeen: List<String> = (page1Ids + page2Ids).filter { fixtureServiceIds.contains(it) }
        check(allSeen == allSeen.sorted()) { "must be service-id ascending across pages: $allSeen" }
        check(allSeen.toSet().size == allSeen.size) { "no id should repeat across pages" }
    }

    @Test
    fun `size is clamped to the hard maximum`() {
        val bearer = adminBearer()
        val response = listUsers(bearer, size = 5000)
        check(response.statusCode() == 200)
        check(json(response).get("size").asInt() == UserListingUseCase.MAX_PAGE_SIZE)
    }

    @Test
    fun `serviceId query filters by substring`() {
        val target = givenUser()
        val bearer = adminBearer()

        val response = listUsers(bearer, query = target.serviceId.value.removePrefix("SZ-"))
        check(response.statusCode() == 200) { response.body() }
        val ids = json(response).get("items").asList().map { it.get("serviceId").asText() }
        check(ids.contains(target.serviceId.value))
    }

    @Test
    fun `role and status filters narrow the result`() {
        val moderator = givenUser(role = UserRole.MODERATOR)
        val deactivated = givenUser(status = hu.orszembejelento.backend.identity.domain.UserStatus.DEACTIVATED)
        val bearer = adminBearer()

        val moderators = listUsers(bearer, role = "MODERATOR")
        val moderatorIds = json(moderators).get("items").asList().map { it.get("serviceId").asText() }
        check(moderatorIds.contains(moderator.serviceId.value))
        check(!moderatorIds.contains(deactivated.serviceId.value))

        val deactivatedList = listUsers(bearer, status = "DEACTIVATED")
        val deactivatedIds = json(deactivatedList).get("items").asList().map { it.get("serviceId").asText() }
        check(deactivatedIds.contains(deactivated.serviceId.value))
        check(!deactivatedIds.contains(moderator.serviceId.value))
    }

    @Test
    fun `area filter narrows to users assigned to that area`() {
        val area = givenArea()
        val inArea = givenUser()
        assignArea(inArea.id, area.id)
        val outsideArea = givenUser()
        val bearer = adminBearer()

        val response = listUsers(bearer, areaId = area.id)
        val ids = json(response).get("items").asList().map { it.get("serviceId").asText() }
        check(ids.contains(inArea.serviceId.value))
        check(!ids.contains(outsideArea.serviceId.value))
    }

    @Test
    fun `SUPER_ADMIN sees every role, including other SUPER_ADMINs`() {
        val serviceUser = givenUser()
        val moderator = givenUser(role = UserRole.MODERATOR)
        val otherAdmin = createSuperAdmin.create()
        val bearer = adminBearer()

        val response = listUsers(bearer, size = 100)
        val ids = json(response).get("items").asList().map { it.get("serviceId").asText() }
        check(ids.contains(serviceUser.serviceId.value))
        check(ids.contains(moderator.serviceId.value))
        check(ids.contains(otherAdmin.serviceId.value)) { "a SUPER_ADMIN must see other SUPER_ADMIN rows" }
    }

    @Test
    fun `a territorial moderator sees only scoped users, overlapping peers, and never an unrelated target`() {
        val ownArea = givenArea()
        val otherArea = givenArea()

        val moderator = givenUser(role = UserRole.MODERATOR)
        assignArea(moderator.id, ownArea.id)

        val scopedUser = givenUser()
        assignArea(scopedUser.id, ownArea.id)

        val overlappingPeer = givenUser(role = UserRole.MODERATOR)
        assignArea(overlappingPeer.id, ownArea.id)

        val unrelatedUser = givenUser()
        assignArea(unrelatedUser.id, otherArea.id)

        val superAdmin = createSuperAdmin.create()

        val bearer = loginSuccessfully(moderator.serviceId).accessToken
        val response = listUsers(bearer, size = 100)
        check(response.statusCode() == 200) { response.body() }
        val items = json(response).get("items").asList()
        val ids = items.map { it.get("serviceId").asText() }

        check(ids.contains(scopedUser.serviceId.value))
        check(ids.contains(overlappingPeer.serviceId.value))
        check(!ids.contains(unrelatedUser.serviceId.value)) { "an unrelated territorial account must stay invisible" }
        check(!ids.contains(superAdmin.serviceId.value)) { "SUPER_ADMIN must be hidden from a moderator" }

        val peerRow = items.first { it.get("serviceId").asText() == overlappingPeer.serviceId.value }
        check(!peerRow.get("canManage").asBoolean()) { "a peer moderator is read-only" }
    }

    @Test
    fun `detail is existence-safe - a nonexistent and an out-of-scope target both return USER_NOT_FOUND`() {
        val moderator = givenUser(role = UserRole.MODERATOR)
        val unrelatedUser = givenUser()
        val bearer = loginSuccessfully(moderator.serviceId).accessToken

        val nonexistent = userDetail(bearer, "SZ-999999")
        val outOfScope = userDetail(bearer, unrelatedUser.serviceId.value)

        check(nonexistent.statusCode() == 404)
        check(outOfScope.statusCode() == 404)
        check(errorCode(nonexistent) == "USER_NOT_FOUND")
        check(errorCode(outOfScope) == "USER_NOT_FOUND")
    }

    @Test
    fun `a visible but non-manageable peer moderator returns 200 with canManage false on detail`() {
        val area = givenArea()
        val moderator = givenUser(role = UserRole.MODERATOR)
        assignArea(moderator.id, area.id)
        val peer = givenUser(role = UserRole.MODERATOR)
        assignArea(peer.id, area.id)
        val bearer = loginSuccessfully(moderator.serviceId).accessToken

        val response = userDetail(bearer, peer.serviceId.value)
        check(response.statusCode() == 200) { response.body() }
        check(!json(response).get("canManage").asBoolean())
    }

    @Test
    fun `no sensitive field is ever serialized`() {
        val target = givenUser()
        val bearer = adminBearer()

        val response = userDetail(bearer, target.serviceId.value)
        val body = response.body()
        listOf("passwordHash", "password_hash", "accessSecretHash", "refreshToken", "sessionId", "auditEvents").forEach { field ->
            check(!body.contains(field, ignoreCase = true)) { "response must not mention $field: $body" }
        }
    }

    @Test
    fun `a SERVICE_USER cannot list or read any user`() {
        val actor = givenUser()
        val bearer = loginSuccessfully(actor.serviceId).accessToken

        check(listUsers(bearer).statusCode() == 403)
        check(userDetail(bearer, actor.serviceId.value).statusCode() == 403)
    }
}
