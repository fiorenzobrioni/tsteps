package com.callbackdev.tsteps.widget

import com.callbackdev.tsteps.data.UnitsSystem
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetContentBuilderTest {

    private val rome = ZoneId.of("Europe/Rome")

    private fun millis(dateTime: String): Long =
        LocalDateTime.parse(dateTime).atZone(rome).toInstant().toEpochMilli()

    private fun data(
        goal: Int = 10_000,
        kcal: Double? = 327.0,
        streak: Int = 6,
        lastSync: Long? = millis("2026-08-18T14:32:00")
    ) = WidgetData(
        hasEverSampled = true,
        sensorOk = true,
        todaySteps = 8_432,
        goalSteps = goal,
        distanceMeters = 6_123.0,
        activeMinutes = 74,
        activeKcal = kcal,
        streakDays = streak,
        lastWalkStartMillis = millis("2026-08-18T09:32:00"),
        lastWalkActiveMinutes = 46,
        lastSyncMillis = lastSync
    )

    private fun build(
        data: WidgetData = data(),
        tier: WidgetTier = WidgetTier.Terminal(11),
        now: Instant? = Instant.ofEpochMilli(millis("2026-08-18T14:40:00"))
    ) = WidgetContentBuilder.build(data, UnitsSystem.METRIC, tier, rome, Locale.ENGLISH, now)

    private fun WidgetContent.texts() = bodyLines.map { it.text }

    @Test
    fun `the full transcript, most useful first`() {
        val lines = build().texts()
        assertEquals(
            listOf(
                "Steps: 8,432 / 10,000",
                "Check: ▓▓▓▓▓▓▓▓░░ 84%",
                "Dist: 6.1 km",
                "Active: 74 min",
                "Kcal: 327",
                "Streak: 6 days",
                "# last walk: 09:32 (46 min)",
                "# last_sync: 14:32"
            ),
            lines
        )
        assertEquals("you@tsteps:~$ cat steps_data.json", build().promptLine.text)
        assertEquals(WidgetContentBuilder.EMOJI, build().emoji)
    }

    @Test
    fun `the tier budget cuts from the bottom, never reorders`() {
        val lines = build(tier = WidgetTier.Terminal(4)).texts()
        assertEquals(
            listOf("Steps: 8,432 / 10,000", "Check: ▓▓▓▓▓▓▓▓░░ 84%", "Dist: 6.1 km", "Active: 74 min"),
            lines
        )
    }

    @Test
    fun `no goal means no goal fraction, no check, no streak`() {
        val lines = build(data(goal = 0, streak = 0)).texts()
        assertEquals("Steps: 8,432", lines.first())
        assertTrue(lines.none { it.startsWith("Check") || it.startsWith("Streak") })
    }

    @Test
    fun `no weight means no kcal line`() {
        assertTrue(build(data(kcal = null)).texts().none { it.startsWith("Kcal") })
    }

    @Test
    fun `stale sampling turns the sync line red`() {
        val content = build(now = Instant.ofEpochMilli(millis("2026-08-18T16:00:00")))
        val syncLine = content.bodyLines.last()
        assertEquals(TokenRole.ALERT, syncLine.tokens.single().role)
    }

    @Test
    fun `on short tiers the stale marker rides the steps line`() {
        val content = build(
            tier = WidgetTier.Terminal(4),
            now = Instant.ofEpochMilli(millis("2026-08-18T16:00:00"))
        )
        val steps = content.bodyLines.first()
        assertTrue(steps.text.endsWith(WidgetContentBuilder.STALE_MARKER))
        assertEquals(TokenRole.ALERT, steps.tokens.last().role)
        assertTrue(content.smallValue.text.endsWith(WidgetContentBuilder.STALE_MARKER))
    }

    @Test
    fun `fresh sampling stays calm`() {
        val content = build()
        assertTrue(content.bodyLines.none { it.text.contains("# stale") })
        assertEquals(TokenRole.COMMENT, content.bodyLines.last().tokens.single().role)
    }

    @Test
    fun `sensor off is a red terminal error, everywhere`() {
        val content = build(data().copy(sensorOk = false))
        assertEquals("# sensor off — open tsteps", content.bodyLines.single().text)
        assertEquals(TokenRole.ALERT, content.bodyLines.single().tokens.single().role)
        assertEquals("# sensor off", content.smallLabel.text)
        assertEquals("--", content.smallValue.text)
    }

    @Test
    fun `never sampled is an honest comment, not a zero`() {
        val content = build(data().copy(hasEverSampled = false))
        assertEquals("# no data yet — open tsteps", content.bodyLines.single().text)
        assertEquals(TokenRole.COMMENT, content.bodyLines.single().tokens.single().role)
    }

    @Test
    fun `the small tier gets the count and the goal bar`() {
        val content = build(tier = WidgetTier.Small)
        assertEquals("8,432", content.smallValue.text)
        assertEquals("▓▓▓▓▓▓▓▓░░ 84%", content.smallLabel.text)
        assertEquals(TokenRole.PROMPT, content.smallLabel.tokens.single().role)
        // and no body lines at all
        assertEquals(0, WidgetContentBuilder.bodyLineBudget(WidgetTier.Small))
    }

    @Test
    fun `without a goal the small label is plain words`() {
        val content = build(data(goal = 0), tier = WidgetTier.Small)
        assertEquals("steps today", content.smallLabel.text)
        assertEquals(TokenRole.PLAIN, content.smallLabel.tokens.single().role)
    }

    @Test
    fun `imperial units convert the distance line`() {
        val content = WidgetContentBuilder.build(
            data(), UnitsSystem.IMPERIAL, WidgetTier.Terminal(11), rome, Locale.ENGLISH, null
        )
        assertTrue(content.texts().any { it == "Dist: 3.8 mi" })
    }
}
