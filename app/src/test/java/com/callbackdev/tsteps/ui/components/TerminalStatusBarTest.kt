package com.callbackdev.tsteps.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The shrink entry's two regimes: with room it ellipsizes like an editor path,
 * starved it collapses to nothing — divider included. On device the editor bar
 * degraded to `⎇ main |  | sensor:…` when the right-hand side ate all the room;
 * a bar must drop an entry it cannot render, never exhibit its skeleton.
 */
@RunWith(RobolectricTestRunner::class)
class TerminalStatusBarTest {

    @get:Rule
    val compose = createComposeRule()

    private fun bar(width: Dp) {
        compose.setContent {
            TstepsTheme {
                Box(Modifier.width(width)) {
                    TerminalStatusBar {
                        StatusBarStart {
                            StatusBarText("⎇ main")
                            StatusBarText("2026-08-19", shrink = true, leadingDivider = true)
                        }
                        StatusBarText("commit: e5e5691")
                    }
                }
            }
        }
    }

    @Test
    fun `with room the shrink entry and its divider render`() {
        bar(400.dp)
        compose.onNodeWithText("2026-08-19").assertIsDisplayed()
        compose.onNodeWithText("|").assertIsDisplayed()
    }

    @Test
    fun `starved of room the shrink entry collapses, divider included`() {
        bar(220.dp)
        compose.onNodeWithText("⎇ main").assertIsDisplayed()
        compose.onNodeWithText("commit: e5e5691").assertIsDisplayed()
        // Collapsed = zero-size, still in the semantics tree: not-displayed, not gone.
        compose.onNodeWithText("2026-08-19").assertIsNotDisplayed()
        compose.onNodeWithText("|").assertIsNotDisplayed()
    }
}
