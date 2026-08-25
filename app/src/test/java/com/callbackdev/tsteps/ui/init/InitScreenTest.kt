package com.callbackdev.tsteps.ui.init

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `$ tsteps init` (Fase 17): two answers, and both are answers — skipping included,
 * because the document behind already says the counter is off and offers the grant.
 */
@RunWith(RobolectricTestRunner::class)
class InitScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var granted = 0
    private var skipped = 0

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
}
