package hu.orszembejelento.service.update.data

import hu.orszembejelento.service.update.domain.UpdateManifest
import hu.orszembejelento.service.update.domain.UpdatePolicy
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface ManifestResult {
    data class Success(val manifest: UpdateManifest) : ManifestResult

    /** No network, timeout, non-2xx: silently skipped, the app keeps working. */
    data object Unavailable : ManifestResult

    /** Reachable but unusable (bad JSON, foreign APK host, malformed checksum). */
    data object Invalid : ManifestResult
}

sealed interface DownloadResult {
    data class Success(val file: File) : DownloadResult
    data object NetworkError : DownloadResult
    data object TooLarge : DownloadResult
    data object ChecksumMismatch : DownloadResult
}

interface UpdateRepository {
    /** False in builds without a manifest URL (debug): the updater stays switched off. */
    val enabled: Boolean

    suspend fun fetchManifest(): ManifestResult

    suspend fun downloadApk(manifest: UpdateManifest, onProgress: (Float?) -> Unit): DownloadResult
}

/**
 * Plain OkHttp, blocking calls - the caller runs them on an IO dispatcher. No auth header is
 * ever attached: the manifest and the APK are public static files.
 */
class HttpUpdateRepository(
    private val client: OkHttpClient,
    private val manifestUrl: String,
    private val downloadDir: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val maxApkBytes: Long = MAX_APK_BYTES,
) : UpdateRepository {

    override val enabled: Boolean get() = manifestUrl.isNotBlank()

    override suspend fun fetchManifest(): ManifestResult {
        if (!enabled) return ManifestResult.Unavailable
        val request = Request.Builder()
            .url(manifestUrl)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return ManifestResult.Unavailable
                val body = response.body.string()
                val manifest = json.decodeFromString(UpdateManifest.serializer(), body)
                if (UpdatePolicy.isValid(manifest, manifestUrl)) ManifestResult.Success(manifest) else ManifestResult.Invalid
            }
        } catch (_: IOException) {
            ManifestResult.Unavailable
        } catch (_: SerializationException) {
            ManifestResult.Invalid
        } catch (_: IllegalArgumentException) {
            ManifestResult.Invalid
        }
    }

    override suspend fun downloadApk(manifest: UpdateManifest, onProgress: (Float?) -> Unit): DownloadResult {
        downloadDir.mkdirs()
        // Never keep a half-written or previously rejected file around.
        downloadDir.listFiles()?.forEach { it.delete() }
        val target = File(downloadDir, APK_FILE_NAME)
        val request = Request.Builder().url(manifest.apkUrl).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return DownloadResult.NetworkError
                val body = response.body
                val total = body.contentLength()
                if (total > maxApkBytes) return DownloadResult.TooLarge
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > maxApkBytes) {
                                output.close()
                                target.delete()
                                return DownloadResult.TooLarge
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            onProgress(if (total > 0) written.toFloat() / total else null)
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(manifest.apkSha256, ignoreCase = true)) {
                    target.delete()
                    return DownloadResult.ChecksumMismatch
                }
                return DownloadResult.Success(target)
            }
        } catch (_: IOException) {
            target.delete()
            return DownloadResult.NetworkError
        }
    }

    companion object {
        const val APK_FILE_NAME = "service-app-update.apk"
        const val MAX_APK_BYTES = 50L * 1024 * 1024
        private const val BUFFER_SIZE = 64 * 1024
    }
}
