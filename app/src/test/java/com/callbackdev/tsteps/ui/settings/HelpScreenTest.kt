package com.callbackdev.tsteps.ui.settings

import androidx.compose.ui.test.assertIsSelected
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
 * `HELP.md` (Fase 17): the second file behind the Settings tab bar. It is a document,
 * so the test checks it renders as markdown source and stays part of the tab strip —
 * the vocabulary itself is copy, and copy is not something a test should freeze.
 */
@RunWith(RobolectricTestRunner::class)
class HelpScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var selected = -1

    private fun setScreen() {
        compose.setContent {
            TstepsTheme {
                HelpScreen(onSelectFile = { selected = it })
            }
        }
    }

    @Test
    fun `the document renders with its headings`() {
        setScreen()

        compose.onNodeWithText("# tsteps").assertExists()
        compose.onNodeWithText("## The four tabs").assertExists()
        compose.onNodeWithText("## The borrowed words").assertExists()
    }

    @Test
    fun `help is the open file of the settings tab strip`() {
        setScreen()

        compose.onNodeWithText("HELP.md").assertIsSelected()
    }

    @Test
    fun `the config file is one tap away`() {
        setScreen()

        compose.onNodeWithText("settings.config").performClick()

        assertEquals(0, selected)
    }
}
