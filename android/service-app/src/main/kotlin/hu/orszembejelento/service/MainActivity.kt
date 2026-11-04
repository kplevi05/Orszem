package hu.orszembejelento.service

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import hu.orszembejelento.service.auth.data.NetworkModule
import hu.orszembejelento.service.auth.ui.AuthViewModel
import hu.orszembejelento.service.auth.ui.ServiceAuthHost
import hu.orszembejelento.service.reports.data.SharedPrefsActiveWorkAreaStore
import hu.orszembejelento.service.ui.OrszemServiceTheme
import hu.orszembejelento.service.update.data.AndroidApkInstaller
import hu.orszembejelento.service.update.data.AndroidApkVerifier
import hu.orszembejelento.service.update.ui.UpdateGate
import hu.orszembejelento.service.update.ui.UpdateViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val repository = NetworkModule.authRepository(applicationContext)
        val reportRepository = NetworkModule.reportWorkflowRepository(repository)
        val userManagementRepository = NetworkModule.userManagementRepository(repository)
        val catalogRepository = NetworkModule.catalogRepository()
        val moderationRepository = NetworkModule.moderationRepository(repository)
        val areaAdminRepository = NetworkModule.areaAdminRepository(repository)
        val analyticsRepository = NetworkModule.analyticsRepository(repository)
        val auditRepository = NetworkModule.auditRepository(repository)
        val activeWorkAreaStore = SharedPrefsActiveWorkAreaStore(applicationContext)
        val updateRepository = NetworkModule.updateRepository(applicationContext)

        setContent {
            OrszemServiceTheme {
                // Held by the ViewModel store, so the session survives configuration
                // changes; the access token stays in memory and dies with the process.
                val authViewModel: AuthViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            AuthViewModel(repository) as T
                    },
                )
                val updateViewModel: UpdateViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            UpdateViewModel(
                                repository = updateRepository,
                                verifier = AndroidApkVerifier(applicationContext),
                                installer = AndroidApkInstaller(applicationContext),
                                currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                            ) as T
                    },
                )
                Box(modifier = Modifier.fillMaxSize()) {
                    ServiceAuthHost(
                        authViewModel,
                        reportRepository,
                        userManagementRepository,
                        catalogRepository,
                        activeWorkAreaStore,
                        moderationRepository,
                        areaAdminRepository,
                        analyticsRepository,
                        auditRepository,
                    )
                    UpdateGate(updateViewModel)
                }
            }
        }
    }
}
