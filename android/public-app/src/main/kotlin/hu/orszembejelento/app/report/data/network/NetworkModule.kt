package hu.orszembejelento.app.report.data.network

import hu.orszembejelento.app.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Wires the Public API client. Plain manual construction - no DI framework needed for one Retrofit instance. */
object NetworkModule {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun createApi(baseUrl: String = BuildConfig.API_BASE_URL): PublicApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // No automatic retry: an ambiguous transport failure during a report POST must
            // surface as PENDING and wait for an explicit user retry (§9), never be
            // silently resent by the HTTP client itself.
            .retryOnConnectionFailure(false)
            // No logging interceptor anywhere in this client (§25) - request bodies and
            // the X-Orszem-Report-Access header must never reach logcat.
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PublicApi::class.java)
    }
}
