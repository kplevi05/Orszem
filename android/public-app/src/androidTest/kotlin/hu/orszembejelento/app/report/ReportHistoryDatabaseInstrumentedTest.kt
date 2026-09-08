package hu.orszembejelento.app.report

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.orszembejelento.app.report.data.ReportDisplaySnapshot
import hu.orszembejelento.app.report.data.ReportRepository
import hu.orszembejelento.app.report.data.SubmitOutcome
import hu.orszembejelento.app.report.data.crypto.KeystoreCryptoBox
import hu.orszembejelento.app.report.data.local.ReportHistoryDatabase
import hu.orszembejelento.app.report.domain.ReportDraft
import hu.orszembejelento.app.report.domain.SubmissionState
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the real Room database (§58): a plaintext credential never touches disk, and a
 * PENDING record survives a simulated "app relaunch" - a fresh `ReportHistoryDatabase` and
 * `KeystoreCryptoBox` instance, pointed at the same on-disk database file/Keystore alias,
 * exactly as a freshly launched process would construct them (see `AppContainer`). A literal
 * process kill is not reachable from within one instrumented test process; opening a brand
 * new set of these objects against the same persistent state is the accepted equivalent,
 * and is the same guarantee the app actually depends on - nothing in `ReportRepository` or
 * `ReportHistoryDatabase` holds any in-memory state that a real relaunch would have anyway.
 */
@RunWith(AndroidJUnit4::class)
class ReportHistoryDatabaseInstrumentedTest {

    private val dbFileName = "report-history-instrumented-test.db"
    private val keystoreAlias = "orszem.public.report.instrumented-test.v1"
    private lateinit var dbFile: File

    private fun freshDatabase(): ReportHistoryDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return Room.databaseBuilder(context, ReportHistoryDatabase::class.java, dbFileName).build()
    }

    private fun freshRepository(db: ReportHistoryDatabase) =
        ReportRepository(db.reportHistoryDao(), KeystoreCryptoBox(keystoreAlias), FailingApi())

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dbFile = context.getDatabasePath(dbFileName)
        context.deleteDatabase(dbFileName)
        deleteKey()
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(dbFileName)
        deleteKey()
    }

    private fun deleteKey() {
        runCatching {
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(keystoreAlias)) keyStore.deleteEntry(keystoreAlias)
        }
    }

    @Test
    fun aPlaintextCredentialNeverAppearsInTheDatabaseFileOnDisk() = runBlocking {
        val db = freshDatabase()
        val repository = freshRepository(db)
        val draft = ReportDraft(Instant.now(), null, UUID.randomUUID(), null, "FIGHT")
        val display = ReportDisplaySnapshot("Alfaváros", null, "Kategória", "Verekedés")

        val outcome = repository.submit(draft, display) // FailingApi -> stays PENDING, row is committed
        assertTrue(outcome is SubmitOutcome.AmbiguousFailure)
        db.close()

        assertTrue("expected the db file to exist at ${dbFile.absolutePath}", dbFile.exists())
        val raw = dbFile.readBytes()
        val rawAsLatin1 = String(raw, Charsets.ISO_8859_1)
        // Every credential this test generates starts with this literal prefix - if any
        // plaintext credential landed on disk it would appear as this substring.
        assertFalse("a plaintext report-access credential must never appear in the raw database file", rawAsLatin1.contains("pr_"))
    }

    @Test
    fun aPendingRecordSurvivesAFreshDatabaseAndCryptoBoxInstance_simulatingAnAppRelaunch() = runBlocking {
        val firstDb = freshDatabase()
        val firstRepository = freshRepository(firstDb)
        val settlementId = UUID.randomUUID()
        val draft = ReportDraft(Instant.now(), "G123", settlementId, null, "FIGHT")
        val display = ReportDisplaySnapshot("Alfaváros", null, "Kategória", "Verekedés")

        val outcome = firstRepository.submit(draft, display) as SubmitOutcome.AmbiguousFailure
        val entityId = firstDb.reportHistoryDao().observeAll().first().single().clientSubmissionId
        firstDb.close()

        // A brand-new process would construct all of this fresh, from the same on-disk
        // database file and the same (device-persistent) Keystore alias.
        val secondDb = freshDatabase()
        val secondRepository = freshRepository(secondDb)

        val reloaded = secondDb.reportHistoryDao().findByClientSubmissionId(entityId)
        assertTrue(reloaded != null)
        assertEquals(SubmissionState.PENDING, reloaded!!.submissionState)
        assertEquals(settlementId, reloaded.settlementId)
        assertEquals("G123", reloaded.trainIdentifier)

        // And the retry must still work - the decrypted credential from the FIRST
        // instance's encryption must be usable by the SECOND instance's crypto box, which
        // is only possible because the Keystore key itself is device-persistent, not
        // process-local.
        val retryOutcome = secondRepository.retry(entityId)
        assertTrue(retryOutcome is SubmitOutcome.AmbiguousFailure) // FailingApi again, but no ACCESS_LOST
        secondDb.close()
    }

    /** Always fails the network call - these tests only care about local persistence. */
    private class FailingApi : hu.orszembejelento.app.report.data.network.PublicApi {
        override suspend fun reportCatalog() = throw java.io.IOException("not used in this test")
        override suspend fun searchSettlements(query: String) = throw java.io.IOException("not used in this test")
        override suspend fun railwayLinesOfSettlement(settlementId: String) = throw java.io.IOException("not used in this test")
        override suspend fun submitReport(
            body: hu.orszembejelento.app.report.data.network.SubmitReportRequestBody,
            accessCredential: String,
        ): retrofit2.Response<hu.orszembejelento.app.report.data.network.SubmitReportResponseBody> =
            throw java.io.IOException("simulated network failure")
        override suspend fun getReport(publicReportId: String, accessCredential: String) =
            throw java.io.IOException("not used in this test")
    }
}
