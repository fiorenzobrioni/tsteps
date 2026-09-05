package com.callbackdev.tsteps.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.width
import com.callbackdev.tsteps.ui.components.EditorOptions
import com.callbackdev.tsteps.ui.components.LocalEditorOptions
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private fun setScreen(wordWrap: Boolean = false) {
        compose.setContent {
            TstepsTheme {
                CompositionLocalProvider(
                    LocalEditorOptions provides EditorOptions(wordWrap = wordWrap)
                ) {
                    HelpScreen(onSelectFile = { selected = it })
                }
            }
        }
    }

    @Test
    fun `the document renders with its headings`() {
        setScreen()

        compose.onNodeWithText("# tsteps").assertExists()
        // Scrolled to rather than asserted in place: the file wraps (below), so its
        // paragraphs are several lines tall and the later headings start off-screen.
        listOf("## The four tabs", "## The borrowed words").forEach { heading ->
            compose.onNode(hasScrollToNodeAction())
                .performScrollToNode(hasText(heading))
            compose.onNodeWithText(heading).assertExists()
        }
    }

    @Test
    fun `help is the open file of the settings tab strip`() {
        setScreen()

        compose.onNodeWithText("HELP.md").assertIsSelected()
    }

    /**
     * Fase 22b: the one file that wraps whatever `settings.config` says. Its
     * paragraphs run to 300+ characters, and panning sideways through a sentence is
     * not reading — this is the surface addressed to somebody who cannot read the
     * app yet, so it is the one that cannot ask them to work for the words.
     */
    @Test
    fun `the document wraps even with word_wrap off`() {
        setScreen(wordWrap = false)

        val paragraph = compose.onNodeWithText("A step counter that", substring = true)
            .getUnclippedBoundsInRoot()
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue(
            "the paragraph is ${paragraph.width}, the screen ${screen.width}",
            paragraph.width <= screen.width
        )
    }

    @Test
    fun `the status bar declares the mode the file has of its own`() {
        setScreen(wordWrap = false)

        compose.onNodeWithText("wrap").assertExists()
    }

    @Test
    fun `the config file is one tap away`() {
        setScreen()

        compose.onNodeWithText("settings.config").performClick()

        assertEquals(0, selected)
    }
}
