package hu.orszembejelento.service.update

import hu.orszembejelento.service.update.data.DownloadResult
import hu.orszembejelento.service.update.data.HttpUpdateRepository
import hu.orszembejelento.service.update.data.ManifestResult
import hu.orszembejelento.service.update.domain.UpdateManifest
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HttpUpdateRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `fetchManifest returns Success for valid JSON`() = runTest {
        val root = server.url("/")
        val apkUrl = root.resolve("service-app.apk").toString()
        val manifestUrl = root.resolve("latest.json").toString()
        val jsonBody = """
            {
                "versionCode": 11,
                "versionName": "2.0.10",
                "minSupportedVersionCode": 10,
                "apkUrl": "$apkUrl",
                "apkSha256": "e62ade239e0265f5ddc15e3018135ad0a5930d950cbf7390b55d1315d7b303cf",
                "releaseNotes": "Frissítés"
            }
        """.trimIndent()

        server.enqueue(MockResponse.Builder().code(200).body(jsonBody).build())

        val repo = HttpUpdateRepository(client, manifestUrl, tempFolder.newFolder("downloads"))
        val result = repo.fetchManifest()

        assertTrue(result is ManifestResult.Success)
        val manifest = (result as ManifestResult.Success).manifest
        assertEquals(11L, manifest.versionCode)
        assertEquals("2.0.10", manifest.versionName)
    }

    @Test
    fun `fetchManifest returns Invalid for malformed JSON or invalid schema`() = runTest {
        val manifestUrl = server.url("/latest.json").toString()
        server.enqueue(MockResponse.Builder().code(200).body("{ invalid json").build())

        val repo = HttpUpdateRepository(client, manifestUrl, tempFolder.newFolder("downloads"))
        val result = repo.fetchManifest()

        assertTrue(result is ManifestResult.Invalid)
    }

    @Test
    fun `fetchManifest returns Unavailable on 404 or server error`() = runTest {
        val manifestUrl = server.url("/latest.json").toString()
        server.enqueue(MockResponse.Builder().code(404).build())

        val repo = HttpUpdateRepository(client, manifestUrl, tempFolder.newFolder("downloads"))
        val result = repo.fetchManifest()

        assertTrue(result is ManifestResult.Unavailable)
    }

    @Test
    fun `downloadApk computes SHA-256 and saves file on success`() = runTest {
        val bytes = "FAKE-APK-CONTENT".toByteArray(Charsets.UTF_8)
        val expectedSha = sha256(bytes)

        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(bytes)).build())

        val apkUrl = server.url("/app.apk").toString()
        val manifest = UpdateManifest(
            versionCode = 11,
            versionName = "2.0.10",
            apkUrl = apkUrl,
            apkSha256 = expectedSha,
        )

        val downloadDir = tempFolder.newFolder("downloads")
        val repo = HttpUpdateRepository(client, server.url("/latest.json").toString(), downloadDir)
        var lastProgress: Float? = null
        val result = repo.downloadApk(manifest) { lastProgress = it }

        assertTrue(result is DownloadResult.Success)
        val file = (result as DownloadResult.Success).file
        assertTrue(file.exists())
        assertEquals(bytes.size.toLong(), file.length())
        assertEquals(1.0f, lastProgress ?: 0f, 0.01f)
    }

    @Test
    fun `downloadApk deletes file and returns ChecksumMismatch on corrupted content`() = runTest {
        val bytes = "FAKE-APK-CONTENT".toByteArray(Charsets.UTF_8)

        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(bytes)).build())

        val apkUrl = server.url("/app.apk").toString()
        val manifest = UpdateManifest(
            versionCode = 11,
            versionName = "2.0.10",
            apkUrl = apkUrl,
            apkSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
        )

        val downloadDir = tempFolder.newFolder("downloads")
        val repo = HttpUpdateRepository(client, server.url("/latest.json").toString(), downloadDir)
        val result = repo.downloadApk(manifest) {}

        assertTrue(result is DownloadResult.ChecksumMismatch)
        val expectedTarget = File(downloadDir, HttpUpdateRepository.APK_FILE_NAME)
        assertFalse(expectedTarget.exists())
    }
}
