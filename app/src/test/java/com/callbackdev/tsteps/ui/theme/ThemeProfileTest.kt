package com.callbackdev.tsteps.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeProfileTest {

    @Test
    fun `fromName maps every profile by exact name`() {
        ThemeProfile.entries.forEach { profile ->
            assertEquals(profile, ThemeProfile.fromName(profile.name))
        }
    }

    @Test
    fun `fromName falls back to Obsidian for unknown names`() {
        assertEquals(ThemeProfile.Obsidian, ThemeProfile.fromName("solarized"))
        assertEquals(ThemeProfile.Obsidian, ThemeProfile.fromName(""))
    }
}
