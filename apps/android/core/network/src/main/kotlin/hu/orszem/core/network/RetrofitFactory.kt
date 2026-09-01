package hu.orszem.core.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Builds the [OrszemApi] client. `authInterceptor` is supplied only by the
 * Service App (adds the Bearer token and reacts to 401); the Public App passes none.
 */
object OrszemApiFactory {

    fun create(
        baseUrl: String,
        debug: Boolean,
        authInterceptor: Interceptor? = null,
    ): OrszemApi {
        val logging = HttpLoggingInterceptor().apply {
            // Never log bodies/headers in release; headers (Authorization) are redacted regardless.
            level = if (debug) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
            redactHeader("Authorization")
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .apply { authInterceptor?.let { addInterceptor(it) } }
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(client)
            .addConverterFactory(LenientJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OrszemApi::class.java)
    }
}
