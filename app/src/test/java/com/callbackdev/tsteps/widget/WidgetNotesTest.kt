package com.callbackdev.tsteps.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.callbackdev.tsteps.data.UnitsSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The guard on the widget's English, which exists twice on purpose.
 *
 * `WidgetContentBuilder` is a pure value builder — no `Context`, no `Resources`,
 * so its plain-JVM test can stay plain — and it pays for that with a copy of its
 * sentences in [WidgetNotes.EN]. Two copies drift the day somebody edits one, so
 * this ties them together: change either alone and the suite goes red. Comparing
 * the whole value rather than field by field means a sentence added tomorrow is
 * covered the moment it exists.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetNotesTest {

    private val resources
        get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `the English fallback is word for word what the resources say`() {
        assertEquals(WidgetNotes.EN, widgetNotes(resources))
    }

    @Test
    @Config(qualifiers = "it")
    fun `and in Italian the widget says something else`() {
        val italian = widgetNotes(resources)
        assertNotEquals(WidgetNotes.EN, italian)
        // Every field, not just the one that happens to differ first.
        assertNotEquals(WidgetNotes.EN.sensorOff, italian.sensorOff)
        assertNotEquals(WidgetNotes.EN.sensorOffShort, italian.sensorOffShort)
        assertNotEquals(WidgetNotes.EN.noDataYet, italian.noDataYet)
        assertNotEquals(WidgetNotes.EN.noData, italian.noData)
        assertNotEquals(WidgetNotes.EN.stepsToday, italian.stepsToday)
    }

    /**
     * The `#` is the transcript's syntax and the builder puts it there, so the
     * Italian line is a translated sentence behind an untranslated marker — both
     * halves of the seam, on the surface a launcher actually shows.
     */
    @Test
    @Config(qualifiers = "it")
    fun `the marker stays in front of the translated sentence`() {
        val content = WidgetContentBuilder.build(
            data = WidgetData(hasEverSampled = false, sensorOk = true),
            units = UnitsSystem.METRIC,
            tier = WidgetTier.Terminal(4),
            notes = widgetNotes(resources)
        )
        val line = content.bodyLines.single().text
        assertTrue(line, line.startsWith("# "))
        assertTrue(line, line.contains("apri tsteps"))
    }
}
