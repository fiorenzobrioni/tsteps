package com.callbackdev.tsteps.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Steps landed in one local (date, hour) bucket. This is the raw material of the
 * live day: today's total, the hourly sparkline and the derived active minutes
 * are all reads over these rows. Rows are kept after the day commits (they cost
 * nothing and let history screens show a day's shape).
 */
@Entity(tableName = "hourly_steps", primaryKeys = ["date", "hour"])
data class HourlyStepsEntity(
    /** ISO-8601 local date, `yyyy-MM-dd`. */
    val date: String,
    /** Local hour 0–23. */
    val hour: Int,
    val steps: Long
)

/**
 * A committed day — one git commit in the metaphor, written once by the rollover
 * and never updated. Distance and kcal are **frozen at commit time** with the
 * profile of that day, like any honest commit: editing your weight later must
 * not rewrite history. `goalSteps`/`goalMet` snapshot the CI check the same way
 * (goalMet is null when no goal was set — the check was skipped, not failed).
 */
@Entity(tableName = "day_summary")
data class DaySummaryEntity(
    @PrimaryKey val date: String,
    val steps: Long,
    val activeMinutes: Int,
    val distanceMeters: Double,
    val activeKcal: Double?,
    val goalSteps: Int,
    val goalMet: Boolean?
)

/**
 * An activity session — a diff hunk in the day. Schema lands in Fase 2 with the
 * rest of the database; the logic that writes it is Fase 6 (manual tracking) and
 * Fase 11 (auto detection).
 */
@Entity(tableName = "session")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMillis: Long,
    /** Null while the session is running. */
    val endMillis: Long?,
    /** `walk` or `other` — session types are deliberately few (VISION §6.7). */
    val type: String,
    val steps: Long,
    val distanceMeters: Double?,
    val avgCadenceSpm: Int?,
    val auto: Boolean = false
)

@Dao
interface HourlyStepsDao {

    @Query("SELECT * FROM hourly_steps WHERE date = :date ORDER BY hour")
    fun observeDay(date: String): Flow<List<HourlyStepsEntity>>

    @Query("SELECT * FROM hourly_steps WHERE date = :date ORDER BY hour")
    suspend fun day(date: String): List<HourlyStepsEntity>

    @Query("SELECT DISTINCT date FROM hourly_steps WHERE date < :beforeDate")
    suspend fun datesBefore(beforeDate: String): List<String>

    @Query("SELECT steps FROM hourly_steps WHERE date = :date AND hour = :hour")
    suspend fun steps(date: String, hour: Int): Long?

    @Upsert
    suspend fun upsert(row: HourlyStepsEntity)

    /** Adds a delta to one bucket, creating it on first touch. */
    @Transaction
    suspend fun increment(date: String, hour: Int, delta: Long) {
        val current = steps(date, hour) ?: 0L
        upsert(HourlyStepsEntity(date, hour, current + delta))
    }
}

@Dao
interface DaySummaryDao {

    @Query("SELECT * FROM day_summary WHERE date = :date")
    suspend fun byDate(date: String): DaySummaryEntity?

    @Query("SELECT * FROM day_summary ORDER BY date DESC")
    fun observeAll(): Flow<List<DaySummaryEntity>>

    @Query("SELECT * FROM day_summary ORDER BY date DESC")
    suspend fun all(): List<DaySummaryEntity>

    /** Insert-only on purpose: a commit is written once (see [DaySummaryEntity]). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(day: DaySummaryEntity)
}

@Dao
interface SessionDao {

    @Query("SELECT * FROM session ORDER BY startMillis DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Insert
    suspend fun insert(session: SessionEntity): Long
}

@Database(
    entities = [HourlyStepsEntity::class, DaySummaryEntity::class, SessionEntity::class],
    version = 1,
    exportSchema = false
)
abstract class TstepsDatabase : RoomDatabase() {
    abstract fun hourlyStepsDao(): HourlyStepsDao
    abstract fun daySummaryDao(): DaySummaryDao
    abstract fun sessionDao(): SessionDao
}
