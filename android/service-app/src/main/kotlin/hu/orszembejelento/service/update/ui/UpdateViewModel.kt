package hu.orszembejelento.service.update.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.orszembejelento.service.update.data.ApkInstaller
import hu.orszembejelento.service.update.data.ApkVerifier
import hu.orszembejelento.service.update.data.DownloadResult
import hu.orszembejelento.service.update.data.InstallEvents
import hu.orszembejelento.service.update.data.ManifestResult
import hu.orszembejelento.service.update.data.UpdateRepository
import hu.orszembejelento.service.update.domain.UpdateDecision
import hu.orszembejelento.service.update.domain.UpdateManifest
import hu.orszembejelento.service.update.domain.UpdatePolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class UpdateError { DOWNLOAD_FAILED, TOO_LARGE, CHECKSUM_MISMATCH, VERIFICATION_FAILED, INSTALL_FAILED, INSTALL_ABORTED }

sealed interface UpdatePhase {
    data object Idle : UpdatePhase

    /** Waiting for the user to allow installs from this app in system settings. */
    data object NeedsPermission : UpdatePhase

    /** [progress] is null when the server sent no length. */
    data class Downloading(val progress: Float?) : UpdatePhase
    data object Installing : UpdatePhase
    data class Failed(val error: UpdateError) : UpdatePhase
}

/**
 * Checks the published manifest and drives download -> verify -> system install.
 *
 * Network trouble while *checking* is always silent - the app must keep working offline, so a
 * mandatory update is only enforced once the manifest has actually been read. An update only
 * counts as started after the checksum and signer checks passed; any failure leaves the app as
 * it was and offers a retry.
 */
class UpdateViewModel(
    private val repository: UpdateRepository,
    private val verifier: ApkVerifier,
    private val installer: ApkInstaller,
    private val currentVersionCode: Long,
    private val installEvents: Flow<InstallEvents.Event> = InstallEvents.flow,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minCheckIntervalMillis: Long = CHECK_INTERVAL_MILLIS,
) : ViewModel() {

    data class UiState(
        val decision: UpdateDecision = UpdateDecision.UpToDate,
        val phase: UpdatePhase = UpdatePhase.Idle,
        /** Version the user postponed with "Később" in this process; hides only the optional dialog. */
        val dismissedVersionCode: Long? = null,
    ) {
        val showOptionalDialog: Boolean
            get() = decision is UpdateDecision.Optional && decision.manifest.versionCode != dismissedVersionCode
        val showMandatoryScreen: Boolean
            get() = decision is UpdateDecision.Mandatory
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var lastCheckAt: Long? = null
    private var checkJob: Job? = null
    private var updateJob: Job? = null

    init {
        viewModelScope.launch {
            installEvents.collect { event ->
                val error = when (event) {
                    InstallEvents.Event.Aborted -> UpdateError.INSTALL_ABORTED
                    InstallEvents.Event.Failed -> UpdateError.INSTALL_FAILED
                }
                _state.update { it.copy(phase = UpdatePhase.Failed(error)) }
            }
        }
    }

    /**
     * Reads the manifest at most once per [minCheckIntervalMillis] unless [force]d. Called on
     * every return to the foreground; also re-evaluates the install permission, because the
     * user comes back here from the system settings page.
     */
    fun check(force: Boolean = false) {
        if (_state.value.phase == UpdatePhase.NeedsPermission && installer.canRequestInstalls()) {
            startUpdate()
        }
        if (!repository.enabled) return
        if (checkJob?.isActive == true || updateJob?.isActive == true) return
        val now = clock()
        val last = lastCheckAt
        if (!force && last != null && now - last < minCheckIntervalMillis) return
        lastCheckAt = now
        checkJob = viewModelScope.launch {
            when (val result = withContext(ioDispatcher) { repository.fetchManifest() }) {
                is ManifestResult.Success -> {
                    val decision = UpdatePolicy.decide(currentVersionCode, result.manifest)
                    _state.update { it.copy(decision = decision) }
                }
                // Unreachable or unusable manifest: keep whatever was decided before.
                ManifestResult.Unavailable, ManifestResult.Invalid -> Unit
            }
        }
    }

    fun dismiss() {
        val decision = _state.value.decision
        if (decision is UpdateDecision.Optional && !isBusy()) {
            _state.update { it.copy(dismissedVersionCode = decision.manifest.versionCode, phase = UpdatePhase.Idle) }
        }
    }

    fun startUpdate() {
        val manifest = currentManifest() ?: return
        if (updateJob?.isActive == true) return
        if (!installer.canRequestInstalls()) {
            _state.update { it.copy(phase = UpdatePhase.NeedsPermission) }
            installer.openInstallPermissionSettings()
            return
        }
        updateJob = viewModelScope.launch { downloadAndInstall(manifest) }
    }

    private suspend fun downloadAndInstall(manifest: UpdateManifest) {
        _state.update { it.copy(phase = UpdatePhase.Downloading(progress = null)) }
        val result = withContext(ioDispatcher) {
            repository.downloadApk(manifest) { progress ->
                _state.update { it.copy(phase = UpdatePhase.Downloading(progress)) }
            }
        }
        val file = when (result) {
            is DownloadResult.Success -> result.file
            DownloadResult.NetworkError -> return fail(UpdateError.DOWNLOAD_FAILED)
            DownloadResult.TooLarge -> return fail(UpdateError.TOO_LARGE)
            DownloadResult.ChecksumMismatch -> return fail(UpdateError.CHECKSUM_MISMATCH)
        }
        val verified = withContext(ioDispatcher) { verifier.verify(file) }
        if (!verified) {
            file.delete()
            return fail(UpdateError.VERIFICATION_FAILED)
        }
        _state.update { it.copy(phase = UpdatePhase.Installing) }
        val started = withContext(ioDispatcher) {
            try {
                installer.install(file)
                true
            } catch (_: Exception) {
                false
            }
        }
        if (!started) fail(UpdateError.INSTALL_FAILED)
    }

    private fun fail(error: UpdateError) {
        _state.update { it.copy(phase = UpdatePhase.Failed(error)) }
    }

    private fun isBusy(): Boolean = when (_state.value.phase) {
        is UpdatePhase.Downloading, UpdatePhase.Installing -> true
        else -> false
    }

    private fun currentManifest(): UpdateManifest? = when (val decision = _state.value.decision) {
        is UpdateDecision.Optional -> decision.manifest
        is UpdateDecision.Mandatory -> decision.manifest
        UpdateDecision.UpToDate -> null
    }

    companion object {
        const val CHECK_INTERVAL_MILLIS = 60 * 60 * 1000L
    }
}
