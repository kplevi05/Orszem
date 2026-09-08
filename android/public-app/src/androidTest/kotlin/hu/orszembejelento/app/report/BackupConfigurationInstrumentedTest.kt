package hu.orszembejelento.app.report

import android.content.pm.ApplicationInfo
import android.content.res.XmlResourceParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.orszembejelento.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Item 8 of the owner's Android technical verification gate: the local history database
 * (and the Keystore-encrypted report-access credentials it holds) must never leave the
 * device via cloud backup or device-to-device transfer (Phase 5 brief §24,
 * `data_extraction_rules.xml` / `backup_rules.xml`).
 *
 * [ApplicationInfo.fullBackupContent] and `dataExtractionRulesRes` are not part of the
 * public SDK stub (hidden from `android.jar`, even though the platform populates them at
 * install time), so they cannot be read directly here. Instead this reads the *actual
 * installed* app's `android:allowBackup` flag via the public `ApplicationInfo.flags` API,
 * and parses the *actual packaged* `data_extraction_rules.xml` / `backup_rules.xml`
 * resources to prove every domain is genuinely excluded - not merely that a source file
 * with that name exists somewhere in the repository.
 */
@RunWith(AndroidJUnit4::class)
class BackupConfigurationInstrumentedTest {

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun allowBackupIsDisabledOnTheInstalledApp() {
        val info = targetContext.packageManager.getApplicationInfo(targetContext.packageName, 0)
        assertEquals(
            "android:allowBackup must be false - the installed app must not offer itself for backup at all",
            0,
            info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP,
        )
    }

    @Test
    fun dataExtractionRulesExcludeEveryDomainFromBothCloudBackupAndDeviceTransfer() {
        val excludedDomains = excludedDomains(R.xml.data_extraction_rules, "cloud-backup") +
            excludedDomains(R.xml.data_extraction_rules, "device-transfer")
        // "root" alone is a superset of every other domain, but the resource lists them
        // all explicitly (see the file's own comment) - assert the ones that matter most
        // for this gate are genuinely present, not merely that parsing succeeded.
        for (domain in listOf("root", "database", "sharedpref", "file", "external")) {
            assertTrue(
                "expected domain=\"$domain\" to be excluded from both cloud-backup and device-transfer, found: $excludedDomains",
                excludedDomains.count { it == domain } >= 2,
            )
        }
    }

    @Test
    fun legacyBackupRulesExcludeEveryDomainToo() {
        val excludedDomains = excludedDomains(R.xml.backup_rules, "full-backup-content")
        for (domain in listOf("root", "database", "sharedpref", "file", "external")) {
            assertTrue(
                "expected domain=\"$domain\" to be excluded in the pre-Android-12 backup_rules.xml, found: $excludedDomains",
                excludedDomains.contains(domain),
            )
        }
    }

    /** Collects every `domain` attribute of an `<exclude>` element inside [rootTag], from the given XML resource. */
    private fun excludedDomains(resId: Int, rootTag: String): List<String> {
        val parser: XmlResourceParser = targetContext.resources.getXml(resId)
        val domains = mutableListOf<String>()
        var insideRoot = false
        var event = parser.eventType
        while (event != XmlResourceParser.END_DOCUMENT) {
            if (event == XmlResourceParser.START_TAG) {
                when (parser.name) {
                    rootTag -> insideRoot = true
                    "exclude" -> if (insideRoot) {
                        parser.getAttributeValue(null, "domain")?.let { domains.add(it) }
                    }
                }
            } else if (event == XmlResourceParser.END_TAG && parser.name == rootTag) {
                insideRoot = false
            }
            event = parser.next()
        }
        parser.close()
        return domains
    }
}
