package hu.orszembejelento.service.usermanagement.data

import hu.orszembejelento.service.auth.data.AuthRepository
import hu.orszembejelento.service.common.data.ApiResult
import hu.orszembejelento.service.common.data.apiCall

/**
 * The Phase 6 user-management surface every screen/ViewModel depends on.
 *
 * An interface, not a class - see [hu.orszembejelento.service.reports.data.ReportWorkflowRepository]'s
 * identical reasoning: a test implements this directly, no Retrofit/AuthRepository double
 * needed. Production code only ever constructs [DefaultUserManagementRepository], via
 * [hu.orszembejelento.service.auth.data.NetworkModule].
 */
interface UserManagementRepository {
    suspend fun list(page: Int, size: Int, role: String? = null, status: String? = null, query: String? = null, areaId: String? = null): ApiResult<ManagedUserPageResponse>
    suspend fun detail(serviceId: String): ApiResult<ManagedUserResponse>
    suspend fun assignableAreas(): ApiResult<List<AssignableAreaResponse>>
    suspend fun create(role: String, areaIds: List<String>, globalAreaAccess: Boolean): ApiResult<CreateUserResponse>
    suspend fun resetPassword(serviceId: String): ApiResult<PasswordResetResponse>
    suspend fun deactivate(serviceId: String): ApiResult<ManagedUserResponse>
    suspend fun reactivate(serviceId: String): ApiResult<ManagedUserResponse>
    suspend fun changeRole(serviceId: String, role: String): ApiResult<ManagedUserResponse>
    suspend fun grantGlobalAccess(serviceId: String): ApiResult<ManagedUserResponse>
    suspend fun revokeGlobalAccess(serviceId: String): ApiResult<ManagedUserResponse>
    suspend fun grantArea(serviceId: String, areaId: String): ApiResult<ManagedUserResponse>
    suspend fun revokeArea(serviceId: String, areaId: String): ApiResult<ManagedUserResponse>
}

/** Thin wrapper over [UserManagementApi] - see [ReportWorkflowRepository][hu.orszembejelento.service.reports.data.ReportWorkflowRepository]'s identical shape and reasoning. */
class DefaultUserManagementRepository(
    private val api: UserManagementApi,
    private val auth: AuthRepository,
) : UserManagementRepository {

    override suspend fun list(page: Int, size: Int, role: String?, status: String?, query: String?, areaId: String?): ApiResult<ManagedUserPageResponse> =
        apiCall(auth) { bearer -> api.list(bearer, page, size, role, status, query, areaId) }

    override suspend fun detail(serviceId: String): ApiResult<ManagedUserResponse> =
        apiCall(auth) { bearer -> api.detail(bearer, serviceId) }

    override suspend fun assignableAreas(): ApiResult<List<AssignableAreaResponse>> =
        apiCall(auth) { bearer -> api.assignableAreas(bearer) }

    override suspend fun create(role: String, areaIds: List<String>, globalAreaAccess: Boolean): ApiResult<CreateUserResponse> =
        apiCall(auth) { bearer -> api.create(bearer, CreateUserRequest(role, areaIds, globalAreaAccess)) }

    override suspend fun resetPassword(serviceId: String): ApiResult<PasswordResetResponse> =
        apiCall(auth) { bearer -> api.resetPassword(bearer, serviceId) }

    override suspend fun deactivate(serviceId: String): ApiResult<ManagedUserResponse> =
        apiCall(auth) { bearer -> api.deactivate(bearer, serviceId) }

    override suspend fun reactivate(serviceId: String): ApiResult<ManagedUserResponse> =
        apiCall(auth) { bearer -> api.reactivate(bearer, serviceId) }

    override suspend fun changeRole(serviceId: String, role: String): ApiResult<ManagedUserResponse> =
        apiCall(auth) { bearer -> api.changeRole(bearer, serviceId, ChangeRoleRequest(role)) }

    override suspend fun grantGlobalAccess(serviceId: String): ApiResult<ManagedUserResponse> =
        apiCall(auth) { bearer -> api.grantGlobalAccess(bearer, serviceId) }

    override suspend fun revokeGlobalAccess(serviceId: String): ApiResult<ManagedUserResponse> =
        apiCall(auth) { bearer -> api.revokeGlobalAccess(bearer, serviceId) }

    override suspend fun grantArea(serviceId: String, areaId: String): ApiResult<ManagedUserResponse> =
        apiCall(auth) { bearer -> api.grantArea(bearer, serviceId, areaId) }

    override suspend fun revokeArea(serviceId: String, areaId: String): ApiResult<ManagedUserResponse> =
        apiCall(auth) { bearer -> api.revokeArea(bearer, serviceId, areaId) }
}
