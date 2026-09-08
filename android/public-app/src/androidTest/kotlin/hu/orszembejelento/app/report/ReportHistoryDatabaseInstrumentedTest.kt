package hu.orszembejelento.app.report

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.orszembejelento.app.report.data.ReportDisplaySnapshot
import hu.orszembejelento.app.report.data.ReportRepository
import hu.orszembejelento.app.report.data.SubmitOutcome
import hu.orszembejelento.app.report.data.crypto.KeystoreCryptoBox
import hu.orszembejelento.app.report.data.local.ReportHistoryDatabase
import hu.orszembejelento.app.report.data.network.SubmitReportRequestBody
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the real Room database and the full [ReportRepository] lifecycle across a
 * simulated "app relaunch" - a fresh `ReportHistoryDatabase` and `KeystoreCryptoBox`
 * instance, pointed at the same on-disk database file/Keystore alias, exactly as a freshly
 * launched process would construct them (see `AppContainer`). A literal process kill is not
 * reachable from within one instrumented test process; opening a brand new set of these
 * objects against the same persistent state is the accepted equivalent, and is the same
 * guarantee the app actually depends on - nothing in `ReportRepository` or
 * `ReportHistoryDatabase` holds any in-memory state that a real relaunch would have anyway.
 *
 * This is the owner-required Android technical verification gate (Phase 5 visual-approval
 * round): every numbered item below maps directly to one requirement from that gate.
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

    private fun freshRepository(db: ReportHistoryDatabase, api: CapturingFailingApi = CapturingFailingApi()) =
        ReportRepository(db.reportHistoryDao(), KeystoreCryptoBox(keystoreAlias), api)

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

    /** Item 7 of the gate: a plaintext credential never appears anywhere in the on-disk database file. */
    @Test
    fun aPlaintextCredentialNeverAppearsInTheDatabaseFileOnDisk() = runBlocking {
        val db = freshDatabase()
        val repository = freshRepository(db)
        val draft = ReportDraft(Instant.now(), null, UUID.randomUUID(), null, "FIGHT")
        val display = ReportDisplaySnapshot("Alfaváros", null, "Kategória", "Verekedés")

        val outcome = repository.submit(draft, display) // network fails -> stays PENDING, row is committed
        assertTrue(outcome is SubmitOutcome.AmbiguousFailure)
        db.close()

        assertTrue("expected the db file to exist at ${dbFile.absolutePath}", dbFile.exists())
        val raw = dbFile.readBytes()
        val rawAsLatin1 = String(raw, Charsets.ISO_8859_1)
        // Every credential this test generates starts with this literal prefix - if any
        // plaintext credential landed on disk it would appear as this substring.
        assertFalse("a plaintext report-access credential must never appear in the raw database file", rawAsLatin1.contains("pr_"))
    }

    /** Items 2 and 4 of the gate: a committed PENDING record survives a simulated relaunch. */
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
        assertTrue(retryOutcome is SubmitOutcome.AmbiguousFailure) // network fails again, but no ACCESS_LOST
        secondDb.close()
    }

    /**
     * Item 3 of the gate: the stored, Keystore-encrypted access credential decrypts
     * successfully after a simulated relaunch, and the decrypted plaintext is byte-for-byte
     * the exact credential this app generated at submission time - not merely "some"
     * successful decryption.
     */
    @Test
    fun theStoredCredentialDecryptsSuccessfullyAfterASimulatedRelaunchAndMatchesTheOriginal() = runBlocking {
        val firstApi = CapturingFailingApi()
        val firstDb = freshDatabase()
        val firstRepository = freshRepository(firstDb, firstApi)
        val draft = ReportDraft(Instant.now(), null, UUID.randomUUID(), null, "FIGHT")
        val display = ReportDisplaySnapshot("Alfaváros", null, "Kategória", "Verekedés")

        firstRepository.submit(draft, display)
        val originalCredential = firstApi.lastCredential
        assertNotNull("submit() must have reached the network layer with a credential", originalCredential)
        val entityId = firstDb.reportHistoryDao().observeAll().first().single().clientSubmissionId
        firstDb.close()

        val secondDb = freshDatabase()
        val secondCryptoBox = KeystoreCryptoBox(keystoreAlias)
        val reloaded = secondDb.reportHistoryDao().findByClientSubmissionId(entityId)!!
        val decrypted = secondCryptoBox.decrypt(reloaded.encryptedCredentialBlob)
        assertNotNull("the credential must decrypt successfully after a relaunch", decrypted)
        assertEquals(originalCredential, String(decrypted!!, Charsets.UTF_8))
        secondDb.close()
    }

    /**
     * Item 5 of the gate: a retry issued after a simulated relaunch resends the exact same
     * `clientSubmissionId`, the exact same access credential, and the exact same frozen
     * normalized payload the original submission recorded - never anything reconstructed
     * from fresh UI state.
     */
    @Test
    fun retryAfterARelaunchUsesTheExactSameIdentityCredentialAndFrozenPayload() = runBlocking {
        val firstApi = CapturingFailingApi()
        val firstDb = freshDatabase()
        val firstRepository = freshRepository(firstDb, firstApi)
        val settlementId = UUID.randomUUID()
        val railwayLineId = UUID.randomUUID()
        val draft = ReportDraft(Instant.parse("2026-05-01T10:15:30Z"), "IC 123", settlementId, railwayLineId, "THEFT")
        val display = ReportDisplaySnapshot("Alfaváros", "Alfa - Beta", "Kategória", "Lopás")

        firstRepository.submit(draft, display)
        val originalBody = firstApi.lastBody
        val originalCredential = firstApi.lastCredential
        assertNotNull(originalBody)
        assertNotNull(originalCredential)
        val entityId = firstDb.reportHistoryDao().observeAll().first().single().clientSubmissionId
        assertEquals(entityId.toString(), originalBody!!.clientSubmissionId)
        firstDb.close()

        val secondApi = CapturingFailingApi()
        val secondDb = freshDatabase()
        val secondRepository = freshRepository(secondDb, secondApi)

        val retryOutcome = secondRepository.retry(entityId)
        assertTrue(retryOutcome is SubmitOutcome.AmbiguousFailure)

        assertEquals(
            "retry must resend the exact same frozen payload, not one rebuilt from fresh state",
            originalBody,
            secondApi.lastBody,
        )
        assertEquals(
            "retry must reuse the exact same access credential decrypted from storage",
            originalCredential,
            secondApi.lastCredential,
        )
        secondDb.close()
    }

    /**
     * Item 6 of the gate: once the stored credential blob is corrupted/undecryptable, a
     * retry transitions the record to ACCESS_LOST without throwing, without ever calling
     * the network, and without generating a replacement credential - the corrupted blob on
     * disk is left exactly as-is, matching [ReportRepository]'s documented "no
     * credential-recovery path" design.
     */
    @Test
    fun aCorruptedCredentialBlobTransitionsToAccessLostSafelyWithoutRegeneratingIt() = runBlocking {
        val firstApi = CapturingFailingApi()
        val firstDb = freshDatabase()
        val firstRepository = freshRepository(firstDb, firstApi)
        val draft = ReportDraft(Instant.now(), null, UUID.randomUUID(), null, "FIGHT")
        val display = ReportDisplaySnapshot("Alfaváros", null, "Kategória", "Verekedés")

        firstRepository.submit(draft, display)
        val entityId = firstDb.reportHistoryDao().observeAll().first().single().clientSubmissionId
        val original = firstDb.reportHistoryDao().findByClientSubmissionId(entityId)!!

        // Flip the last byte of the real GCM ciphertext/tag - authenticated encryption
        // fails closed on any tampering, exactly as KeystoreCryptoBoxInstrumentedTest
        // proves for the primitive in isolation. Here the corruption is written directly
        // into Room, the same place a real on-disk bit-flip or partial write would land.
        val corrupted = original.encryptedCredentialBlob.copyOf()
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1] + 1).toByte()
        firstDb.reportHistoryDao().update(original.copy(encryptedCredentialBlob = corrupted))
        firstDb.close()

        val secondApi = CapturingFailingApi()
        val secondDb = freshDatabase()
        val secondRepository = freshRepository(secondDb, secondApi)

        // Must not throw - a crash here would be the failure this test exists to catch.
        val retryOutcome = secondRepository.retry(entityId)
        assertTrue(retryOutcome is SubmitOutcome.AccessLost)
        assertEquals("ACCESS_LOST must never reach the network", 0, secondApi.submitCallCount)

        val reloaded = secondDb.reportHistoryDao().findByClientSubmissionId(entityId)!!
        assertEquals(SubmissionState.ACCESS_LOST, reloaded.submissionState)
        assertTrue(
            "the corrupted blob must be left exactly as-is, never replaced with a freshly generated credential",
            corrupted.contentEquals(reloaded.encryptedCredentialBlob),
        )

        // And the corrupted blob still genuinely does not decrypt - this is really
        // ACCESS_LOST, not a false positive from some other code path.
        assertNull(KeystoreCryptoBox(keystoreAlias).decrypt(reloaded.encryptedCredentialBlob))
        secondDb.close()
    }

    /**
     * Fails every submit like a real network outage would, but - unlike a bare throw -
     * records exactly what was sent, so a retry's payload/credential can be compared
     * against the original submission's.
     */
    private class CapturingFailingApi : hu.orszembejelento.app.report.data.network.PublicApi {
        var lastBody: SubmitReportRequestBody? = null
        var lastCredential: String? = null
        var submitCallCount = 0

        override suspend fun reportCatalog() = throw java.io.IOException("not used in this test")
        override suspend fun searchSettlements(query: String) = throw java.io.IOException("not used in this test")
        override suspend fun railwayLinesOfSettlement(settlementId: String) = throw java.io.IOException("not used in this test")
        override suspend fun submitReport(
            body: SubmitReportRequestBody,
            accessCredential: String,
        ): retrofit2.Response<hu.orszembejelento.app.report.data.network.SubmitReportResponseBody> {
            submitCallCount++
            lastBody = body
            lastCredential = accessCredential
            throw java.io.IOException("simulated network failure")
        }
        override suspend fun getReport(publicReportId: String, accessCredential: String) =
            throw java.io.IOException("not used in this test")
    }
}
