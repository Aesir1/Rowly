package dev.aesir1.rowly.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-created training.
 *
 * [phases] is the delimited string from `encodePhases` (`SPEED:T:60000|RECOVERY:D:500.0`) rather
 * than a child table: the list is only ever read and written whole, and the project carries no
 * serialization library to justify anything richer.
 */
@Entity(tableName = "custom_trainings")
data class CustomTrainingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val phases: String,
    val createdAt: Long,
)
