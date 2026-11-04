package hu.orszembejelento.service.update

import hu.orszembejelento.service.update.domain.UpdateDecision
import hu.orszembejelento.service.update.domain.UpdateManifest
import hu.orszembejelento.service.update.domain.UpdatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    private val validManifest = UpdateManifest(
        versionCode = 11,
        versionName = "2.0.10",
        minSupportedVersionCode = 10,
        apkUrl = "https://orszembejelento.hu/letoltes/service-app/service-app-2.0.10.apk",
        apkSha256 = "e62ade239e0265f5ddc15e3018135ad0a5930d950cbf7390b55d1315d7b303cf",
        releaseNotes = "Újdonságok...",
    )
    private val manifestUrl = "https://orszembejelento.hu/letoltes/service-app/latest.json"

    @Test
    fun `isValid accepts consistent manifest and same-origin APK URL`() {
        assertTrue(UpdatePolicy.isValid(validManifest, manifestUrl))
    }

    @Test
    fun `isValid rejects foreign host in apkUrl`() {
        val foreign = validManifest.copy(apkUrl = "https://malicious.example.com/fake.apk")
        assertFalse(UpdatePolicy.isValid(foreign, manifestUrl))
    }

    @Test
    fun `isValid rejects invalid sha256 checksum`() {
        assertFalse(UpdatePolicy.isValid(validManifest.copy(apkSha256 = "not-a-sha256"), manifestUrl))
        assertFalse(UpdatePolicy.isValid(validManifest.copy(apkSha256 = "e62ade23"), manifestUrl))
    }

    @Test
    fun `isValid rejects non-positive versionCode or blank versionName`() {
        assertFalse(UpdatePolicy.isValid(validManifest.copy(versionCode = 0), manifestUrl))
        assertFalse(UpdatePolicy.isValid(validManifest.copy(versionCode = -1), manifestUrl))
        assertFalse(UpdatePolicy.isValid(validManifest.copy(versionName = "  "), manifestUrl))
    }

    @Test
    fun `isValid rejects URLs with userinfo`() {
        val withUserInfo = validManifest.copy(apkUrl = "https://user:pass@orszembejelento.hu/app.apk")
        assertFalse(UpdatePolicy.isValid(withUserInfo, manifestUrl))
    }

    @Test
    fun `decide returns UpToDate when current version is equal or newer`() {
        assertEquals(UpdateDecision.UpToDate, UpdatePolicy.decide(11, validManifest))
        assertEquals(UpdateDecision.UpToDate, UpdatePolicy.decide(12, validManifest))
    }

    @Test
    fun `decide returns Optional when current version is at or above minimum but below latest`() {
        val decision = UpdatePolicy.decide(10, validManifest)
        assertTrue(decision is UpdateDecision.Optional)
        assertEquals(validManifest, (decision as UpdateDecision.Optional).manifest)
    }

    @Test
    fun `decide returns Mandatory when current version is below minimum`() {
        val decision = UpdatePolicy.decide(9, validManifest)
        assertTrue(decision is UpdateDecision.Mandatory)
        assertEquals(validManifest, (decision as UpdateDecision.Mandatory).manifest)
    }

    @Test
    fun `decide caps minimum at latest versionCode if misconfigured higher`() {
        val misconfigured = validManifest.copy(versionCode = 11, minSupportedVersionCode = 20)
        // Since minimum is capped at 11, version 10 is below 11 -> Mandatory
        val decision = UpdatePolicy.decide(10, misconfigured)
        assertTrue(decision is UpdateDecision.Mandatory)
        // If current is 11, it is UpToDate
        assertEquals(UpdateDecision.UpToDate, UpdatePolicy.decide(11, misconfigured))
    }
}
