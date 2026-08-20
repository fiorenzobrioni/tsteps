package com.callbackdev.tsteps.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
 * An activity session — a diff hunk in the day, written once at ^C by manual
 * tracking (Fase 6) or inferred by the auto detector (Fase 11, `auto = true`).
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
    val auto: Boolean = false,
    /** Wall time minus pauses — what duration/speed/pace/cadence divide by (v2). */
    val activeMillis: Long = 0,
    /**
     * `[rm]` is a soft delete (v3): the row stays as a tombstone, invisible to
     * every screen but still an exclusion for the detector — a dismissed auto
     * session must never be re-detected from the same samples.
     */
    val dismissedMillis: Long? = null,
    /**
     * The detector's original window (v3, auto sessions only) — immutable even
     * after the user edits the boundaries, so dedup always checks against what
     * the machine actually claimed. Boundary still equal to it = still the
     * machine's guess: that is what renders the `~`.
     */
    val detectedStartMillis: Long? = null,
    val detectedEndMillis: Long? = null
)

/**
 * One recorded sample span (v3): the counter delta observed between two
 * consecutive readings — the auto detector's raw material. Recorded only while
 * `sessions.auto_detect` is on (off = zero rows, zero anything), coalesced to
 * ~1-min resolution while the live listener ticks, pruned after a few days.
 */
@Entity(tableName = "step_sample", indices = [Index("toMillis")])
data class StepSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromMillis: Long,
    val toMillis: Long,
    val steps: Long
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

    /**
     * Insert-only on purpose: a commit is written once (see [DaySummaryEntity]).
     * Returns -1 when the day was already committed — how callers tell a fresh
     * commit (worth a notification) from a no-op safety-net pass.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(day: DaySummaryEntity): Long
}

@Dao
interface SessionDao {

    // Every read the screens consume filters tombstones: a dismissed session is
    // gone from the UI, it only survives as the detector's exclusion evidence.

    @Query("SELECT * FROM session WHERE dismissedMillis IS NULL ORDER BY startMillis DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query(
        "SELECT * FROM session WHERE dismissedMillis IS NULL " +
            "AND startMillis >= :fromMillis AND startMillis < :toMillis " +
            "ORDER BY startMillis"
    )
    fun observeBetween(fromMillis: Long, toMillis: Long): Flow<List<SessionEntity>>

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun byId(id: Long): SessionEntity?

    /**
     * Every session the export writes (Fase 13), oldest first: tombstones out
     * (`[rm]` means gone), still-running out (no end, no record yet).
     */
    @Query(
        "SELECT * FROM session WHERE dismissedMillis IS NULL AND endMillis IS NOT NULL " +
            "ORDER BY startMillis"
    )
    suspend fun allCompleted(): List<SessionEntity>

    /**
     * Everything overlapping the range, tombstones included — the detector's
     * dedup view. A running manual session (endMillis null) counts from its
     * start onward.
     */
    @Query(
        "SELECT * FROM session WHERE startMillis < :toMillis " +
            "AND (endMillis IS NULL OR endMillis > :fromMillis " +
            "OR detectedEndMillis > :fromMillis)"
    )
    suspend fun overlappingIncludingDismissed(
        fromMillis: Long,
        toMillis: Long
    ): List<SessionEntity>

    /** `[rm]` — soft delete: the row becomes a tombstone. */
    @Query("UPDATE session SET dismissedMillis = :nowMillis WHERE id = :id")
    suspend fun dismiss(id: Long, nowMillis: Long)

    /** Boundary edit (auto sessions): times move, metrics follow the recompute. */
    @Query(
        "UPDATE session SET startMillis = :startMillis, endMillis = :endMillis, " +
            "steps = :steps, distanceMeters = :distanceMeters, " +
            "activeMillis = :activeMillis, avgCadenceSpm = :avgCadenceSpm WHERE id = :id"
    )
    suspend fun updateBounds(
        id: Long,
        startMillis: Long,
        endMillis: Long,
        steps: Long,
        distanceMeters: Double?,
        activeMillis: Long,
        avgCadenceSpm: Int?
    )

    /**
     * Tombstones whose whole window (detected or actual) ended before the
     * cutoff can't exclude anything anymore: garbage. End-based on purpose — a
     * dismissed session that crossed midnight keeps excluding its morning tail.
     */
    @Query(
        "DELETE FROM session WHERE dismissedMillis IS NOT NULL " +
            "AND COALESCE(MAX(detectedEndMillis, endMillis), endMillis, " +
            "detectedEndMillis, startMillis) < :beforeMillis"
    )
    suspend fun pruneDismissedBefore(beforeMillis: Long)

    @Insert
    suspend fun insert(session: SessionEntity): Long
}

@Dao
interface StepSampleDao {

    @Query("SELECT * FROM step_sample ORDER BY toMillis DESC LIMIT 1")
    suspend fun latest(): StepSampleEntity?

    @Query("SELECT * FROM step_sample WHERE toMillis > :afterMillis ORDER BY fromMillis")
    suspend fun since(afterMillis: Long): List<StepSampleEntity>

    @Query("SELECT COUNT(*) FROM step_sample")
    suspend fun count(): Long

    @Insert
    suspend fun insert(sample: StepSampleEntity): Long

    @Upsert
    suspend fun upsert(sample: StepSampleEntity)

    @Query("DELETE FROM step_sample WHERE toMillis < :beforeMillis")
    suspend fun pruneBefore(beforeMillis: Long)

    /** Toggle off = the feature never existed: no rows, no residue. */
    @Query("DELETE FROM step_sample")
    suspend fun clear()
}

@Database(
    entities = [
        HourlyStepsEntity::class,
        DaySummaryEntity::class,
        SessionEntity::class,
        StepSampleEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class TstepsDatabase : RoomDatabase() {
    abstract fun hourlyStepsDao(): HourlyStepsDao
    abstract fun daySummaryDao(): DaySummaryDao
    abstract fun sessionDao(): SessionDao
    abstract fun stepSampleDao(): StepSampleDao

    companion object {
        /** v2 (Fase 6): sessions learn their active (pause-free) duration. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE session ADD COLUMN activeMillis INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v3 (Fase 11): sessions learn tombstones and their detected window;
         * the recorded sample spans get their own table.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE session ADD COLUMN dismissedMillis INTEGER")
                db.execSQL("ALTER TABLE session ADD COLUMN detectedStartMillis INTEGER")
                db.execSQL("ALTER TABLE session ADD COLUMN detectedEndMillis INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `step_sample` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`fromMillis` INTEGER NOT NULL, `toMillis` INTEGER NOT NULL, " +
                        "`steps` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_step_sample_toMillis` " +
                        "ON `step_sample` (`toMillis`)"
                )
            }
        }
    }
}
