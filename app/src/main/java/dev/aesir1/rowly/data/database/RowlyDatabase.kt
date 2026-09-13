package dev.aesir1.rowly.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.aesir1.rowly.data.entity.ActivityEntity
import dev.aesir1.rowly.data.entity.CalibrationSampleEntity
import dev.aesir1.rowly.data.entity.LocationPointEntity
import dev.aesir1.rowly.data.entity.StrokeRateSampleEntity
import dev.aesir1.rowly.data.entity.UserSettingsEntity

@Database(
    entities = [
        ActivityEntity::class,
        LocationPointEntity::class,
        StrokeRateSampleEntity::class,
        UserSettingsEntity::class,
        CalibrationSampleEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class RowlyDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
    abstract fun locationPointDao(): LocationPointDao
    abstract fun strokeRateDao(): StrokeRateDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        /**
         * Two new tables, nothing touched. Written out rather than destroyed-and-recreated
         * because v1 holds the user's recorded sessions and those are not reproducible.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `user_settings` (" +
                        "`id` INTEGER NOT NULL, `strokeSensitivity` REAL NOT NULL, " +
                        "`deviceModel` TEXT NOT NULL, `sensorName` TEXT NOT NULL, " +
                        "`sensorResolution` REAL NOT NULL, `sensorMaxRange` REAL NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `calibration_samples` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                        "`sensitivity` REAL NOT NULL, `measuredAvgSpm` REAL, " +
                        "`readingCount` INTEGER NOT NULL, `ergometerSpm` REAL NOT NULL, " +
                        "`deviceModel` TEXT NOT NULL)",
                )
            }
        }

        fun create(context: Context): RowlyDatabase =
            Room.databaseBuilder(context, RowlyDatabase::class.java, "rowly.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
