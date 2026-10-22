package nl.stijnvdpol.klinedash.ui.theme

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

private const val TAG = "Themes"
private const val JSON_EXTENSION = ".json"
private const val USER_ID_PREFIX = "user:"
private const val MAX_USER_FILES = 50
private const val MAX_FILE_BYTES = 64 * 1024L
private const val BYTES_PER_KB = 1024

/** Id of the theme used when nothing is chosen or the chosen one no longer exists. */
const val DEFAULT_THEME_ID = "race"

/** Built-in themes in list order; each is assets/themes/<id>.json. */
internal val BuiltInThemeIds = listOf(DEFAULT_THEME_ID, "minimal", "retro", "day", "rosso", "rspec", "autobahn", "virtual", "classic")

class InvalidThemeFile(val fileName: String, val reason: String)

/**
 * Built-in themes first, then the user's files by name; [invalid] are the user's files that were
 * ignored. [userFolderPath] is where the user puts theme files, to show on screen.
 */
class ThemeCatalog(val themes: List<ThemeDefinition>, val invalid: List<InvalidThemeFile>, val userFolderPath: String) {
    fun find(id: String): ThemeDefinition? = themes.firstOrNull { it.id == id }
}

/**
 * Reads the built-in themes through [readBuiltIn] (an asset in the app) and every `*.json` in the
 * folder [userDir] gives. Blocking file I/O, finding the folder included: call it off the main thread.
 */
class ThemeLoader(private val readBuiltIn: (id: String) -> String, private val userDir: () -> File) {
    fun load(): ThemeCatalog {
        val builtIn = BuiltInThemeIds.mapNotNull { id -> loadBuiltIn(id) }
        val invalid = ArrayList<InvalidThemeFile>()
        val dir = userDir()
        val user = loadUserThemes(dir, invalid)
        return ThemeCatalog(builtIn + user, invalid, dir.path)
    }

    private fun loadBuiltIn(id: String): ThemeDefinition? {
        val result = try {
            parseTheme(readBuiltIn(id))
        } catch (e: IOException) {
            Log.e(TAG, "Could not read built-in theme $id", e)
            return null
        }
        return when (result) {
            is ThemeParseResult.Valid -> ThemeDefinition(id, result.name, result.colors, isBuiltIn = true, look = result.look)
            // A shipped theme that does not validate is a bug; the unit tests guard against it.
            is ThemeParseResult.Invalid -> null.also { Log.e(TAG, "Built-in theme $id is invalid: ${result.reason}") }
        }
    }

    private fun loadUserThemes(userDir: File, invalid: MutableList<InvalidThemeFile>): List<ThemeDefinition> {
        val files = userDir.listFiles { f -> f.isFile && f.name.endsWith(JSON_EXTENSION, ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        if (files.size > MAX_USER_FILES) {
            invalid += InvalidThemeFile("${files.size - MAX_USER_FILES} more files", "Only the first $MAX_USER_FILES theme files are read.")
        }
        return files.take(MAX_USER_FILES).mapNotNull { file ->
            when (val result = parseUserFile(file)) {
                is ThemeParseResult.Valid -> ThemeDefinition(USER_ID_PREFIX + file.name, result.name, result.colors, isBuiltIn = false, look = result.look)
                is ThemeParseResult.Invalid -> null.also { invalid += InvalidThemeFile(file.name, result.reason) }
            }
        }
    }

    private fun parseUserFile(file: File): ThemeParseResult {
        if (file.length() > MAX_FILE_BYTES) return ThemeParseResult.Invalid("The file is larger than ${MAX_FILE_BYTES / BYTES_PER_KB} KB.")
        return try {
            parseTheme(file.readText())
        } catch (e: IOException) {
            Log.w(TAG, "Could not read theme file ${file.name}", e)
            ThemeParseResult.Invalid("The file could not be read. Copy it to the folder again.")
        }
    }
}

/** Holds the loaded [ThemeCatalog] (null until the first load finishes) and reloads it on request. */
class ThemeCatalogSource(private val loader: ThemeLoader, private val scope: CoroutineScope) {
    private val state = MutableStateFlow<ThemeCatalog?>(null)
    private var loadJob: Job? = null

    val catalog: StateFlow<ThemeCatalog?> = state.asStateFlow()

    /** Loads again on the IO dispatcher; a load that is still running is replaced. */
    fun reload() {
        loadJob?.cancel()
        loadJob = scope.launch(Dispatchers.IO) {
            val loaded = loader.load()
            ensureActive()
            state.value = loaded
        }
    }
}
