package nl.stijnvdpol.klinedash.ui.theme

import androidx.compose.ui.graphics.Color
import nl.stijnvdpol.klinedash.data.ClusterStyle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val AssetsDir = File("src/main/assets/themes")

/** Caution and danger bands on a dial face: a band is a wide stroke, so it needs less than text does. */
private const val MIN_BAND_CONTRAST = 2.5

private fun builtIn(id: String): ThemeParseResult.Valid =
    when (val result = parseTheme(File(AssetsDir, "$id.json").readText())) {
        is ThemeParseResult.Valid -> result
        is ThemeParseResult.Invalid -> error("$id is invalid: ${result.reason}")
    }

class BuiltInThemesTest {
    private val all = BuiltInThemeIds.associateWith { builtIn(it) }

    @Test
    fun everyShippedThemeFileIsListedAndValid() {
        assertEquals(listOf("race", "minimal", "retro", "day", "rosso", "rspec", "autobahn", "virtual", "classic"), BuiltInThemeIds)
        assertEquals(BuiltInThemeIds.sorted(), AssetsDir.list()!!.map { it.removeSuffix(".json") }.sorted())
    }

    @Test
    fun labelsMeetTheContrastRuleOnTheirBackground() {
        all.forEach { (id, theme) ->
            val ratio = contrastRatio(theme.colors.inkMuted, theme.colors.background)
            assertTrue("$id inkMuted contrast $ratio", ratio >= MIN_LABEL_CONTRAST)
        }
    }

    @Test
    fun inkAndStatusColoursStandOutFromTheBackground() {
        all.forEach { (id, theme) ->
            val c = theme.colors
            assertTrue("$id ink", contrastRatio(c.ink, c.background) >= MIN_LABEL_CONTRAST)
            assertTrue("$id accent", contrastRatio(c.accent, c.background) >= MIN_FILLED_TEXT_CONTRAST)
            listOf("ok" to c.ok, "caution" to c.caution, "danger" to c.danger).forEach { (token, color) ->
                assertTrue("$id $token", contrastRatio(color, c.background) >= MIN_FILLED_TEXT_CONTRAST)
            }
            assertTrue("$id unlit segments must differ from the background", contrastRatio(c.surface, c.background) >= 1.1)
        }
    }

    @Test
    fun warningBannerTextIsReadableInEveryTheme() {
        all.forEach { (id, theme) ->
            val c = theme.colors
            val onCaution = readableOn(c.caution, c.background, c.ink)
            val onDanger = readableOn(c.danger, c.ink, c.background)
            assertTrue("$id caution banner", contrastRatio(c.caution, onCaution) >= MIN_FILLED_TEXT_CONTRAST)
            assertTrue("$id danger banner", contrastRatio(c.danger, onDanger) >= MIN_FILLED_TEXT_CONTRAST)
        }
    }

    @Test
    fun raceKeepsTheDesignSystemWordingOnBanners() {
        val c = builtIn("race").colors
        assertEquals(c.background, readableOn(c.caution, c.background, c.ink))
        assertEquals(c.ink, readableOn(c.danger, c.ink, c.background))
    }

    @Test
    fun builtInsUseTheValuesOfTheDesignSystem() {
        fun check(id: String, name: String, background: Long, ink: Long, accent: Long) {
            val theme = builtIn(id)
            assertEquals(name, theme.name)
            assertEquals("$id background", Color(background), theme.colors.background)
            assertEquals("$id ink", Color(ink), theme.colors.ink)
            assertEquals("$id accent", Color(accent), theme.colors.accent)
        }
        check("race", "Race", 0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF)
        check("minimal", "Minimal", 0xFF000000, 0xFFC8C8C8, 0xFF7FA7C9)
        check("retro", "Retro", 0xFF0A0603, 0xFFFFB54A, 0xFFFF8A1F)
        check("day", "Day", 0xFFF2F2F2, 0xFF0A0A0A, 0xFF0A0A0A)
    }

    @Test
    fun dialNumbersMeetTheContrastRuleOnTheirFace() {
        all.forEach { (id, theme) ->
            val ratio = contrastRatio(theme.colors.faceInk, theme.colors.face)
            assertTrue("$id faceInk on face $ratio", ratio >= MIN_LABEL_CONTRAST)
        }
    }

    @Test
    fun needlesAndZoneBandsShowUpOnTheirFace() {
        all.forEach { (id, theme) ->
            val c = theme.colors
            assertTrue("$id needle on face", contrastRatio(c.needle, c.face) >= MIN_FILLED_TEXT_CONTRAST)
            assertTrue("$id caution band on face", contrastRatio(c.caution, c.face) >= MIN_BAND_CONTRAST)
            assertTrue("$id danger band on face", contrastRatio(c.danger, c.face) >= MIN_BAND_CONTRAST)
        }
    }

    @Test
    fun theThemesBringTheirClusterAndNumerals() {
        fun check(id: String, cluster: ClusterStyle, numerals: NumeralFace) = assertEquals(id, ClusterLook(cluster, numerals), builtIn(id).look)
        listOf("race", "minimal", "retro", "day").forEach { check(it, ClusterStyle.BAR, NumeralFace.CONDENSED) }
        check("rosso", ClusterStyle.CENTER, NumeralFace.CONDENSED)
        check("rspec", ClusterStyle.ARC, NumeralFace.CONDENSED)
        check("autobahn", ClusterStyle.TWIN, NumeralFace.CONDENSED)
        check("virtual", ClusterStyle.VIRTUAL, NumeralFace.LIGHT)
        check("classic", ClusterStyle.TWIN, NumeralFace.SERIF)
    }

    @Test
    fun theClusterThemesUseTheColoursOfTheSpec() {
        fun check(id: String, name: String, face: Long, faceInk: Long, needle: Long) {
            val c = builtIn(id).colors
            assertEquals(name, builtIn(id).name)
            assertEquals("$id face", Color(face), c.face)
            assertEquals("$id faceInk", Color(faceInk), c.faceInk)
            assertEquals("$id needle", Color(needle), c.needle)
        }
        check("rosso", "Rosso", 0xFFFFD200, 0xFF000000, 0xFFE3001B)
        check("rspec", "R-Spec", 0xFF000000, 0xFFFFFFFF, 0xFFE60012)
        check("autobahn", "Autobahn", 0xFF0A0B0D, 0xFFF2F2F2, 0xFFFF5A1F)
        check("virtual", "Virtual", 0xFF000000, 0xFFFFFFFF, 0xFFD4001A)
        check("classic", "Classic", 0xFFEFE6D2, 0xFF1A1A1A, 0xFFCC1520)
    }

    @Test
    fun noThemeNamesABrand() {
        val brands = listOf("ferrari", "honda", "porsche", "audi", "volkswagen", "bmw", "toyota", "lotus", "type r", "gti")
        AssetsDir.listFiles()!!.forEach { file ->
            val text = file.readText().lowercase()
            brands.forEach { brand -> assertTrue("${file.name} mentions $brand", brand !in text) }
        }
    }

    @Test
    fun theCompiledRaceFallbackIsTheRaceFile() {
        assertEquals(RaceColors, builtIn("race").colors)
    }

    @Test
    fun everyColourSwatchWorksOnEveryBackground() {
        val backgrounds = all.values.map { it.colors.background }
        AccentSwatches.filter { it != 0xFFFFFF && it != 0x000000 }.forEach { rgb ->
            backgrounds.forEach { background ->
                val ratio = contrastRatio(Color(0xFF000000.toInt() or rgb), background)
                assertTrue("#%06X on %s: %.2f".format(rgb, background, ratio), ratio >= MIN_FILLED_TEXT_CONTRAST)
            }
        }
        assertEquals(8, AccentSwatches.size)
        assertEquals(AccentSwatches.size, AccentSwatches.toSet().size)
    }
}

class ContrastTest {
    @Test
    fun blackOnWhiteIs21() {
        assertEquals(21.0, contrastRatio(Color(0xFF000000), Color(0xFFFFFFFF)), 0.001)
    }

    @Test
    fun identicalColoursGive1AndTheOrderDoesNotMatter() {
        assertEquals(1.0, contrastRatio(Color(0xFF8C8C8C), Color(0xFF8C8C8C)), 0.0001)
        assertEquals(contrastRatio(Color(0xFF123456), Color(0xFFEEEEEE)), contrastRatio(Color(0xFFEEEEEE), Color(0xFF123456)), 0.0)
    }
}
