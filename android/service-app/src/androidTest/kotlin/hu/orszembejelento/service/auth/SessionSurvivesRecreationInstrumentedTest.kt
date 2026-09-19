package hu.orszembejelento.service.auth

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import hu.orszembejelento.service.MainActivity
import hu.orszembejelento.service.auth.data.NetworkModule
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 16 regression: the Service login/session anomaly first seen in Phase 15.
 *
 * The access token lives only in [hu.orszembejelento.service.auth.data.AuthRepository]'s memory,
 * while the ViewModel that owns the auth state is retained across configuration changes. When
 * `MainActivity.onCreate` built a NEW repository on every Activity creation, a rotation (or a
 * font-scale / dark-mode / locale change) left the new screens with an empty in-memory token:
 * their calls reported "no session", the app signed itself out and deleted the stored refresh
 * token, although the backend session was still valid.
 *
 * The repository must therefore be one process-wide instance, and an Activity recreation must
 * keep handing out that same instance.
 */
@RunWith(AndroidJUnit4::class)
class SessionSurvivesRecreationInstrumentedTest {

    @Test
    fun every_call_returns_the_same_process_wide_auth_repository() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertSame(NetworkModule.authRepository(context), NetworkModule.authRepository(context))
    }

    @Test
    fun recreating_the_activity_keeps_using_the_same_auth_repository() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val before = NetworkModule.authRepository(context)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.recreate()
        }

        assertSame(before, NetworkModule.authRepository(context))
    }
}
