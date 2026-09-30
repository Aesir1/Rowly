package dev.aesir1.rowly.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.aesir1.rowly.data.entity.ActivityEntity
import dev.aesir1.rowly.data.entity.CalibrationSampleEntity
import dev.aesir1.rowly.data.entity.CustomTrainingEntity
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
        CustomTrainingEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class RowlyDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
    abstract fun locationPointDao(): LocationPointDao
    abstract fun strokeRateDao(): StrokeRateDao
    abstract fun settingsDao(): SettingsDao
    abstract fun trainingDao(): TrainingDao

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

        /**
         * Activity ranks and the user profile. All additive, so plain ADD COLUMNs - the
         * recorded sessions are untouched and must stay that way.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `activities` ADD COLUMN `rank` TEXT")
                db.execSQL("ALTER TABLE `user_settings` ADD COLUMN `firstName` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `user_settings` ADD COLUMN `lastName` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `user_settings` ADD COLUMN `nickname` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `user_settings` ADD COLUMN `languageTag` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `user_settings` ADD COLUMN `units` TEXT NOT NULL DEFAULT 'METRIC'")
            }
        }

        /** Training support: the custom trainings table plus two activity columns. Additive. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `activities` ADD COLUMN `ergometer` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `activities` ADD COLUMN `trainingName` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `custom_trainings` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                        "`phases` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
            }
        }

        fun create(context: Context): RowlyDatabase =
            Room.databaseBuilder(context, RowlyDatabase::class.java, "rowly.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
