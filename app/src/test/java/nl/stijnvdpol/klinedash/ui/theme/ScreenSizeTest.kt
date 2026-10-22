package nl.stijnvdpol.klinedash.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSizeTest {
    @Test
    fun `a phone in landscape is compact`() {
        assertEquals(SizeTier.COMPACT, sizeTierOf(360f))
        assertEquals(SizeTier.COMPACT, sizeTierOf(411f))
        assertEquals(SizeTier.COMPACT, sizeTierOf(479.9f))
    }

    @Test
    fun `a 1024 x 600 head unit is medium`() {
        assertEquals(SizeTier.MEDIUM, sizeTierOf(480f))
        assertEquals(SizeTier.MEDIUM, sizeTierOf(600f))
        assertEquals(SizeTier.MEDIUM, sizeTierOf(659.9f))
    }

    @Test
    fun `the reference unit and everything taller is expanded`() {
        assertEquals(SizeTier.EXPANDED, sizeTierOf(660f))
        assertEquals(SizeTier.EXPANDED, sizeTierOf(720f))
        assertEquals(SizeTier.EXPANDED, sizeTierOf(800f))
    }

    @Test
    fun `a 1280 x 720 unit with a thin permanent system bar is still expanded`() {
        assertEquals(SizeTier.EXPANDED, sizeTierOf(720f - 48f))
    }

    @Test
    fun `the smallest touch target stays above the 48 dp Android minimum on every tier`() {
        val touchTargetDp = 72f
        SizeTier.entries.forEach { tier -> assertTrue("$tier", touchTargetDp * tier.layoutScale >= 48f) }
    }

    @Test
    fun `body text never drops below 16 sp on screen`() {
        val bodySp = 20f
        SizeTier.entries.forEach { tier -> assertTrue("$tier", bodySp * tier.layoutScale * tier.textBoost >= 16f) }
    }

    @Test
    fun `the canvas of a small phone is its real size divided by the scale`() {
        val profile = screenProfileOf(640f, 360f)
        assertEquals(SizeTier.COMPACT, profile.tier)
        assertEquals(640f / 0.75f, profile.widthDp, 0.01f)
        assertEquals(480f, profile.heightDp, 0.01f)
    }

    @Test
    fun `the reference unit is laid out one to one`() {
        val profile = screenProfileOf(1280f, 720f)
        assertEquals(1280f, profile.widthDp, 0f)
        assertEquals(720f, profile.heightDp, 0f)
        assertEquals(ScreenProfile.Reference, profile)
    }

    @Test
    fun `a 1024 x 600 unit is drawn on a canvas about as tall as the reference`() {
        val profile = screenProfileOf(1024f, 600f)
        assertEquals(SizeTier.MEDIUM, profile.tier)
        assertEquals(706f, profile.heightDp, 1f)
    }

    @Test
    fun `only the compact tier is compact`() {
        assertTrue(screenProfileOf(914f, 411f).isCompact)
        assertFalse(screenProfileOf(1024f, 600f).isCompact)
    }
}
