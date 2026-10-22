package nl.stijnvdpol.klinedash.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThemeLoaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val builtIns: (String) -> String = { id -> File("src/main/assets/themes/$id.json").readText() }

    private fun loader(dir: File = folder.root) = ThemeLoader(builtIns, dir)

    @Test
    fun emptyFolderGivesJustTheBuiltInsInOrder() {
        val catalog = loader().load()
        assertEquals(listOf("race", "minimal", "retro", "day"), catalog.themes.map { it.id })
        assertTrue(catalog.themes.all { it.isBuiltIn })
        assertTrue(catalog.invalid.isEmpty())
    }

    @Test
    fun aMissingFolderIsTreatedAsEmpty() {
        assertEquals(4, loader(File(folder.root, "nope")).load().themes.size)
    }

    @Test
    fun validUserThemesFollowTheBuiltInsSortedByFileName() {
        folder.newFile("b-night.json").writeText(themeJson(name = "Night B"))
        folder.newFile("A-sun.JSON").writeText(themeJson(name = "Sun A"))
        val catalog = loader().load()
        val user = catalog.themes.filter { !it.isBuiltIn }
        assertEquals(listOf("user:A-sun.JSON", "user:b-night.json"), user.map { it.id })
        assertEquals(listOf("Sun A", "Night B"), user.map { it.name })
        assertEquals("Night B", catalog.find("user:b-night.json")?.name)
        assertNull(catalog.find("user:gone.json"))
    }

    @Test
    fun invalidFilesAreListedWithTheirReasonAndIgnored() {
        folder.newFile("good.json").writeText(themeJson())
        folder.newFile("faint.json").writeText(themeJson(overrides = mapOf("inkMuted" to "#222222")))
        folder.newFile("broken.json").writeText("{")
        folder.newFile("notes.txt").writeText("not a theme")
        val catalog = loader().load()
        assertEquals(listOf("user:good.json"), catalog.themes.filter { !it.isBuiltIn }.map { it.id })
        assertEquals(listOf("broken.json", "faint.json"), catalog.invalid.map { it.fileName })
        assertTrue(catalog.invalid[0].reason.startsWith("Not valid JSON"))
        assertTrue(catalog.invalid[1].reason.startsWith("Labels are too faint"))
    }

    @Test
    fun anOversizedFileIsRejectedWithoutBeingParsed() {
        folder.newFile("huge.json").writeText(" ".repeat(70 * 1024) + themeJson())
        assertEquals("The file is larger than 64 KB.", loader().load().invalid.single().reason)
    }

    @Test
    fun onlyTheFirstFiftyFilesAreRead() {
        repeat(52) { folder.newFile("t%02d.json".format(it)).writeText(themeJson()) }
        val catalog = loader().load()
        assertEquals(50, catalog.themes.count { !it.isBuiltIn })
        assertEquals("2 more files", catalog.invalid.single().fileName)
    }
}
