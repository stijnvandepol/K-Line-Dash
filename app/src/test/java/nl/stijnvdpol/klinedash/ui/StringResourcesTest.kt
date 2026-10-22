package nl.stijnvdpol.klinedash.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class StringResourcesTest {
    private val strings = File("src/main/res/values/strings.xml").readText()

    /**
     * `%d` is formatted in the system locale, which on an Arabic or Persian head unit writes the digits
     * as ٠١٢; the display always shows ASCII digits (docs/design-system.md, "Numbers never move"). The
     * screens pass numbers as text into `%s`, formatted by service/ValueFormat.kt or plain digits.
     */
    @Test
    fun `no string resource formats a number in the system locale`() {
        val localized = Regex("""<string name="(\w+)"[^>]*>[^<]*%(\d+\$)?[dfe][^<]*</string>""")
            .findAll(strings)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(emptyList<String>(), localized)
    }
}
