package com.callbackdev.tsteps.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.callbackdev.tsteps.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The register rule as a test (`PLANNING.md` Fase 20), read off the resources
 * themselves rather than off any one screen.
 *
 * The per-screen tests check that a line is drawn; this checks the thing the rule
 * is actually about — that what moved is prose and what stayed is code — and it
 * checks it for **every** note at once.
 *
 * The list is taken by reflection over `R.string` rather than written out by
 * hand (tweather's version enumerates its notes, and that list has to be
 * remembered). Here the day somebody adds a note and leaves it in English, this
 * test already knows about it: nothing to keep in sync, so nothing to forget.
 */
@RunWith(RobolectricTestRunner::class)
class RegisterRuleTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val resources: Resources get() = context.resources

    /** Every `note_*` string the app prints in the comment channel. */
    private val notes: List<Pair<String, Int>> = R.string::class.java.declaredFields
        .filter { it.name.startsWith("note_") }
        .map { it.name to it.getInt(null) }
        .sortedBy { it.first }

    @Test
    fun `the sweep found the notes at all`() {
        // A reflective list that silently comes back empty would make every other
        // test in this class pass by vacuum.
        assertTrue("no note_* strings found", notes.size >= 60)
    }

    /**
     * The marker is the file's syntax and is added by the renderer, never by the
     * resource. A note that carried its own `//` would be a line no file with a
     * different comment channel could reuse — and the series has both (`#` in the
     * widget's transcript, `//` in the editor).
     */
    @Test
    fun `no note carries its own comment marker`() {
        notes.forEach { (name, id) ->
            val text = resources.getString(id)
            assertTrue("$name carries its own marker: '$text'",
                !text.startsWith("//") && !text.startsWith("#"))
        }
    }

    /**
     * And none of them carries a level either: `ERROR:` and `WARN:` are tokens of
     * the channel, the renderer puts them there, and a translated `ERRORE:` would
     * be the one word on the line a reader looking for a log level cannot find.
     */
    @Test
    fun `no note carries its own level`() {
        notes.forEach { (name, id) ->
            val text = resources.getString(id)
            assertTrue("$name carries a level: '$text'",
                !text.startsWith("ERROR") && !text.startsWith("WARN"))
        }
    }

    /**
     * Every note is actually translated. A rule kept sixty-four times out of
     * sixty-five does not read as a decision, it reads as a job somebody
     * abandoned halfway — which is the failure mode this phase wrote itself
     * against. tweather needed one exemption here; tsteps needs none, so there is
     * deliberately no allowlist to add to.
     */
    @Test
    @Config(qualifiers = "it")
    fun `every note says something different in Italian`() {
        val english = context.createConfigurationContext(
            Configuration(resources.configuration).apply { setLocale(Locale.ENGLISH) }
        ).resources
        val unchanged = notes
            .filter { (_, id) -> resources.getString(id) == english.getString(id) }
            .map { it.first }
        assertEquals("these notes were never translated: $unchanged", emptyList<String>(), unchanged)
    }

    /**
     * The tokens survive the translation. A file name, a key, a command or a unit
     * inside a localized sentence is still the thing the reader has to type or
     * look for, so it comes through both languages unchanged.
     */
    @Test
    @Config(qualifiers = "it")
    fun `the tokens inside a translated sentence survive it`() {
        assertTrue(resources.getString(R.string.note_no_commits).contains("commit"))
        assertTrue(resources.getString(R.string.note_uncommitted).contains("commit"))
        assertTrue(resources.getString(R.string.note_kcal_needs_weight).contains("weight_kg"))
        assertTrue(resources.getString(R.string.note_height_unused).contains("stride_cm"))
        assertTrue(resources.getString(R.string.note_export_intro).contains("Downloads/"))
        assertTrue(resources.getString(R.string.note_wrote).contains("Downloads/"))
        assertTrue(resources.getString(R.string.note_widget_no_data_yet).contains("tsteps"))
        assertTrue(resources.getString(R.string.note_on_device).contains("tsteps"))
        assertTrue(resources.getString(R.string.note_err_expected_range).contains("%1\$s"))
    }
}
