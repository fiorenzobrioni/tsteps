package com.callbackdev.tsteps.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.callbackdev.tsteps.R
import com.callbackdev.tsteps.data.UnitsSystem
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The renderer is only observable through the view tree the launcher would get,
 * so every test inflates the RemoteViews for real (`apply`) and asserts on the
 * resulting Views — which also proves the layouts stay RemoteViews-compatible.
 * The measuring tests are tweather's: breakpoints get measured, never trusted.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val palette = widgetPalette("Obsidian")
    private val rome = ZoneId.of("Europe/Rome")

    private fun millis(dateTime: String): Long =
        LocalDateTime.parse(dateTime).atZone(rome).toInstant().toEpochMilli()

    private fun fullData() = WidgetData(
        hasEverSampled = true,
        sensorOk = true,
        todaySteps = 8_432,
        goalSteps = 10_000,
        distanceMeters = 6_123.0,
        activeMinutes = 74,
        activeKcal = 327.0,
        streakDays = 6,
        lastWalkStartMillis = millis("2026-08-18T09:32:00"),
        lastWalkActiveMinutes = 46,
        lastSyncMillis = millis("2026-08-18T14:32:00")
    )

    private fun content(tier: WidgetTier, data: WidgetData = fullData()) =
        WidgetContentBuilder.build(data, UnitsSystem.METRIC, tier, rome, Locale.ENGLISH, null)

    private fun inflate(
        content: WidgetContent,
        tier: WidgetTier,
        opacityPct: Int = 100,
        syncing: Boolean = false
    ): View = WidgetRenderer.render(context, content, palette, opacityPct, tier, syncing)
        .apply(context, FrameLayout(context))

    private fun View.text(id: Int): String = findViewById<TextView>(id).text.toString()

    private fun View.visibility(id: Int): Int = findViewById<View>(id).visibility

    /** Color of the token covering [index] — spans are the only carrier of color. */
    private fun View.tokenColorAt(id: Int, index: Int): Int {
        val text = findViewById<TextView>(id).text as Spanned
        return text.getSpans(index, index + 1, ForegroundColorSpan::class.java)
            .single()
            .foregroundColor
    }

    @Test
    fun mediumBindsHeaderPromptAndFourBodyLines() {
        val view = inflate(content(WidgetTier.Terminal(4)), WidgetTier.Terminal(4))

        assertEquals(WidgetContentBuilder.HEADER, view.text(R.id.widget_title))
        assertEquals("you@tsteps:~$ cat steps_data.json", view.text(R.id.widget_prompt))
        assertEquals("Steps: 8,432 / 10,000", view.text(R.id.widget_line1))
        assertEquals("Check: ▓▓▓▓▓▓▓▓░░ 84%", view.text(R.id.widget_line2))
        assertEquals("Dist: 6.1 km", view.text(R.id.widget_line3))
        assertEquals("Active: 74 min", view.text(R.id.widget_line4))
        // token colors survive the RemoteViews round-trip
        assertEquals(palette.key, view.tokenColorAt(R.id.widget_line1, 0))
        assertEquals(palette.number, view.tokenColorAt(R.id.widget_line1, 7))
        assertEquals(palette.prompt, view.tokenColorAt(R.id.widget_line2, 7))
        // The medium layout stops at 4 slots on purpose.
        assertNull(view.findViewById<TextView>(R.id.widget_line5))
        assertEquals(WidgetContentBuilder.EMOJI, view.text(R.id.widget_emoji))
    }

    @Test
    fun largeHidesTheSlotsWithoutAContentLine() {
        // No goal, no weight, no walk, no sync: only steps/dist/active remain.
        val sparse = WidgetData(
            hasEverSampled = true, sensorOk = true,
            todaySteps = 1_000, distanceMeters = 700.0, activeMinutes = 10
        )
        val view = inflate(content(WidgetTier.Terminal(11), sparse), WidgetTier.Terminal(11))

        listOf(R.id.widget_line1, R.id.widget_line2, R.id.widget_line3)
            .forEach { assertEquals(View.VISIBLE, view.visibility(it)) }
        listOf(
            R.id.widget_line4, R.id.widget_line5, R.id.widget_line6, R.id.widget_line7,
            R.id.widget_line8, R.id.widget_line9, R.id.widget_line10, R.id.widget_line11
        ).forEach { assertEquals(View.GONE, view.visibility(it)) }
    }

    @Test
    fun smallBindsCountAndGoalBar() {
        val view = inflate(content(WidgetTier.Small), WidgetTier.Small)

        assertEquals("8,432", view.text(R.id.widget_small_value))
        assertEquals("▓▓▓▓▓▓▓▓░░ 84%", view.text(R.id.widget_small_label))
        assertEquals(palette.number, view.tokenColorAt(R.id.widget_small_value, 0))
        assertEquals(palette.prompt, view.tokenColorAt(R.id.widget_small_label, 0))
    }

    /**
     * The tap's acknowledgment has to reach the sizes people actually place, and
     * `# last_sync` is last in the transcript — the medium tier cuts it. So the
     * glyph carries it, on every tier, and comes back on the next repaint.
     */
    @Test
    fun theRefreshGlyphWearsTheTapOnEveryTier() {
        val idle = context.getString(R.string.widget_refresh_glyph)
        val busy = context.getString(R.string.widget_refresh_glyph_busy)

        listOf(WidgetTier.Small, WidgetTier.Terminal(4), WidgetTier.Terminal(8)).forEach { tier ->
            val waiting = inflate(content(tier), tier, syncing = true)
            assertEquals(busy, waiting.text(R.id.widget_refresh))
            assertEquals(palette.comment, waiting.findViewById<TextView>(R.id.widget_refresh).currentTextColor)

            val settled = inflate(content(tier), tier)
            assertEquals(idle, settled.text(R.id.widget_refresh))
            assertEquals(palette.plain, settled.findViewById<TextView>(R.id.widget_refresh).currentTextColor)
        }
    }

    @Test
    fun sensorOffRendersTheRedError() {
        val view = inflate(
            content(WidgetTier.Terminal(4), fullData().copy(sensorOk = false)),
            WidgetTier.Terminal(4)
        )
        assertEquals("# sensor off — open tsteps", view.text(R.id.widget_line1))
        assertEquals(palette.alert, view.tokenColorAt(R.id.widget_line1, 0))
        assertEquals(View.GONE, view.visibility(R.id.widget_line2))
    }

    @Test
    fun theFillLayerCarriesTheOpacityTheSettingAsksFor() {
        val fill = layeredBackground(opacityPct = 50).first
        val pixel = fill.centerPixel()
        // 50% of 255 = 127±: the alpha must actually land on the pixels.
        assertTrue("fill alpha ${Color.alpha(pixel)} not ~50%", Color.alpha(pixel) in 120..135)
        // ...and still be the Obsidian background underneath.
        assertNear(0x10, Color.red(pixel))
        assertNear(0x14, Color.green(pixel))
        assertNear(0x1A, Color.blue(pixel))
    }

    @Test
    fun theBorderLayerPaintsNothingButItsFrame() {
        val border = layeredBackground(opacityPct = 50).second

        // A filled border layer would sit opaque on top of the fill and make the
        // opacity setting look broken (tweather's GradientDrawable bug, kept fixed).
        assertEquals(
            "the frame layer must stay hollow, or it hides the fill underneath",
            0,
            Color.alpha(border.centerPixel())
        )
        // ...and hollow must not mean absent: the frame stays opaque, theme-colored.
        val edge = (0..2).map { border.getPixel(border.width / 2, it) }
            .maxBy { Color.alpha(it) }
        assertTrue("the frame edge is missing", Color.alpha(edge) > 100)
        assertNear(0x30, Color.red(edge))
        assertNear(0x36, Color.green(edge))
        assertNear(0x3D, Color.blue(edge))
    }

    /**
     * A sizes-map key promises the layout FITS at that size — the host clips
     * silently otherwise, so the breakpoints get measured, not trusted.
     */
    @Test
    fun everyTierFitsInsideItsOwnBreakpoint() {
        val density = context.resources.displayMetrics.density

        WidgetRenderer.breakpoints().forEach { (tier, size) ->
            val root = inflate(content(tier), tier)
            val widthPx = (size.width * density).toInt()
            val heightPx = (size.height * density).toInt()
            root.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, widthPx, heightPx)

            val visible = (if (tier == WidgetTier.Small) {
                listOf(R.id.widget_small_value, R.id.widget_small_label)
            } else {
                listOf(
                    R.id.widget_title, R.id.widget_prompt,
                    R.id.widget_line1, R.id.widget_line2, R.id.widget_line3, R.id.widget_line4
                )
            }).mapNotNull { root.findViewById<View>(it) }.filter { it.visibility == View.VISIBLE }

            visible.forEach { line ->
                val bottom = IntArray(2).also { line.getLocationInWindow(it) }[1] + line.height
                assertTrue(
                    "$tier clips a line at its own ${size.width}x${size.height}dp breakpoint",
                    bottom <= heightPx
                )
            }
        }
    }

    /**
     * A rung taller than its transcript needs is not harmless: the launcher only
     * picks a rung that fits, so every wasted dp is a line the user paid for in
     * screen space and did not get. Binary-search the real minimum and hold the
     * promised height close to it (tweather's test, worth 30dp per rung there).
     */
    @Test
    fun noRungClaimsMoreHeightThanItsTranscriptNeeds() {
        val density = context.resources.displayMetrics.density
        val widthPx = (200 * density).toInt()

        val slack = WidgetRenderer.breakpoints()
            .filterKeys { it is WidgetTier.Terminal }
            .toSortedMap(compareBy { WidgetContentBuilder.bodyLineBudget(it) })
            .map { (tier, size) ->
                val lines = WidgetContentBuilder.bodyLineBudget(tier)
                var low = 0
                var high = (500 * density).toInt()
                while (low < high) {
                    val mid = (low + high) / 2
                    if (fitsAt(tier, widthPx, mid, lines)) high = mid else low = mid + 1
                }
                Triple(lines, low / density, size.height)
            }

        val wrong = slack.filter { (lines, needed, promised) ->
            promised < needed || promised - needed > 6f + 2.5f * lines
        }
        assertTrue(
            "rungs out of step (lines, needed dp, promised dp): $wrong — all: $slack",
            wrong.isEmpty()
        )
    }

    /** True when every bound line is fully inside a widget of [heightPx]. */
    private fun fitsAt(tier: WidgetTier, widthPx: Int, heightPx: Int, lines: Int): Boolean {
        val root = inflate(content(tier), tier)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, widthPx, heightPx)
        return (1..lines).all { slot ->
            val id = context.resources.getIdentifier(
                "widget_line$slot", "id", context.packageName
            )
            val view = root.findViewById<View>(id) ?: return@all true
            if (view.visibility != View.VISIBLE) return@all true
            val bottom = IntArray(2).also { view.getLocationInWindow(it) }[1] + view.height
            view.height > 0 && bottom <= heightPx
        }
    }

    /** Both background layers of a laid-out MEDIUM widget, each drawn alone. */
    private fun layeredBackground(opacityPct: Int): Pair<Bitmap, Bitmap> {
        val root = inflate(content(WidgetTier.Terminal(4)), WidgetTier.Terminal(4), opacityPct)
        val size = (200 * context.resources.displayMetrics.density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, size, size)
        return root.drawAlone(R.id.widget_bg_fill) to root.drawAlone(R.id.widget_bg_border)
    }

    private fun View.drawAlone(id: Int): Bitmap {
        val target = findViewById<View>(id)
        return Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
            .also { target.draw(Canvas(it)) }
    }

    private fun Bitmap.centerPixel(): Int = getPixel(width / 2, height / 2)

    private fun assertNear(expected: Int, actual: Int) =
        assertTrue("expected ~$expected but was $actual", actual in (expected - 3)..(expected + 3))
}
