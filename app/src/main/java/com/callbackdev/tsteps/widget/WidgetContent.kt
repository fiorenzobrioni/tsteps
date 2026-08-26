package com.callbackdev.tsteps.widget

import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.domain.TrackerState
import com.callbackdev.tsteps.ui.format.UnitFormat
import com.callbackdev.tsteps.ui.steps.StepsGlyphs
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * How much the launcher has room for, chosen through the RemoteViews sizes map.
 *
 * The terminal tiers differ only in how many transcript lines fit, so the ladder
 * is simply a line budget with one rung per line (tweather's hard-won scale: a
 * coarse ladder means a widget with room for seven lines silently settles for
 * five, because the map only ever picks a rung that FITS).
 */
sealed interface WidgetTier {
    /** The glanceable strip: 👣, the count, the goal bar. Its own layout. */
    data object Small : WidgetTier

    data class Terminal(val lines: Int) : WidgetTier
}

/** Semantic color role of a token; the renderer maps roles to [WidgetPalette] ints. */
enum class TokenRole { PROMPT, PLAIN, DIM, KEY, STRING, NUMBER, COMMENT, ALERT }

data class WidgetToken(val text: String, val role: TokenRole)

data class TerminalLine(val tokens: List<WidgetToken>) {
    val text: String get() = tokens.joinToString("") { it.text }
}

/**
 * Everything a widget layout binds. [bodyLines] is what varies per tier; SMALL
 * ignores it and uses [smallValue]/[smallLabel] instead — lines, not strings, so
 * the small tier gets its colors from the same token roles as the rest.
 */
data class WidgetContent(
    val headerTitle: String,
    val promptLine: TerminalLine,
    val bodyLines: List<TerminalLine>,
    val emoji: String?,
    val smallValue: TerminalLine,
    val smallLabel: TerminalLine
)

/** What the updater gathers from the stores for one render pass. */
data class WidgetData(
    /** False until the very first sensor reading ever (no anchor yet). */
    val hasEverSampled: Boolean,
    /** Permission granted AND the hardware counter present. */
    val sensorOk: Boolean,
    val todaySteps: Long = 0,
    val goalSteps: Int = 0,
    val distanceMeters: Double = 0.0,
    val activeMinutes: Int = 0,
    val activeKcal: Double? = null,
    val streakDays: Int = 0,
    /** Today's most recent completed walk, for the `# last walk:` comment. */
    val lastWalkStartMillis: Long? = null,
    val lastWalkActiveMinutes: Int? = null,
    /** Auto-detected start (Fase 11): the comment wears the `~` too. */
    val lastWalkApprox: Boolean = false,
    /**
     * When the counter was last **read** ([TrackerState.lastReadMillis]), not when
     * the steps it reported were walked. The two are not the same number and using
     * the wrong one made this a measure of how long the user had been sitting
     * still: a healthy widget wore `# stale` after 45 quiet minutes, and no ↻ tap
     * could clear it because the next read returned the same old event.
     */
    val lastSyncMillis: Long? = null
)

/**
 * Pure mapping from persisted state to the terminal transcript the widget shows.
 * Keys, prompt and `#` comments are code and stay English (the widget is a
 * terminal window); the emoji is the brand's 👣, constant on purpose. Field
 * names are Capitalized like tweather's widget (device feedback: the two
 * widgets sit on the same home screen and must read as siblings) — the `#`
 * comments stay lowercase, they're comments.
 */
object WidgetContentBuilder {

    const val HEADER = "tsteps --today"
    const val EMOJI = "👣"

    /**
     * The longest transcript this builder can produce (steps, check, dist,
     * active, kcal, streak, last walk, last_sync). The renderer's ladder stops
     * here: a rung nothing can fill just wastes the height it promises — the
     * measured-rung test is what caught this.
     */
    const val MAX_LINES = 8

    /** Terminal shorthand, so it stays English like every other `#` comment. */
    const val STALE_MARKER = "  # stale"

    /**
     * A *read* this old means something is wrong (jobs throttled hard, permission
     * revoked mid-flight): three sync periods absorbs ordinary Doze stretching,
     * anything past it is worth flagging instead of posing as current. Sitting
     * still is not one of those things — see [WidgetData.lastSyncMillis].
     */
    private val StaleAfter: Duration = Duration.ofMinutes(45)

    /** Narrower than the app's 16-cell bar: widget columns are precious. */
    private const val BAR_WIDTH = 10

    fun build(
        data: WidgetData,
        units: UnitsSystem,
        tier: WidgetTier,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.ENGLISH,
        now: Instant? = null
    ): WidgetContent {
        val prompt = TerminalLine(
            listOf(
                WidgetToken("you@tsteps", TokenRole.PROMPT),
                WidgetToken(":~", TokenRole.PLAIN),
                WidgetToken("$ ", TokenRole.PLAIN),
                WidgetToken("cat steps_data.json", TokenRole.DIM)
            )
        )
        if (!data.sensorOk) {
            return WidgetContent(
                headerTitle = HEADER,
                promptLine = prompt,
                bodyLines = listOf(token("# sensor off — open tsteps", TokenRole.ALERT)),
                emoji = EMOJI,
                smallValue = token("--", TokenRole.NUMBER),
                smallLabel = token("# sensor off", TokenRole.ALERT)
            )
        }
        if (!data.hasEverSampled) {
            return WidgetContent(
                headerTitle = HEADER,
                promptLine = prompt,
                bodyLines = listOf(comment("# no data yet — open tsteps")),
                emoji = EMOJI,
                smallValue = token("--", TokenRole.NUMBER),
                smallLabel = comment("# no data")
            )
        }

        val numbers = NumberFormat.getIntegerInstance(locale)
        val steps = numbers.format(data.todaySteps)
        val hasGoal = data.goalSteps > 0
        val stale = isStale(data.lastSyncMillis, now)
        val syncLine = data.lastSyncMillis?.let {
            val stamp = UnitFormat.clockTime(it, zone)
            // the timestamp is its own evidence, so the whole line turns red
            token("# last_sync: $stamp", if (stale) TokenRole.ALERT else TokenRole.COMMENT)
        }

        // The whole transcript, most useful first. The tier decides how much of it
        // is shown — no per-tier branching, so a new rung needs no new code here.
        val transcript = buildList {
            add(
                TerminalLine(
                    listOfNotNull(
                        WidgetToken("Steps", TokenRole.KEY),
                        WidgetToken(": ", TokenRole.PLAIN),
                        WidgetToken(steps, TokenRole.NUMBER),
                        WidgetToken(" / ${numbers.format(data.goalSteps)}", TokenRole.DIM)
                            .takeIf { hasGoal }
                    )
                )
            )
            if (hasGoal) {
                // Prompt green: DESIGN.md's "emerald green represents active states".
                add(kv("Check", StepsGlyphs.goalBar(data.todaySteps, data.goalSteps, BAR_WIDTH), TokenRole.PROMPT))
            }
            add(kv("Dist", UnitFormat.distance(data.distanceMeters, units), TokenRole.NUMBER))
            add(kv("Active", "${data.activeMinutes} min", TokenRole.NUMBER))
            data.activeKcal?.let { add(kv("Kcal", numbers.format(it.toInt()), TokenRole.NUMBER)) }
            if (hasGoal && data.streakDays > 0) {
                add(kv("Streak", "${data.streakDays} days", TokenRole.NUMBER))
            }
            if (data.lastWalkStartMillis != null && data.lastWalkActiveMinutes != null) {
                val start = UnitFormat.clockTime(
                    data.lastWalkStartMillis, zone, data.lastWalkApprox
                )
                add(comment("# last walk: $start (${data.lastWalkActiveMinutes} min)"))
            }
            syncLine?.let { add(it) }
        }

        val lines = transcript.take(bodyLineBudget(tier)).toMutableList()
        // The stale marker rides the steps line on the tiers too short for the
        // last_sync line — that is where the eye lands (tweather's Temp-line rule).
        if (stale && lines.none { it === syncLine } && lines.isNotEmpty()) {
            lines[0] = TerminalLine(lines[0].tokens + WidgetToken(STALE_MARKER, TokenRole.ALERT))
        }
        return WidgetContent(
            headerTitle = HEADER,
            promptLine = prompt,
            bodyLines = lines,
            emoji = EMOJI,
            smallValue = TerminalLine(
                listOfNotNull(
                    WidgetToken(steps, TokenRole.NUMBER),
                    WidgetToken(STALE_MARKER, TokenRole.ALERT).takeIf { stale }
                )
            ),
            // With a goal the label is the bar itself — glanceable progress; without
            // one, plain text (comment gray only clears ~3:1 on Dracula/Monokai).
            smallLabel = if (hasGoal) {
                token(StepsGlyphs.goalBar(data.todaySteps, data.goalSteps, BAR_WIDTH), TokenRole.PROMPT)
            } else {
                token("steps today", TokenRole.PLAIN)
            }
        )
    }

    /** Body lines the tier has room for; [WidgetTier.Small] renders none of them. */
    fun bodyLineBudget(tier: WidgetTier): Int = when (tier) {
        is WidgetTier.Small -> 0
        is WidgetTier.Terminal -> tier.lines
    }

    private fun isStale(lastSyncMillis: Long?, now: Instant?): Boolean {
        if (lastSyncMillis == null || now == null) return false
        return Duration.between(Instant.ofEpochMilli(lastSyncMillis), now) > StaleAfter
    }

    private fun kv(key: String, value: String, role: TokenRole) = TerminalLine(
        listOf(
            WidgetToken(key, TokenRole.KEY),
            WidgetToken(": ", TokenRole.PLAIN),
            WidgetToken(value, role)
        )
    )

    private fun comment(text: String) = token(text, TokenRole.COMMENT)

    private fun token(text: String, role: TokenRole) =
        TerminalLine(listOf(WidgetToken(text, role)))
}
