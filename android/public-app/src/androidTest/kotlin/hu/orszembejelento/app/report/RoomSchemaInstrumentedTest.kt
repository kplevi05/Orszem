package hu.orszembejelento.app.report

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import hu.orszembejelento.app.report.data.local.ReportHistoryDatabase
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Establishes real Room migration-test infrastructure now (§59), even though version 1 has
 * no prior version to migrate from - so the first genuine schema change only has to add a
 * migration and a test that runs it through [helper], never invent this harness from
 * scratch under time pressure later. `fallbackToDestructiveMigration` is never used in
 * production (see `ReportHistoryDatabase`'s own KDoc): this is the mechanism that makes
 * that promise checkable.
 */
class RoomSchemaInstrumentedTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ReportHistoryDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun theExportedVersion1SchemaCreatesSuccessfully() {
        // createDatabase applies the exported schema JSON for this version directly - if
        // the entity definitions and the committed schemas/…/1.json ever drift apart, this
        // fails immediately, not silently.
        val db = helper.createDatabase("schema-check.db", 1)
        assertTrue(db.version == 1)
        db.close()
    }
}
