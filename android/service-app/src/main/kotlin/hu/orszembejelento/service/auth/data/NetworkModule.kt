package hu.orszembejelento.service.auth.data

import android.content.Context
import hu.orszembejelento.service.BuildConfig
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
 * Wires the authentication stack. Plain manual construction: one client, one Retrofit, one
 * repository. A dependency-injection framework would be more machinery than this needs.
 */
object NetworkModule {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun authRepository(context: Context, scope: CoroutineScope = CoroutineScope(SupervisorJob())): AuthRepository {
        val store = EncryptedTokenStore(
            // App-private storage, and excluded from cloud backup and device transfer by
            // data_extraction_rules.xml / backup_rules.xml.
            file = File(context.filesDir, EncryptedTokenStore.FILE_NAME),
            crypto = KeystoreCryptoBox(),
        )
        return AuthRepository(api = createApi(), tokenStore = store, scope = scope)
    }

    private fun createApi(): AuthApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // Automatic retry is disabled for the whole client because of the refresh
            // endpoint: refresh tokens rotate, so silently re-sending a request that may
            // already have been processed would present a consumed token and get the
            // session revoked. An ambiguous transport failure must surface, not be replayed.
            .retryOnConnectionFailure(false)
            // No logging interceptor. Auth request bodies carry passwords and temporary
            // credentials, and responses carry tokens; none of that may reach logcat.
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AuthApi::class.java)
    }
}
