package com.callbackdev.tsteps.ui.components

import androidx.compose.ui.test.assertIsNotSelected
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
 * The editor's tab strip. Fase 11b folded the single-file bar into this one
 * component, so a one-element strip must behave like any other: the open file is a
 * *selected tab*, which is what carries the active indicator that `cities.json` and
 * `widget.config` were missing.
 */
@RunWith(RobolectricTestRunner::class)
class EditorTabsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a single open file is a selected tab`() {
        compose.setContent {
            TstepsTheme {
                EditorTabs(fileNames = listOf("cities.json"), activeIndex = 0, onSelect = {})
            }
        }
        compose.onNodeWithText("cities.json").assertIsSelected()
    }

    @Test
    fun `tapping the only tab cannot deselect it`() {
        compose.setContent {
            TstepsTheme {
                EditorTabs(fileNames = listOf("widget.config"), activeIndex = 0, onSelect = {})
            }
        }
        compose.onNodeWithText("widget.config").performClick()
        compose.onNodeWithText("widget.config").assertIsSelected()
    }

    @Test
    fun `only the active file of a multi-file strip is selected`() {
        var selected = -1
        compose.setContent {
            TstepsTheme {
                EditorTabs(
                    fileNames = listOf("settings.config", "alerts.rules"),
                    activeIndex = 0,
                    onSelect = { selected = it }
                )
            }
        }
        compose.onNodeWithText("settings.config").assertIsSelected()
        compose.onNodeWithText("alerts.rules").assertIsNotSelected()

        compose.onNodeWithText("alerts.rules").performClick()
        assertEquals(1, selected)
    }
}
