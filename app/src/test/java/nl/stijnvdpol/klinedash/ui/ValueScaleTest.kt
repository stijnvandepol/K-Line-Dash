package nl.stijnvdpol.klinedash.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValueScaleTest {

    @Test
    fun `a value that fits keeps its size when it may not grow`() {
        assertEquals(1f, valueScale(slotWidth = 300f, availableWidth = 500f, digitHeight = 100f, availableHeight = 400f, maxGrowth = 1f), 0f)
    }

    @Test
    fun `a value wider than its field shrinks to the field`() {
        assertEquals(0.5f, valueScale(slotWidth = 400f, availableWidth = 200f, digitHeight = 100f, availableHeight = 400f, maxGrowth = 1f), 0f)
    }

    @Test
    fun `a value taller than the room above its baseline shrinks to it`() {
        assertEquals(0.5f, valueScale(slotWidth = 100f, availableWidth = 500f, digitHeight = 200f, availableHeight = 100f, maxGrowth = 1f), 0f)
    }

    @Test
    fun `a big field lets the value grow up to the limit`() {
        assertEquals(1.5f, valueScale(slotWidth = 200f, availableWidth = 900f, digitHeight = 100f, availableHeight = 900f, maxGrowth = 1.5f), 0f)
    }

    @Test
    fun `growth stops at the field even below the limit`() {
        assertEquals(1.25f, valueScale(slotWidth = 400f, availableWidth = 500f, digitHeight = 100f, availableHeight = 900f, maxGrowth = 1.5f), 0f)
    }

    @Test
    fun `a field with no room left gives a tiny positive size, never a negative one`() {
        // A phone with a short screen can leave no height above the baseline; a negative scale made the line height negative and crashed.
        val scale = valueScale(slotWidth = 100f, availableWidth = 500f, digitHeight = 100f, availableHeight = -30f, maxGrowth = 1f)
        assertTrue(scale > 0f)
        assertTrue(scale < 0.1f)
    }
}
