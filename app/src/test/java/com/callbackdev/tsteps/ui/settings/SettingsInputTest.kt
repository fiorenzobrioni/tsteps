package com.callbackdev.tsteps.ui.settings

import com.callbackdev.tsteps.R
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

    /**
     * The range travels as the sentence's argument, not baked into it: the
     * numbers are code (they line up with the value the file prints) and the
     * sentence around them is the reader's language, added by the screen.
     */
    @Test
    fun `out of range is rejected with the range as the error's argument`() {
        val goal = parseNumericInput(NumericField.GOAL, "100001") as NumericInput.Invalid
        assertEquals(R.string.note_err_expected_range, goal.id)
        assertEquals(listOf("0..100000"), goal.args)
        val weight = parseNumericInput(NumericField.WEIGHT, "10") as NumericInput.Invalid
        assertEquals(listOf("20..300 kg"), weight.args)
        val height = parseNumericInput(NumericField.HEIGHT, "90") as NumericInput.Invalid
        assertEquals(listOf("100..250 cm"), height.args)
    }

    @Test
    fun `goal and height must be integers`() {
        assertTrue(parseNumericInput(NumericField.GOAL, "9999.5") is NumericInput.Invalid)
        assertTrue(parseNumericInput(NumericField.HEIGHT, "175.5") is NumericInput.Invalid)
    }

    @Test
    fun `garbage is not a number`() {
        val result = parseNumericInput(NumericField.WEIGHT, "7f.2") as NumericInput.Invalid
        assertEquals(R.string.note_err_not_a_number, result.id)
        assertTrue(result.args.isEmpty())
    }

    @Test
    fun `weight formats with one decimal`() {
        assertEquals("78.0", formatWeight(78.0))
        assertEquals("78.5", formatWeight(78.5))
    }
}
