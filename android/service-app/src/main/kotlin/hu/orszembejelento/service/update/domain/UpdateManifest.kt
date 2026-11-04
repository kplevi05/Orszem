package hu.orszembejelento.service.update.domain

import kotlinx.serialization.Serializable

/**
 * The static `latest.json` published next to the signed APK on the owner's server
 * (`/letoltes/service-app/latest.json`). It is not part of the `/api/v1` HTTP contract:
 * it is a plain file uploaded at release time, so publishing a new build never needs a
 * backend change.
 */
@Serializable
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val minSupportedVersionCode: Long = 0,
    val apkUrl: String,
    val apkSha256: String,
    val releaseNotes: String? = null,
)

/** What the app should do about the manifest it just read. */
sealed interface UpdateDecision {
    /** Already up to date (or the manifest is unusable) - nothing is shown. */
    data object UpToDate : UpdateDecision

    /** A newer build exists; the user may postpone it with "Később". */
    data class Optional(val manifest: UpdateManifest) : UpdateDecision

    /** This build is below the published minimum - the app is blocked until it updates. */
    data class Mandatory(val manifest: UpdateManifest) : UpdateDecision
}

object UpdatePolicy {

    private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")

    /**
     * Whether a manifest is safe to act on. The APK must come from the same scheme and host
     * as the manifest itself, so a tampered or mistyped `apkUrl` can never send the device
     * to a third-party download. A malformed checksum is rejected outright because the
     * downloaded file could not be verified against it.
     */
    fun isValid(manifest: UpdateManifest, manifestUrl: String): Boolean {
        if (manifest.versionCode <= 0) return false
        if (manifest.versionName.isBlank()) return false
        if (!SHA256_HEX.matches(manifest.apkSha256)) return false
        val manifestOrigin = origin(manifestUrl) ?: return false
        val apkOrigin = origin(manifest.apkUrl) ?: return false
        return manifestOrigin == apkOrigin
    }

    /**
     * Mandatory beats optional: below [UpdateManifest.minSupportedVersionCode] the update is
     * required. A minimum that is (mis)configured above the published build itself is capped
     * at that build, so a typo can never demand a version that does not exist.
     */
    fun decide(currentVersionCode: Long, manifest: UpdateManifest): UpdateDecision {
        if (manifest.versionCode <= currentVersionCode) return UpdateDecision.UpToDate
        val minimum = minOf(manifest.minSupportedVersionCode, manifest.versionCode)
        return if (currentVersionCode < minimum) {
            UpdateDecision.Mandatory(manifest)
        } else {
            UpdateDecision.Optional(manifest)
        }
    }

    /** `scheme://host[:port]`, lower-cased; null when the URL is not absolute http(s). */
    internal fun origin(url: String): String? {
        val match = Regex("^(https?)://([^/?#]+)", RegexOption.IGNORE_CASE).find(url.trim()) ?: return null
        val scheme = match.groupValues[1].lowercase()
        val authority = match.groupValues[2].lowercase()
        if (authority.contains('@')) return null
        return "$scheme://$authority"
    }
}
