package com.callbackdev.tsteps.export

import com.callbackdev.tsteps.domain.SessionItem
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The archive's format, line by line: what it says, and what it refuses to say. */
class ExportDocumentsTest {

    private val rome = ZoneId.of("Europe/Rome")
    private val defaultLocale = Locale.getDefault()

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
    }

    private fun millis(dateTime: String): Long =
        LocalDateTime.parse(dateTime).atZone(rome).toInstant().toEpochMilli()

    private val committed = ExportDay(
        date = LocalDate.of(2026, 8, 19),
        commit = "3f2c1a9",
        steps = 11_204,
        activeMinutes = 96,
        distanceMeters = 8310.24,
        activeKcal = 312.4,
        goalSteps = 10_000,
        goalMet = true,
        committed = true
    )

    /** Today: live numbers, no goal set, no weight — two honest nulls. */
    private val open = ExportDay(
        date = LocalDate.of(2026, 8, 20),
        commit = "a10c4e5",
        steps = 4_120,
        activeMinutes = 41,
        distanceMeters = 3_058.0,
        activeKcal = null,
        goalSteps = 0,
        goalMet = null,
        committed = false
    )

    private val manualWalk = SessionItem(
        id = 1,
        startMillis = millis("2026-08-19T09:32:00"),
        endMillis = millis("2026-08-19T10:18:00"),
        type = "walk",
        steps = 4_210,
        distanceMeters = 3_120.5,
        activeMillis = 46 * 60_000L,
        avgCadenceSpm = 92
    )

    private val autoWalk = SessionItem(
        id = 2,
        startMillis = millis("2026-08-20T07:05:00"),
        endMillis = millis("2026-08-20T07:40:00"),
        type = "walk",
        steps = 3_010,
        distanceMeters = 2_200.0,
        activeMillis = 35 * 60_000L,
        avgCadenceSpm = null,
        auto = true,
        startApprox = true,
        endApprox = false
    )

    private fun bundle(
        days: List<ExportDay> = listOf(committed, open),
        sessions: List<SessionItem> = listOf(manualWalk, autoWalk)
    ) = ExportBundle(
        exportedAtMillis = millis("2026-08-20T18:32:05"),
        zone = rome,
        days = days,
        sessions = sessions
    )

    @Test
    fun `the json header states schema, zone and that the metrics are estimates`() {
        val json = ExportDocuments.json(bundle())
        assertTrue(json.startsWith("{\n"))
        assertTrue(json.contains(""""app": "tsteps","""))
        assertTrue(json.contains(""""schema": 2,"""))
        assertTrue(json.contains(""""exported_at": "2026-08-20T16:32:05Z","""))
        assertTrue(json.contains(""""timezone": "Europe/Rome","""))
        assertTrue(json.contains(""""units": "steps, meters, minutes, kcal","""))
        assertTrue(
            json.contains(
                """"estimates": "distance_m and active_kcal are estimated from your profile, not measured","""
            )
        )
        assertTrue(json.endsWith("}\n"))
    }

    @Test
    fun `a committed day carries its frozen numbers and says it is committed`() {
        val line = ExportDocuments.json(bundle()).lines().first { it.contains("2026-08-19\"") }
        assertEquals(
            """    { "date": "2026-08-19", "commit": "3f2c1a9", "steps": 11204, """ +
                """"active_min": 96, "distance_m": 8310.2, "stride_m": 0.742, """ +
                """"active_kcal": 312, "goal_steps": 10000, "goal_met": true, """ +
                """"committed": true },""",
            line
        )
    }

    @Test
    fun `stride_m is the factor that produced the distance, recoverable exactly`() {
        val bundle = bundle()
        val day = ExportDocuments.json(bundle).lines().first { it.contains("2026-08-19\"") }
        // 8310.24 / 11204 = 0.7417... — the stride the day was committed with,
        // not whatever the profile happens to say when the export runs.
        assertTrue(day.contains(""""stride_m": 0.742"""))
        val session = ExportDocuments.json(bundle).lines().first { it.contains("09:32:00") }
        assertTrue(session.contains(""""stride_m": 0.741"""))
    }

    @Test
    fun `a day that took no step reports no stride - zero over zero is not zero`() {
        val still = committed.copy(steps = 0, distanceMeters = 0.0)
        val line = ExportDocuments.json(bundle(days = listOf(still))).lines()
            .first { it.contains("2026-08-19\"") }
        assertTrue(line.contains(""""stride_m": null"""))
        val row = ExportDocuments.daysCsv(bundle(days = listOf(still))).trim().lines()[1]
        assertEquals("2026-08-19,3f2c1a9,0,96,0.0,,312,10000,true,true", row)
    }

    @Test
    fun `the working tree is exported as open, and missing metrics stay null`() {
        val line = ExportDocuments.json(bundle()).lines().first { it.contains("2026-08-20\",") }
        assertTrue(line.contains(""""committed": false"""))
        assertTrue(line.contains(""""active_kcal": null"""))
        assertTrue(line.contains(""""goal_met": null"""))
    }

    @Test
    fun `sessions declare their source and which boundary is still a guess`() {
        val lines = ExportDocuments.json(bundle()).lines()
        val manual = lines.first { it.contains(""""start": "2026-08-19T09:32:00""") }
        assertTrue(manual.contains(""""source": "manual""""))
        assertTrue(manual.contains(""""start_approx": false"""))
        assertTrue(manual.contains(""""end": "2026-08-19T10:18:00+02:00""""))
        assertTrue(manual.contains(""""avg_cadence_spm": 92"""))

        val auto = lines.first { it.contains(""""start": "2026-08-20T07:05:00""") }
        assertTrue(auto.contains(""""source": "auto""""))
        assertTrue(auto.contains(""""start_approx": true"""))
        assertTrue(auto.contains(""""end_approx": false"""))
        assertTrue(auto.contains(""""avg_cadence_spm": null"""))
        // The day a session belongs to, so the two tables join without parsing.
        assertTrue(auto.contains(""""date": "2026-08-20""""))
    }

    @Test
    fun `nothing recorded renders as empty arrays, never as absent keys`() {
        val json = ExportDocuments.json(bundle(days = emptyList(), sessions = emptyList()))
        assertTrue(json.contains("""  "days": [],"""))
        assertTrue(json.contains("""  "sessions": []"""))
    }

    @Test
    fun `days csv is one table with empty cells for what is missing`() {
        val rows = ExportDocuments.daysCsv(bundle()).trim().lines()
        assertEquals(
            "date,commit,steps,active_min,distance_m,stride_m,active_kcal," +
                "goal_steps,goal_met,committed",
            rows[0]
        )
        assertEquals("2026-08-19,3f2c1a9,11204,96,8310.2,0.742,312,10000,true,true", rows[1])
        // No weight, no goal: two empty cells — not zeros, which would be a lie.
        assertEquals("2026-08-20,a10c4e5,4120,41,3058.0,0.742,,0,,false", rows[2])
    }

    @Test
    fun `sessions csv keeps the local wall time with its offset`() {
        val rows = ExportDocuments.sessionsCsv(bundle()).trim().lines()
        assertEquals(
            "date,start,end,type,steps,distance_m,stride_m,active_min," +
                "avg_cadence_spm,source,start_approx,end_approx",
            rows[0]
        )
        assertEquals(
            "2026-08-19,2026-08-19T09:32:00+02:00,2026-08-19T10:18:00+02:00," +
                "walk,4210,3120.5,0.741,46,92,manual,false,false",
            rows[1]
        )
        assertEquals(
            "2026-08-20,2026-08-20T07:05:00+02:00,2026-08-20T07:40:00+02:00," +
                "walk,3010,2200.0,0.731,35,,auto,true,false",
            rows[2]
        )
    }

    @Test
    fun `numbers never follow the device locale`() {
        Locale.setDefault(Locale.ITALY)
        val row = ExportDocuments.daysCsv(bundle()).lines()[1]
        assertTrue(row.contains("8310.2"))
        assertTrue(ExportDocuments.json(bundle()).contains("8310.2"))
    }

    @Test
    fun `json is one file, csv is one table per file`() {
        val json = ExportDocuments.files(bundle(), ExportFormat.JSON)
        assertEquals(listOf("tsteps-export-2026-08-20.json"), json.map { it.name })
        assertEquals(ExportDocuments.JSON_MIME, json.single().mimeType)

        val csv = ExportDocuments.files(bundle(), ExportFormat.CSV)
        assertEquals(
            listOf("tsteps-days-2026-08-20.csv", "tsteps-sessions-2026-08-20.csv"),
            csv.map { it.name }
        )
        assertTrue(csv.all { it.mimeType == ExportDocuments.CSV_MIME })
        assertEquals(ExportDocuments.daysCsv(bundle()), csv[0].content)
        assertEquals(ExportDocuments.sessionsCsv(bundle()), csv[1].content)
    }

    @Test
    fun `the filename is stamped with the local export date, not UTC`() {
        // 00:30 in Rome is still the previous day in UTC: the user's calendar wins.
        val afterMidnight = bundle().copy(exportedAtMillis = millis("2026-08-21T00:30:00"))
        assertEquals(
            "tsteps-export-2026-08-21.json",
            ExportDocuments.files(afterMidnight, ExportFormat.JSON).single().name
        )
    }
}
