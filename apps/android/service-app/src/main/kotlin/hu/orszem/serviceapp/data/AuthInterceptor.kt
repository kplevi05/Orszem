package hu.orszem.serviceapp.data

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Adds the Bearer token to every /service call (except login) and reacts to a
 * 401 by clearing the session so the UI returns to the login screen.
 */
class AuthInterceptor @Inject constructor(
    private val sessionManager: SessionManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val isLogin = original.url.encodedPath.endsWith("/service/auth/login")

        val request = if (isLogin) {
            original
        } else {
            sessionManager.currentToken()?.let {
                original.newBuilder().header("Authorization", "Bearer $it").build()
            } ?: original
        }

        val response = chain.proceed(request)
        if (!isLogin && response.code == 401) {
            sessionManager.onUnauthorized()
        }
        return response
    }
}
