package hu.orszembejelento.app.ui.newreport

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import hu.orszembejelento.app.R
import hu.orszembejelento.app.location.LocationOffDialog
import hu.orszembejelento.app.location.openLocationSourceSettingsIfResolvable
import hu.orszembejelento.app.report.domain.LineAnswer
import hu.orszembejelento.app.ui.PublicPalette
import hu.orszembejelento.app.ui.components.RoundIconButton
import hu.orszembejelento.app.ui.components.StatusPill
import hu.orszembejelento.app.ui.components.PillTone
import hu.orszembejelento.app.ui.components.StepIndicator

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

    Column(modifier = Modifier.fillMaxSize().background(PublicPalette.Background)) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (state.step != ReportStep.SUCCESS) {
                NewReportTopBar(state.step, onClose = onNavigateHome, onBack = viewModel::onBackToStep1)
                StepIndicator(
                    steps = listOf(stringResource(R.string.step1_title), stringResource(R.string.step2_title)),
                    activeIndex = if (state.step == ReportStep.ALAPADATOK) 0 else 1,
                    modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
                )
            }

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
private fun NewReportTopBar(step: ReportStep, onClose: () -> Unit, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.nav_new_report), style = MaterialTheme.typography.titleLarge)
        if (step == ReportStep.ALAPADATOK) {
            RoundIconButton(icon = Icons.Filled.Close, contentDescription = stringResource(R.string.action_home), onClick = onClose)
        } else {
            RoundIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), onClick = onBack)
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
            ReportStep.ALAPADATOK -> Button(
                onClick = viewModel::onProceedToStep2,
                enabled = state.step1Valid,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = PublicPalette.Primary),
            ) {
                Text(stringResource(R.string.action_next))
            }
            ReportStep.ESEMENY -> Button(
                onClick = viewModel::onSubmit,
                enabled = state.step2Valid && !state.submitting,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = PublicPalette.Primary),
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(modifier = Modifier.padding(2.dp), color = PublicPalette.OnPrimary)
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
    StatusPill(text = stringResource(textRes), tone = PillTone.ERROR, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}
