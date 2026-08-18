package com.callbackdev.tsteps.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsInputTest {

    @Test
    fun `empty input means cleared - the first-class empty state`() {
        assertEquals(NumericInput.Cleared, parseNumericInput(NumericField.GOAL, ""))
        assertEquals(NumericInput.Cleared, parseNumericInput(NumericField.WEIGHT, "   "))
        assertEquals(NumericInput.Cleared, parseNumericInput(NumericField.HEIGHT, ""))
    }

    @Test
    fun `valid values parse, comma decimal included`() {
        assertEquals(NumericInput.Value(10_000.0), parseNumericInput(NumericField.GOAL, "10000"))
        assertEquals(NumericInput.Value(78.5), parseNumericInput(NumericField.WEIGHT, "78,5"))
        assertEquals(NumericInput.Value(175.0), parseNumericInput(NumericField.HEIGHT, "175"))
    }

    @Test
    fun `out of range is rejected with the range in the error`() {
        val goal = parseNumericInput(NumericField.GOAL, "100001")
        assertTrue(goal is NumericInput.Invalid && goal.error.contains("0..100000"))
        val weight = parseNumericInput(NumericField.WEIGHT, "10")
        assertTrue(weight is NumericInput.Invalid && weight.error.contains("20..300"))
        val height = parseNumericInput(NumericField.HEIGHT, "90")
        assertTrue(height is NumericInput.Invalid && height.error.contains("100..250"))
    }

    @Test
    fun `goal and height must be integers`() {
        assertTrue(parseNumericInput(NumericField.GOAL, "9999.5") is NumericInput.Invalid)
        assertTrue(parseNumericInput(NumericField.HEIGHT, "175.5") is NumericInput.Invalid)
    }

    @Test
    fun `garbage is not a number`() {
        val result = parseNumericInput(NumericField.WEIGHT, "7f.2")
        assertTrue(result is NumericInput.Invalid && result.error.contains("not a number"))
    }

    @Test
    fun `weight formats with one decimal`() {
        assertEquals("78.0", formatWeight(78.0))
        assertEquals("78.5", formatWeight(78.5))
    }
}
