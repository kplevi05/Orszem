package hu.orszembejelento.service.update

import hu.orszembejelento.service.update.data.ApkInstaller
import hu.orszembejelento.service.update.data.ApkVerifier
import hu.orszembejelento.service.update.data.DownloadResult
import hu.orszembejelento.service.update.data.InstallEvents
import hu.orszembejelento.service.update.data.ManifestResult
import hu.orszembejelento.service.update.data.UpdateRepository
import hu.orszembejelento.service.update.domain.UpdateDecision
import hu.orszembejelento.service.update.domain.UpdateManifest
import hu.orszembejelento.service.update.ui.UpdateError
import hu.orszembejelento.service.update.ui.UpdatePhase
import hu.orszembejelento.service.update.ui.UpdateViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val installEventsFlow = MutableSharedFlow<InstallEvents.Event>()

    @Before
    fun setUpDispatcher() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDownDispatcher() {
        Dispatchers.resetMain()
    }

    private class FakeRepo(
        override var enabled: Boolean = true,
        var manifestResult: ManifestResult = ManifestResult.Unavailable,
        var downloadResult: DownloadResult = DownloadResult.NetworkError,
    ) : UpdateRepository {
        var fetchCalls = 0
        var downloadCalls = 0
        override suspend fun fetchManifest(): ManifestResult {
            fetchCalls++
            return manifestResult
        }
        override suspend fun downloadApk(manifest: UpdateManifest, onProgress: (Float?) -> Unit): DownloadResult {
            downloadCalls++
            onProgress(0.5f)
            return downloadResult
        }
    }

    private class FakeVerifier(var verifyResult: Boolean = true) : ApkVerifier {
        var verifyCalls = 0
        override fun verify(apk: File): Boolean {
            verifyCalls++
            return verifyResult
        }
    }

    private class FakeInstaller(
        var canRequest: Boolean = true,
    ) : ApkInstaller {
        var openSettingsCalls = 0
        val installCalls = mutableListOf<File>()
        override fun canRequestInstalls(): Boolean = canRequest
        override fun openInstallPermissionSettings() { openSettingsCalls++ }
        override fun install(apk: File) { installCalls.add(apk) }
    }

    private val sampleManifest = UpdateManifest(
        versionCode = 11,
        versionName = "2.0.10",
        minSupportedVersionCode = 10,
        apkUrl = "https://orszembejelento.hu/letoltes/service-app/service-app-2.0.10.apk",
        apkSha256 = "e62ade239e0265f5ddc15e3018135ad0a5930d950cbf7390b55d1315d7b303cf",
        releaseNotes = "Hibajavítások",
    )

    @Test
    fun `when updater is disabled, check makes no repository calls`() = runTest {
        val repo = FakeRepo(enabled = false)
        val vm = UpdateViewModel(repo, FakeVerifier(), FakeInstaller(), currentVersionCode = 10, installEvents = installEventsFlow, ioDispatcher = testDispatcher)
        vm.check()
        assertEquals(0, repo.fetchCalls)
        assertEquals(UpdateDecision.UpToDate, vm.state.value.decision)
        assertFalse(vm.state.value.showOptionalDialog)
    }

    @Test
    fun `optional update shows dialog and dismiss postpones it`() = runTest {
        val repo = FakeRepo(manifestResult = ManifestResult.Success(sampleManifest))
        val vm = UpdateViewModel(repo, FakeVerifier(), FakeInstaller(), currentVersionCode = 10, installEvents = installEventsFlow, ioDispatcher = testDispatcher)
        vm.check()
        assertEquals(1, repo.fetchCalls)
        assertTrue(vm.state.value.decision is UpdateDecision.Optional)
        assertTrue(vm.state.value.showOptionalDialog)
        assertFalse(vm.state.value.showMandatoryScreen)

        vm.dismiss()
        assertFalse(vm.state.value.showOptionalDialog)
        assertEquals(11L, vm.state.value.dismissedVersionCode)
    }

    @Test
    fun `mandatory update shows mandatory screen and dismiss does not dismiss it`() = runTest {
        val repo = FakeRepo(manifestResult = ManifestResult.Success(sampleManifest))
        // current versionCode is 9, which is below minSupportedVersionCode = 10
        val vm = UpdateViewModel(repo, FakeVerifier(), FakeInstaller(), currentVersionCode = 9, installEvents = installEventsFlow, ioDispatcher = testDispatcher)
        vm.check()
        assertTrue(vm.state.value.decision is UpdateDecision.Mandatory)
        assertTrue(vm.state.value.showMandatoryScreen)
        assertFalse(vm.state.value.showOptionalDialog)

        vm.dismiss()
        assertTrue(vm.state.value.showMandatoryScreen)
    }

    @Test
    fun `startUpdate opens permission settings if install is not permitted`() = runTest {
        val repo = FakeRepo(manifestResult = ManifestResult.Success(sampleManifest))
        val installer = FakeInstaller(canRequest = false)
        val vm = UpdateViewModel(repo, FakeVerifier(), installer, currentVersionCode = 10, installEvents = installEventsFlow, ioDispatcher = testDispatcher)
        vm.check()
        vm.startUpdate()

        assertEquals(1, installer.openSettingsCalls)
        assertEquals(UpdatePhase.NeedsPermission, vm.state.value.phase)
        assertEquals(0, repo.downloadCalls)

        // When user returns from settings and permission is now granted, check() resumes startUpdate()
        installer.canRequest = true
        repo.downloadResult = DownloadResult.NetworkError
        vm.check()
        assertEquals(1, repo.downloadCalls)
    }

    @Test
    fun `download failure transitions to Failed DOWNLOAD_FAILED`() = runTest {
        val repo = FakeRepo(
            manifestResult = ManifestResult.Success(sampleManifest),
            downloadResult = DownloadResult.NetworkError,
        )
        val vm = UpdateViewModel(repo, FakeVerifier(), FakeInstaller(), currentVersionCode = 10, installEvents = installEventsFlow, ioDispatcher = testDispatcher)
        vm.check()
        vm.startUpdate()

        assertEquals(UpdatePhase.Failed(UpdateError.DOWNLOAD_FAILED), vm.state.value.phase)
    }

    @Test
    fun `checksum mismatch transitions to Failed CHECKSUM_MISMATCH`() = runTest {
        val repo = FakeRepo(
            manifestResult = ManifestResult.Success(sampleManifest),
            downloadResult = DownloadResult.ChecksumMismatch,
        )
        val vm = UpdateViewModel(repo, FakeVerifier(), FakeInstaller(), currentVersionCode = 10, installEvents = installEventsFlow, ioDispatcher = testDispatcher)
        vm.check()
        vm.startUpdate()

        assertEquals(UpdatePhase.Failed(UpdateError.CHECKSUM_MISMATCH), vm.state.value.phase)
    }

    @Test
    fun `verifier rejection transitions to Failed VERIFICATION_FAILED`() = runTest {
        val tempFile = File.createTempFile("test-apk", ".apk").apply { deleteOnExit() }
        val repo = FakeRepo(
            manifestResult = ManifestResult.Success(sampleManifest),
            downloadResult = DownloadResult.Success(tempFile),
        )
        val verifier = FakeVerifier(verifyResult = false)
        val vm = UpdateViewModel(repo, verifier, FakeInstaller(), currentVersionCode = 10, installEvents = installEventsFlow, ioDispatcher = testDispatcher)
        vm.check()
        vm.startUpdate()

        assertEquals(1, verifier.verifyCalls)
        assertEquals(UpdatePhase.Failed(UpdateError.VERIFICATION_FAILED), vm.state.value.phase)
        assertFalse(tempFile.exists())
    }

    @Test
    fun `successful verification hands file to installer`() = runTest {
        val tempFile = File.createTempFile("test-apk", ".apk").apply { deleteOnExit() }
        val repo = FakeRepo(
            manifestResult = ManifestResult.Success(sampleManifest),
            downloadResult = DownloadResult.Success(tempFile),
        )
        val verifier = FakeVerifier(verifyResult = true)
        val installer = FakeInstaller(canRequest = true)
        val vm = UpdateViewModel(repo, verifier, installer, currentVersionCode = 10, installEvents = installEventsFlow, ioDispatcher = testDispatcher)
        vm.check()
        vm.startUpdate()

        assertEquals(1, verifier.verifyCalls)
        assertEquals(1, installer.installCalls.size)
        assertEquals(tempFile, installer.installCalls.first())
        assertEquals(UpdatePhase.Installing, vm.state.value.phase)
    }
}
