package nl.stijnvdpol.klinedash.ui.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OpenSourceLibrariesTest {
    /** Libraries of the version catalog that only tests or the build use; they are not inside the APK. */
    private val notShipped = setOf(
        "kotlinx-coroutines-test", "androidx-compose-ui-tooling", "androidx-room-compiler", "junit", "org-json",
        "androidx-compose-bom", "androidx-compose-ui-test-junit4", "androidx-test-runner", "androidx-test-orchestrator",
    )

    /** alias to Maven group of every `[libraries]` entry of gradle/libs.versions.toml. */
    private val catalog: Map<String, String> = File("../gradle/libs.versions.toml").readLines()
        .dropWhile { it.trim() != "[libraries]" }.drop(1)
        .takeWhile { !it.trim().startsWith("[") }
        .mapNotNull { line ->
            Regex("""^([\w-]+)\s*=\s*\{\s*module\s*=\s*"([^:"]+):""").find(line.trim())?.let { it.groupValues[1] to it.groupValues[2] }
        }
        .toMap()

    @Test
    fun `the catalog was read`() {
        assertTrue(catalog.size >= 10)
        assertTrue("androidx-room-runtime" in catalog)
    }

    @Test
    fun `every library the app ships is listed with its licence`() {
        val shipped = catalog.filterKeys { it !in notShipped }
        assertTrue(shipped.isNotEmpty())
        shipped.forEach { (alias, group) ->
            val listed = OpenSourceLibraries.any { library -> library.groups.any { group == it || group.startsWith("$it.") } }
            assertTrue("$alias ($group) is shipped but not listed under open-source licenses", listed)
        }
    }

    @Test
    fun `the Kotlin standard library is listed too`() {
        assertTrue(OpenSourceLibraries.any { it.groups.contains("org.jetbrains.kotlin") })
    }

    @Test
    fun `every listed library says which licence it is under`() {
        assertTrue(OpenSourceLibraries.all { it.license.isNotBlank() && it.name.isNotBlank() })
        assertEquals(setOf(APACHE_2_0), OpenSourceLibraries.map { it.license }.toSet())
    }

    @Test
    fun `the Apache License text ships with the app, complete`() {
        val text = File("src/main/res/raw/license_apache_2_0.txt").readText()
        assertTrue(text.trimStart().startsWith("Apache License"))
        assertTrue(text.contains("Version 2.0, January 2004"))
        assertTrue(text.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"))
        (1..9).forEach { assertTrue("Section $it", text.contains("   $it. ")) }
        assertTrue(text.contains("END OF TERMS AND CONDITIONS"))
        assertTrue(text.contains("limitations under the License."))
    }
}
