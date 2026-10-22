package nl.stijnvdpol.klinedash.ui.theme

import androidx.compose.ui.graphics.Color
import nl.stijnvdpol.klinedash.data.ClusterStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Race tokens as theme-file text. [overrides] replaces a token's text (or removes it for null);
 * a value is written as a JSON string unless it is a bare number. [rootEntries] are extra entries
 * next to "name" and "colors" (like "cluster"), written the same way.
 */
internal fun themeJson(
    name: String? = "Test",
    overrides: Map<String, String?> = emptyMap(),
    withColorsObject: Boolean = true,
    rootEntries: Map<String, String> = emptyMap(),
): String {
    val tokens = linkedMapOf(
        "background" to "#000000", "surface" to "#1C1C1C", "rule" to "#2A2A2A", "ink" to "#FFFFFF",
        "inkMuted" to "#8C8C8C", "inkFaint" to "#4D4D4D", "ok" to "#2ED47A", "caution" to "#FFB000", "danger" to "#FF2D2D",
    )
    overrides.forEach { (token, value) -> if (value == null) tokens.remove(token) else tokens[token] = value }
    val colors = tokens.entries.joinToString(",") { (token, value) ->
        val json = if (value.toIntOrNull() != null) value else "\"$value\""
        "\"$token\": $json"
    }
    val nameEntry = name?.let { "\"name\": \"$it\"," }.orEmpty() + rootEntries.entries.joinToString("") { (key, value) ->
        "\"$key\": ${if (value.toIntOrNull() != null) value else "\"$value\""},"
    }
    return if (withColorsObject) "{$nameEntry \"colors\": {$colors}}" else "{$nameEntry}"
}

private fun invalidReason(text: String): String =
    (parseTheme(text) as? ThemeParseResult.Invalid)?.reason ?: error("Expected an invalid theme")

class ThemeFormatTest {
    @Test
    fun aCompleteThemeParses() {
        val result = parseTheme(themeJson(overrides = mapOf("accent" to "#7FA7C9"))) as ThemeParseResult.Valid
        assertEquals("Test", result.name)
        assertEquals(Color(0xFF7FA7C9), result.colors.accent)
        assertEquals(Color(0xFF1C1C1C), result.colors.surface)
        assertEquals(Color(0xFFFF2D2D), result.colors.danger)
    }

    @Test
    fun accentIsOptionalAndThenFollowsInk() {
        val result = parseTheme(themeJson()) as ThemeParseResult.Valid
        assertEquals(result.colors.ink, result.colors.accent)
    }

    @Test
    fun lowercaseHexAndUnknownKeysAreFine() {
        val text = themeJson(overrides = mapOf("ink" to "#ffffff")).replace("{\"name\"", "{\"author\": \"me\", \"name\"")
        assertTrue(parseTheme(text) is ThemeParseResult.Valid)
    }

    @Test
    fun aMissingColourIsNamed() {
        assertEquals("Missing color \"caution\".", invalidReason(themeJson(overrides = mapOf("caution" to null))))
    }

    @Test
    fun colourTextThatIsNotSixDigitHexIsRejectedWithTheValue() {
        for (bad in listOf("red", "#FFF", "#GGGGGG", "FFFFFF", "#FFFFFFFF", "")) {
            val reason = invalidReason(themeJson(overrides = mapOf("ink" to bad)))
            assertTrue("$bad: $reason", reason.startsWith("Color \"ink\" is \"$bad\": write it as #RRGGBB"))
        }
    }

    @Test
    fun aNumberInsteadOfTextIsRejected() {
        assertTrue(invalidReason(themeJson(overrides = mapOf("rule" to "12345"))).startsWith("Color \"rule\" is 12345"))
    }

    @Test
    fun faintLabelsAreRejectedWithTheirContrast() {
        val reason = invalidReason(themeJson(overrides = mapOf("inkMuted" to "#4D4D4D")))
        assertTrue(reason, reason.startsWith("Labels are too faint: inkMuted on background has a contrast of 2.5:1, at least 4.5:1 is needed."))
    }

    @Test
    fun contrastJustAboveTheLimitPasses() {
        // #767676 on white is the classic 4.54:1; #777777 is 4.48:1.
        val passing = themeJson(overrides = mapOf("background" to "#FFFFFF", "inkMuted" to "#767676", "ink" to "#000000"))
        assertTrue(parseTheme(passing) is ThemeParseResult.Valid)
        val failing = themeJson(overrides = mapOf("background" to "#FFFFFF", "inkMuted" to "#777777", "ink" to "#000000"))
        assertTrue(invalidReason(failing).startsWith("Labels are too faint"))
    }

    @Test
    fun brokenJsonIsRejected() {
        assertTrue(invalidReason("{\"name\": ").startsWith("Not valid JSON"))
        assertTrue(invalidReason("").startsWith("Not valid JSON"))
        assertTrue(invalidReason("[1, 2]").startsWith("Not valid JSON"))
    }

    @Test
    fun theNameIsRequiredAndShort() {
        assertTrue(invalidReason(themeJson(name = null)).startsWith("Missing \"name\""))
        assertTrue(invalidReason(themeJson(name = "   ")).startsWith("Missing \"name\""))
        assertTrue(invalidReason(themeJson(name = "x".repeat(25))).contains("longer than 24"))
        assertTrue(parseTheme(themeJson(name = "x".repeat(24))) is ThemeParseResult.Valid)
    }

    @Test
    fun theColoursObjectIsRequired() {
        assertTrue(invalidReason(themeJson(withColorsObject = false)).startsWith("Missing \"colors\""))
    }

    @Test
    fun theDialTokensAndTheClusterDefaultToTheBarLookOfTheBaseTokens() {
        val result = parseTheme(themeJson(overrides = mapOf("accent" to "#7FA7C9"))) as ThemeParseResult.Valid
        val c = result.colors
        assertEquals(c.background, c.face)
        assertEquals(c.ink, c.faceInk)
        assertEquals(c.rule, c.ring)
        assertEquals(c.accent, c.needle)
        assertEquals(ClusterLook(ClusterStyle.BAR, NumeralFace.CONDENSED), result.look)
    }

    @Test
    fun theDialTokensAndTheClusterAreRead() {
        val text = themeJson(
            overrides = mapOf("face" to "#FFD200", "faceInk" to "#000000", "ring" to "#222222", "needle" to "#E3001B"),
            rootEntries = mapOf("cluster" to "twin", "numerals" to "serif"),
        )
        val result = parseTheme(text) as ThemeParseResult.Valid
        assertEquals(Color(0xFFFFD200), result.colors.face)
        assertEquals(Color(0xFF000000), result.colors.faceInk)
        assertEquals(Color(0xFF222222), result.colors.ring)
        assertEquals(Color(0xFFE3001B), result.colors.needle)
        assertEquals(ClusterLook(ClusterStyle.TWIN, NumeralFace.SERIF), result.look)
    }

    @Test
    fun everyClusterStyleAndNumeralFaceIsAccepted() {
        ClusterStyle.entries.forEach { style ->
            val result = parseTheme(themeJson(rootEntries = mapOf("cluster" to style.id))) as ThemeParseResult.Valid
            assertEquals(style, result.look.style)
        }
        NumeralFace.entries.forEach { face ->
            val result = parseTheme(themeJson(rootEntries = mapOf("numerals" to face.id))) as ThemeParseResult.Valid
            assertEquals(face, result.look.numerals)
        }
    }

    @Test
    fun anUnknownClusterIsRejectedWithTheChoices() {
        assertEquals(
            "Unknown cluster \"round\": use bar, twin, center, arc, virtual.",
            invalidReason(themeJson(rootEntries = mapOf("cluster" to "round"))),
        )
    }

    @Test
    fun aClusterThatIsNotTextIsRejected() {
        assertTrue(invalidReason(themeJson(rootEntries = mapOf("cluster" to "3"))).startsWith("Unknown cluster 3"))
    }

    @Test
    fun anUnknownNumeralsFaceIsRejectedWithTheChoices() {
        assertEquals(
            "Unknown numerals \"gothic\": use condensed, serif, light.",
            invalidReason(themeJson(rootEntries = mapOf("numerals" to "gothic"))),
        )
    }

    @Test
    fun faintDialNumbersAreRejectedWithTheirContrast() {
        val reason = invalidReason(themeJson(overrides = mapOf("face" to "#FFD200", "faceInk" to "#FFFFFF")))
        assertTrue(reason, reason.startsWith("Dial numbers are too faint: faceInk on face has a contrast of 1.5:1, at least 4.5:1 is needed."))
    }

    @Test
    fun theDialContrastIsOnlyCheckedWhenTheFileSetsADialColour() {
        // Ink and background come from the base tokens here; a theme that never mentions a dial must keep loading.
        val text = themeJson(overrides = mapOf("ink" to "#555555", "inkMuted" to "#8C8C8C"))
        assertTrue(parseTheme(text) is ThemeParseResult.Valid)
        assertTrue(parseTheme(themeJson(overrides = mapOf("ink" to "#555555", "faceInk" to "#555555"))) is ThemeParseResult.Invalid)
    }
}
