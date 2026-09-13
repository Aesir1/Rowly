package dev.aesir1.rowly.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.aesir1.rowly.data.entity.ActivityEntity
import dev.aesir1.rowly.data.entity.LocationPointEntity
import dev.aesir1.rowly.data.entity.StrokeRateSampleEntity

@Database(
    entities = [ActivityEntity::class, LocationPointEntity::class, StrokeRateSampleEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class RowlyDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
    abstract fun locationPointDao(): LocationPointDao
    abstract fun strokeRateDao(): StrokeRateDao

    companion object {
        fun create(context: Context): RowlyDatabase =
            Room.databaseBuilder(context, RowlyDatabase::class.java, "rowly.db").build()
    }
}
