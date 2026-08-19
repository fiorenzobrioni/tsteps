package com.callbackdev.tsteps.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Migrations must carry the user's real history across updates (v1 shipped with
 * Fase 2, v2 with Fase 6): each start schema is hand-built with the exact DDL —
 * if it drifts from what Room generated, opening fails validation — then opened
 * with the current version + the migration chain. tweather's pattern.
 */
@RunWith(RobolectricTestRunner::class)
class TstepsDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"
    private var database: TstepsDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `v1 rows survive the migration, sessions gain a zero active duration`() {
        val file = context.getDatabasePath(dbName).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { v1 ->
            v1.execSQL(
                "CREATE TABLE IF NOT EXISTS `hourly_steps` (" +
                    "`date` TEXT NOT NULL, `hour` INTEGER NOT NULL, " +
                    "`steps` INTEGER NOT NULL, PRIMARY KEY(`date`, `hour`))"
            )
            v1.execSQL(
                "CREATE TABLE IF NOT EXISTS `day_summary` (" +
                    "`date` TEXT NOT NULL, `steps` INTEGER NOT NULL, " +
                    "`activeMinutes` INTEGER NOT NULL, `distanceMeters` REAL NOT NULL, " +
                    "`activeKcal` REAL, `goalSteps` INTEGER NOT NULL, `goalMet` INTEGER, " +
                    "PRIMARY KEY(`date`))"
            )
            v1.execSQL(
                "CREATE TABLE IF NOT EXISTS `session` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`startMillis` INTEGER NOT NULL, `endMillis` INTEGER, " +
                    "`type` TEXT NOT NULL, `steps` INTEGER NOT NULL, " +
                    "`distanceMeters` REAL, `avgCadenceSpm` INTEGER, `auto` INTEGER NOT NULL)"
            )
            v1.execSQL("INSERT INTO hourly_steps (date, hour, steps) VALUES ('2026-08-18', 9, 600)")
            v1.execSQL(
                "INSERT INTO day_summary " +
                    "(date, steps, activeMinutes, distanceMeters, activeKcal, goalSteps, goalMet) " +
                    "VALUES ('2026-08-17', 11204, 96, 8300.0, NULL, 10000, 1)"
            )
            v1.execSQL(
                "INSERT INTO session (startMillis, endMillis, type, steps, distanceMeters, " +
                    "avgCadenceSpm, auto) VALUES (1000, 2000, 'walk', 500, 360.0, 100, 0)"
            )
            v1.version = 1
        }

        val db = openCurrent()

        runBlocking {
            assertEquals(600L, db.hourlyStepsDao().day("2026-08-18").single().steps)
            assertEquals(11_204L, db.daySummaryDao().byDate("2026-08-17")!!.steps)
            val session = db.sessionDao().observeAll().first().single()
            assertEquals(500L, session.steps)
            assertEquals(0L, session.activeMillis) // migrated rows default to 0
        }
    }

    @Test
    fun `v2 sessions survive to v3 as plain manual rows, samples start empty`() {
        val file = context.getDatabasePath(dbName).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { v2 ->
            v2.execSQL(
                "CREATE TABLE IF NOT EXISTS `hourly_steps` (" +
                    "`date` TEXT NOT NULL, `hour` INTEGER NOT NULL, " +
                    "`steps` INTEGER NOT NULL, PRIMARY KEY(`date`, `hour`))"
            )
            v2.execSQL(
                "CREATE TABLE IF NOT EXISTS `day_summary` (" +
                    "`date` TEXT NOT NULL, `steps` INTEGER NOT NULL, " +
                    "`activeMinutes` INTEGER NOT NULL, `distanceMeters` REAL NOT NULL, " +
                    "`activeKcal` REAL, `goalSteps` INTEGER NOT NULL, `goalMet` INTEGER, " +
                    "PRIMARY KEY(`date`))"
            )
            v2.execSQL(
                "CREATE TABLE IF NOT EXISTS `session` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`startMillis` INTEGER NOT NULL, `endMillis` INTEGER, " +
                    "`type` TEXT NOT NULL, `steps` INTEGER NOT NULL, " +
                    "`distanceMeters` REAL, `avgCadenceSpm` INTEGER, " +
                    "`auto` INTEGER NOT NULL, `activeMillis` INTEGER NOT NULL DEFAULT 0)"
            )
            v2.execSQL(
                "INSERT INTO session (startMillis, endMillis, type, steps, distanceMeters, " +
                    "avgCadenceSpm, auto, activeMillis) " +
                    "VALUES (1000, 2000, 'walk', 500, 360.0, 100, 0, 900)"
            )
            v2.version = 2
        }

        val db = openCurrent()

        runBlocking {
            val session = db.sessionDao().observeAll().first().single()
            assertEquals(500L, session.steps)
            assertEquals(900L, session.activeMillis)
            assertNull(session.dismissedMillis)   // no tombstone
            assertNull(session.detectedStartMillis) // a manual row, not a detection
            assertNull(session.detectedEndMillis)
            assertEquals(0L, db.stepSampleDao().count())
            db.stepSampleDao().insert(StepSampleEntity(fromMillis = 0, toMillis = 1, steps = 5))
            assertEquals(1L, db.stepSampleDao().count())
        }
    }

    private fun openCurrent(): TstepsDatabase =
        Room.databaseBuilder(context, TstepsDatabase::class.java, dbName)
            .addMigrations(TstepsDatabase.MIGRATION_1_2, TstepsDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
            .also { database = it }
}
