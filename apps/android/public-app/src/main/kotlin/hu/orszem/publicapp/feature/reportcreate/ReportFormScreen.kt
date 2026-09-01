package hu.orszem.publicapp.feature.reportcreate

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.orszem.core.designsystem.MessageState
import hu.orszem.core.designsystem.PrimaryButton
import hu.orszem.publicapp.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportFormScreen(
    onSubmitted: () -> Unit,
    onBack: () -> Unit,
    viewModel: ReportFormViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pickerOpen by remember { mutableStateOf(false) }
    var dateTimeOpen by remember { mutableStateOf(false) }

    // GPS permission is requested only on the explicit button press (BUSINESS_RULES §11).
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) viewModel.onLocateRequested() else viewModel.onLocationPermissionDenied()
    }

    LaunchedEffect(state.submitted) { if (state.submitted) onSubmitted() }

    val locationDenied = stringResource(R.string.location_denied)
    val locationFailed = stringResource(R.string.location_failed)
    LaunchedEffect(state.locationMessage) {
        when (state.locationMessage) {
            LocationMessage.DENIED -> snackbar.showSnackbar(locationDenied)
            LocationMessage.FAILED -> snackbar.showSnackbar(locationFailed)
            null -> Unit
        }
    }

    val netError = stringResource(R.string.error_network)
    val genericError = stringResource(R.string.error_generic)
    val retryLabel = stringResource(R.string.action_retry)
    LaunchedEffect(state.submitError) {
        val error = state.submitError ?: return@LaunchedEffect
        val message = if (error == FormErrorReason.NETWORK) netError else genericError
        val result = snackbar.showSnackbar(message = message, actionLabel = retryLabel)
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) viewModel.onRetry()
        viewModel.dismissSubmitError()
    }

    val formatter = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.form_title)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.catalogLoading -> MessageState(title = stringResource(R.string.catalog_loading))
            state.catalogError -> MessageState(
                title = stringResource(R.string.catalog_error),
                actionLabel = retryLabel,
                onAction = viewModel::loadCatalog,
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // When
                Column {
                    Text(stringResource(R.string.form_when), style = MaterialTheme.typography.titleMedium)
                    Text(
                        formatter.format(state.occurredAt.atZone(ZoneId.systemDefault())),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (state.timeError) {
                        Text(stringResource(R.string.error_time_future), color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(onClick = { dateTimeOpen = true }) {
                        Text(stringResource(R.string.form_when_change))
                    }
                }

                // Train
                OutlinedTextField(
                    value = state.trainIdentifier,
                    onValueChange = viewModel::onTrainChanged,
                    label = { Text(stringResource(R.string.form_train)) },
                    placeholder = { Text(stringResource(R.string.form_train_placeholder)) },
                    singleLine = true,
                    isError = state.trainError,
                    supportingText = { if (state.trainError) Text(stringResource(R.string.error_train_required)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                // Settlement + locate
                OutlinedTextField(
                    value = state.settlement,
                    onValueChange = viewModel::onSettlementChanged,
                    label = { Text(stringResource(R.string.form_settlement)) },
                    placeholder = { Text(stringResource(R.string.form_settlement_placeholder)) },
                    singleLine = true,
                    isError = state.settlementError,
                    supportingText = { if (state.settlementError) Text(stringResource(R.string.error_settlement_required)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        locationPermission.launch(
                            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                        )
                    },
                    enabled = !state.locating,
                    modifier = Modifier.semantics { contentDescription = "Helyzet meghatározása" },
                ) {
                    if (state.locating) {
                        CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.MyLocation, contentDescription = null)
                        Spacer(Modifier.height(0.dp))
                        Text("  " + stringResource(R.string.form_locate))
                    }
                }

                // Event type
                Column {
                    Text(stringResource(R.string.form_event), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { pickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(state.selectedEventType?.label ?: stringResource(R.string.form_event_choose))
                    }
                    if (state.eventError) {
                        Text(stringResource(R.string.error_event_required), color = MaterialTheme.colorScheme.error)
                    }
                }

                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    text = stringResource(R.string.form_submit),
                    onClick = viewModel::onSubmit,
                    enabled = state.canSubmit,
                    loading = state.submitting,
                )
            }
        }
    }

    if (pickerOpen) {
        EventPickerSheet(
            categories = state.categories,
            onSelected = {
                viewModel.onEventTypeSelected(it)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }

    if (dateTimeOpen) {
        OccurredAtPickerDialog(
            initial = state.occurredAt,
            onConfirm = {
                viewModel.onOccurredAtChanged(it)
                dateTimeOpen = false
            },
            onDismiss = { dateTimeOpen = false },
        )
    }
}
