package hu.orszem.serviceapp.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "orszem_service_session")

data class StoredSession(val accessToken: String, val expiresAt: Instant)

/**
 * Persists the Service App access token in DataStore. The password is never stored.
 * On logout / 401 the token is cleared (DEMO_V1_SCREENS §4).
 */
@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val tokenKey = stringPreferencesKey("access_token")
    private val expiresKey = stringPreferencesKey("expires_at")

    val session: Flow<StoredSession?> = context.dataStore.data.map { prefs ->
        val token = prefs[tokenKey] ?: return@map null
        val expires = prefs[expiresKey]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@map null
        StoredSession(token, expires)
    }

    suspend fun save(token: String, expiresAt: Instant) {
        context.dataStore.edit {
            it[tokenKey] = token
            it[expiresKey] = expiresAt.toString()
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
