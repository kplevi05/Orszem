package hu.orszembejelento.app.report.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The Public app's local report-history database (Phase 5 brief §20-21).
 *
 * Version starts at 1 - this is the initial V2 local database, so there is no v0→v1
 * migration to write. **`fallbackToDestructiveMigration` is deliberately never used**:
 * history surviving an ordinary app update is a product requirement, not a nice-to-have,
 * so every future schema change must ship a real, tested Room migration. The exported
 * schema (`android/public-app/schemas/`, committed) is what makes testing those migrations
 * against this exact starting point possible later.
 *
 * Excluded from cloud backup and device transfer by `data_extraction_rules.xml` /
 * `backup_rules.xml` - the encrypted credentials this database stores are meaningless
 * without the device-local Keystore key that encrypted them, so restoring the database
 * without the key would only leave undecryptable rows (§24).
 */
@Database(entities = [ReportHistoryEntity::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class ReportHistoryDatabase : RoomDatabase() {

    abstract fun reportHistoryDao(): ReportHistoryDao

    companion object {
        const val FILE_NAME = "report-history.db"

        fun build(context: Context): ReportHistoryDatabase =
            Room.databaseBuilder(context.applicationContext, ReportHistoryDatabase::class.java, FILE_NAME).build()
    }
}
