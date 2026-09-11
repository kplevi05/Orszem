package hu.orszembejelento.service.auth.data

import android.content.Context
import hu.orszembejelento.service.BuildConfig
import hu.orszembejelento.service.moderation.data.DefaultModerationRepository
import hu.orszembejelento.service.moderation.data.ModerationApi
import hu.orszembejelento.service.moderation.data.ModerationRepository
import hu.orszembejelento.service.reports.data.CatalogApi
import hu.orszembejelento.service.reports.data.CatalogRepository
import hu.orszembejelento.service.reports.data.DefaultCatalogRepository
import hu.orszembejelento.service.reports.data.DefaultReportWorkflowRepository
import hu.orszembejelento.service.reports.data.ReportWorkflowApi
import hu.orszembejelento.service.reports.data.ReportWorkflowRepository
import hu.orszembejelento.service.usermanagement.data.DefaultUserManagementRepository
import hu.orszembejelento.service.usermanagement.data.UserManagementApi
import hu.orszembejelento.service.usermanagement.data.UserManagementRepository
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Wires the whole networking stack. Plain manual construction: one client, one Retrofit
 * instance shared by every API interface, one repository per feature. A dependency-injection
 * framework would be more machinery than this needs.
 */
object NetworkModule {

    // internal, not private, so a test can pin the exact wire format the backend sees -
    // notably that a request field left at its kotlinx.serialization default is omitted
    // from the JSON (see CreateUserRequestWireFormatTest).
    internal val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * One [Retrofit] instance for the whole app - every feature API (auth, report workflow,
     * user management) is the same server, the same base URL, the same client. Built once
     * and reused, not because construction is expensive, but so there is exactly one
     * networking configuration to reason about.
     */
    private val retrofit: Retrofit by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // Automatic retry is disabled for the whole client because of the refresh
            // endpoint: refresh tokens rotate, so silently re-sending a request that may
            // already have been processed would present a consumed token and get the
            // session revoked. An ambiguous transport failure must surface, not be replayed.
            // The same reasoning extends to every workflow/management mutation this client
            // now also carries - see AuthRepository.withFreshToken and every repository's
            // own "no blind retries" documentation.
            .retryOnConnectionFailure(false)
            // No logging interceptor. Request bodies carry passwords and temporary
            // credentials; none of that may reach logcat.
            .build()

        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    fun authRepository(context: Context, scope: CoroutineScope = CoroutineScope(SupervisorJob())): AuthRepository {
        val store = EncryptedTokenStore(
            // App-private storage, and excluded from cloud backup and device transfer by
            // data_extraction_rules.xml / backup_rules.xml.
            file = File(context.filesDir, EncryptedTokenStore.FILE_NAME),
            crypto = KeystoreCryptoBox(),
        )
        return AuthRepository(api = retrofit.create(AuthApi::class.java), tokenStore = store, scope = scope)
    }

    fun reportWorkflowRepository(auth: AuthRepository): ReportWorkflowRepository =
        DefaultReportWorkflowRepository(api = retrofit.create(ReportWorkflowApi::class.java), auth = auth)

    fun userManagementRepository(auth: AuthRepository): UserManagementRepository =
        DefaultUserManagementRepository(api = retrofit.create(UserManagementApi::class.java), auth = auth)

    fun catalogRepository(): CatalogRepository =
        DefaultCatalogRepository(api = retrofit.create(CatalogApi::class.java))

    fun moderationRepository(auth: AuthRepository): ModerationRepository =
        DefaultModerationRepository(api = retrofit.create(ModerationApi::class.java), auth = auth)
}
