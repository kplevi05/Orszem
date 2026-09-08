package hu.orszembejelento.app.ui.newreport

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import hu.orszembejelento.app.R
import hu.orszembejelento.app.location.LocationOffDialog
import hu.orszembejelento.app.location.openLocationSourceSettingsIfResolvable
import hu.orszembejelento.app.report.domain.LineAnswer

/**
 * The two-step report flow plus its success screen (Phase 5 brief §4-19). GPS permission is
 * requested here, an Activity/Compose concern - [hu.orszembejelento.app.location.LocationAssist]
 * itself never requests a permission.
 */
@Composable
fun NewReportScreen(
    viewModel: NewReportViewModel,
    onNavigateHome: () -> Unit,
    onNavigateHistory: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val activity = LocalActivity.current

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val anyGranted = granted.values.any { it }
        if (anyGranted) {
            viewModel.onLocateMeRequested()
        } else {
            val permanently = activity?.let {
                !it.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) &&
                    !it.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
            } ?: false
            viewModel.onLocationPermissionDenied(permanently)
        }
    }

    fun requestLocate() {
        val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineGranted || coarseGranted) {
            viewModel.onLocateMeRequested()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    if (state.locateStatus == LocateStatus.SERVICES_DISABLED) {
        LocationOffDialog(
            onDismiss = viewModel::onLocateDialogDismissed,
            onOpenSettings = {
                openLocationSourceSettingsIfResolvable(context)
                viewModel.onLocateDialogDismissed()
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (state.step) {
                ReportStep.ALAPADATOK -> Step1Content(state, viewModel, onLocateMe = ::requestLocate)
                ReportStep.ESEMENY -> Step2Content(state, viewModel)
                ReportStep.SUCCESS -> state.success?.let {
                    SuccessContent(
                        info = it,
                        onNewReport = viewModel::onStartNewReport,
                        onViewHistory = onNavigateHistory,
                        onHome = onNavigateHome,
                    )
                }
            }

            state.error?.let { ErrorBanner(it) }
        }

        if (state.step != ReportStep.SUCCESS) {
            NewReportBottomBar(state, viewModel)
        }
    }
}

@Composable
private fun NewReportBottomBar(state: NewReportUiState, viewModel: NewReportViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.step == ReportStep.ESEMENY) {
            OutlinedButton(onClick = viewModel::onBackToStep1) { Text(stringResource(R.string.action_back)) }
        }
        when (state.step) {
            ReportStep.ALAPADATOK -> Button(onClick = viewModel::onProceedToStep2, enabled = state.step1Valid) {
                Text(stringResource(R.string.action_next))
            }
            ReportStep.ESEMENY -> Button(
                onClick = viewModel::onSubmit,
                enabled = state.step2Valid && !state.submitting,
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    Text(stringResource(R.string.action_submit))
                }
            }
            ReportStep.SUCCESS -> {}
        }
    }
}

@Composable
private fun ErrorBanner(reason: UiErrorReason) {
    val textRes = when (reason) {
        UiErrorReason.VALIDATION -> R.string.error_validation
        UiErrorReason.REFERENCE_UNAVAILABLE -> R.string.error_reference_unavailable
        UiErrorReason.NETWORK -> R.string.error_network
        UiErrorReason.CONFLICT -> R.string.error_conflict
        UiErrorReason.LOCAL_STORAGE -> R.string.error_local_storage
        UiErrorReason.ACCESS_LOST -> R.string.error_access_lost
        UiErrorReason.GENERIC -> R.string.error_generic
    }
    Snackbar(modifier = Modifier.padding(16.dp)) {
        Text(stringResource(textRes))
    }
}
