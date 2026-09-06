package com.callbackdev.tsteps.ui.init

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.callbackdev.tsteps.R
import com.callbackdev.tsteps.ui.theme.ObsidianSyntax
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `$ tsteps init` (Fase 17): two answers, and both are answers — skipping included,
 * because the document behind already says the counter is off and offers the grant.
 *
 * Since Fase 23 the transcript prints itself, so every test about its *content* asks
 * for the still version first — which is not a testing trick but the screen a phone
 * with "Remove animations" on actually gets, and therefore worth asserting. The
 * tests at the bottom are about the animation itself.
 */
@RunWith(RobolectricTestRunner::class)
// A phone, not Robolectric's default 320x470 handset: the transcript keeps its last
// line in sight, so on a screen too short for it the top of the session has honestly
// scrolled away and these assertions would be measuring a device nobody ships.
@Config(qualifiers = "w360dp-h740dp")
class InitScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var granted = 0
    private var skipped = 0

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun animations(on: Boolean) {
        Settings.Global.putFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            if (on) 1f else 0f
        )
    }

    @Before
    fun stillByDefault() = animations(on = false)

    private fun setScreen(permissionDenied: Boolean = false) {
        compose.setContent {
            TstepsTheme {
                InitScreen(
                    onGrant = { granted++ },
                    onSkip = { skipped++ },
                    permissionDenied = permissionDenied
                )
            }
        }
    }

    @Test
    fun `the command and both ways out are on screen`() {
        setScreen()

        compose.onNodeWithText("tsteps init", substring = true).assertExists()
        compose.onNodeWithText("turn on the step counter", substring = true).assertExists()
        compose.onNodeWithText("skip", substring = true).assertExists()
    }

    @Test
    fun `each choice reports itself once`() {
        setScreen()

        compose.onNodeWithText("> turn on the step counter").performClick()
        compose.onNodeWithText("> skip").performClick()

        assertEquals(1, granted)
        assertEquals(1, skipped)
    }

    /** A denied permission must not dead-end the screen: skipping stays available. */
    @Test
    fun `a denied permission is said out loud and leaves the way out`() {
        setScreen(permissionDenied = true)

        compose.onNodeWithText("permission denied", substring = true).assertExists()
        compose.onNodeWithText("> skip").performClick()

        assertEquals(1, skipped)
    }

    @Test
    fun `the setup session is the only open file`() {
        setScreen()

        compose.onNodeWithText(SetupFile).assertExists()
    }

    /**
     * The screen says what the app IS before it says what it needs. The four `#`
     * lines are the whole reason the transcript is worth printing rather than
     * pasting, so two of them are held here.
     */
    @Test
    fun `the session introduces the app before asking for anything`() {
        setScreen()

        compose.onNodeWithText("code editor", substring = true).assertExists()
        compose.onNodeWithText("No network", substring = true).assertExists()
    }

    // ---- the animation -----------------------------------------------------

    /**
     * "Remove animations" is not a slower animation: it is no animation. The whole
     * transcript, choices included, has to be there on the frame the screen opens —
     * not a fade later, which is what a half-hearted implementation would leave.
     */
    @Test
    fun `with animations off the transcript is whole on the first frame`() {
        animations(on = false)
        compose.mainClock.autoAdvance = false
        setScreen()
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText("> skip").assertExists()
        compose.onNodeWithText("code editor", substring = true).assertExists()
    }

    /**
     * Tap-to-skip: the touch that ends the printing lands on the transcript, never
     * on a choice. Somebody impatient enough to tap is not somebody who wanted to
     * put a system permission dialog up by accident.
     */
    @Test
    fun `a tap ends the printing without answering the question`() {
        animations(on = true)
        compose.mainClock.autoAdvance = false
        setScreen()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("> skip").assertDoesNotExist()

        compose.onRoot().performTouchInput { down(center); up() }
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText("> skip").assertExists()
        assertEquals(0, granted)
        assertEquals(0, skipped)
    }

    /**
     * The budget, in both languages — and a **range** since Fase 27b.
     *
     * The ceiling is the old reason: the copy is free to grow, but not past the point
     * where a first-run screen starts costing the reader time. This is the test to
     * argue with before the intro becomes a carousel by accretion.
     *
     * The floor is the device's answer. At five hundred characters a second the whole
     * session was over in a second and a half, and that does not read as writing — it
     * reads as a flicker, which is the "fuffa" the animation exists to avoid. "Under
     * two seconds" was the guard that had allowed it, so the guard now has two ends.
     * Italian is the longer of the two languages and both are checked.
     */
    private val budgetMs = 2_000L..4_000L

    @Test
    fun `the whole session prints inside its budget`() {
        assertTrue("English: ${sessionMs(context)}ms", sessionMs(context) in budgetMs)
    }

    @Test
    @Config(qualifiers = "+it")
    fun `the italian session prints inside its budget too`() {
        assertTrue("Italian: ${sessionMs(context)}ms", sessionMs(context) in budgetMs)
    }

    private fun sessionMs(context: Context): Long = Typist(
        buildInitScript(
            syntax = ObsidianSyntax,
            intro = context.getString(R.string.init_intro),
            files = context.getString(R.string.init_files),
            privacy = context.getString(R.string.init_privacy),
            ask = context.getString(R.string.init_ask),
            grant = context.getString(R.string.init_option_grant),
            grantNote = context.getString(R.string.init_option_grant_note),
            skip = context.getString(R.string.init_option_skip),
            skipNote = context.getString(R.string.init_option_skip_note),
            denied = null,
            onGrant = {},
            onSkip = {}
        )
    ).totalMs
}
