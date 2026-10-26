package nl.stijnvdpol.klinedash.core.diag

import nl.stijnvdpol.klinedash.core.car.CarMake
import nl.stijnvdpol.klinedash.core.car.EngineNotes
import java.util.concurrent.ConcurrentHashMap

/**
 * [DtcDescriptions] backed by the data files bundled in the jar (and so in the APK), layered:
 * `/dtc/generic.tsv` (SAE J2012 generic meanings, valid for every car), then an optional
 * `/dtc/make-<family>.tsv` (the make's own meanings of its manufacturer-specific codes, plus
 * notes), then an optional `/dtc/engine-<engine>.tsv` (troubleshooting notes for one researched
 * engine). All files are programmer-maintained data, so a malformed line is a bug and throws
 * [IllegalArgumentException] naming the file and line number.
 *
 * Merge rule: description = engine ?: make ?: generic ?: [manufacturerSpecificDescription] (the
 * last only for manufacturer-specific codes, so a make with a table also gets it for a code the
 * table lacks); note = engine note ?: make note. An empty field means "not given".
 */
class BundledDtcDescriptions internal constructor(
    private val generic: Map<String, String>,
    private val make: CarMake?,
    private val makeEntries: Map<String, Entry>,
    private val engineEntries: Map<String, Entry>,
) : DtcDescriptions {

    init {
        requireDescribable(MAKE_SOURCE_LABEL, makeEntries) { it in generic }
        requireDescribable(ENGINE_SOURCE_LABEL, engineEntries) { it in generic || makeEntries[it]?.description?.isNotEmpty() == true }
    }

    private val fallback: String = manufacturerSpecificDescription(make)

    override fun describe(dtc: Dtc): DtcDescription? {
        val engineEntry = engineEntries[dtc.code]
        val makeEntry = makeEntries[dtc.code]
        val description = engineEntry?.description?.ifEmpty { null }
            ?: makeEntry?.description?.ifEmpty { null }
            ?: generic[dtc.code]
            ?: fallback.takeIf { dtc.isManufacturerSpecific }
            ?: return null
        val note = engineEntry?.note?.ifEmpty { null } ?: makeEntry?.note?.ifEmpty { null }
        return DtcDescription(description, note)
    }

    /** One row of a make or engine file; an empty field means "not given". */
    internal data class Entry(val description: String, val note: String)

    /** The raw lines of one data file and its name, which error messages quote. */
    internal class Table(val source: String, val lines: List<String>)

    companion object {
        private const val GENERIC_SOURCE = "generic.tsv"
        private const val MAKE_SOURCE_LABEL = "make table"
        private const val ENGINE_SOURCE_LABEL = "engine table"
        private const val COMMENT_PREFIX = "#"
        private const val GENERIC_COLUMNS = 2
        private const val ENTRY_COLUMNS = 3

        private val MAKE_TABLES = mapOf(
            CarMake.TOYOTA_LEXUS to "make-toyota.tsv",
            CarMake.VW_GROUP to "make-vag.tsv",
            CarMake.HONDA_ACURA to "make-honda.tsv",
            CarMake.NISSAN_INFINITI to "make-nissan.tsv",
            CarMake.MAZDA to "make-mazda.tsv",
            CarMake.FORD to "make-ford.tsv",
        )

        private val ENGINE_TABLES = mapOf(
            EngineNotes.TOYOTA_2ZZ_GE to "engine-2zz-ge.tsv",
        )

        private val genericMeanings: Map<String, String> by lazy {
            parseGeneric(readResource(GENERIC_SOURCE))
        }

        /** Key: the make plus the engine notes that belong to it (others are dropped before the lookup). */
        private val cache = ConcurrentHashMap<Pair<CarMake?, EngineNotes?>, BundledDtcDescriptions>()

        /**
         * The bundled database for a car of [make] (null: generic, only the SAE meanings), with
         * the notes of [engineNotes] on top. Engine notes of another make than [make] are ignored.
         * Each combination is parsed on first use and shared afterwards; safe from any thread.
         */
        fun load(make: CarMake?, engineNotes: EngineNotes?): BundledDtcDescriptions {
            val engine = engineNotes?.takeIf { it.make == make }
            return cache.computeIfAbsent(make to engine) { (key, engineKey) -> build(key, engineKey) }
        }

        private fun build(make: CarMake?, engineNotes: EngineNotes?): BundledDtcDescriptions {
            val makeTable = MAKE_TABLES[make]?.let { Table(it, readResource(it)) }
            val engineTable = ENGINE_TABLES[engineNotes]?.let { Table(it, readResource(it)) }
            return fromTables(genericMeanings, make, makeTable, engineTable)
        }

        internal fun fromTables(
            generic: Map<String, String>,
            make: CarMake?,
            makeTable: Table?,
            engineTable: Table?,
        ): BundledDtcDescriptions = BundledDtcDescriptions(
            generic,
            make,
            makeTable?.let { parseEntries(it.lines, it.source) } ?: emptyMap(),
            engineTable?.let { parseEntries(it.lines, it.source) } ?: emptyMap(),
        )

        /** Parses `CODE<TAB>description` lines; comment (`#`) and blank lines are skipped. */
        internal fun parseGeneric(lines: List<String>): Map<String, String> {
            val result = HashMap<String, String>()
            forEachDataLine(lines, GENERIC_SOURCE, GENERIC_COLUMNS) { lineNumber, columns ->
                val description = columns[1]
                require(description.isNotEmpty()) { "$GENERIC_SOURCE line $lineNumber: empty description" }
                putUnique(result, GENERIC_SOURCE, lineNumber, columns[0], description)
            }
            return result
        }

        /**
         * Parses `CODE<TAB>description<TAB>note` lines of a make or engine file; description and
         * note may each be empty, not both. [source] is the file name quoted in errors.
         */
        internal fun parseEntries(lines: List<String>, source: String): Map<String, Entry> {
            val result = HashMap<String, Entry>()
            forEachDataLine(lines, source, ENTRY_COLUMNS) { lineNumber, columns ->
                val entry = Entry(columns[1], columns[2])
                require(entry.description.isNotEmpty() || entry.note.isNotEmpty()) {
                    "$source line $lineNumber: description and note are both empty"
                }
                putUnique(result, source, lineNumber, columns[0], entry)
            }
            return result
        }

        internal fun readResource(fileName: String): List<String> {
            val path = "/dtc/$fileName"
            val stream = BundledDtcDescriptions::class.java.getResourceAsStream(path)
                ?: error("Bundled resource missing: $path")
            return stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
        }

        private fun requireDescribable(label: String, entries: Map<String, Entry>, hasMeaning: (String) -> Boolean) {
            for ((code, entry) in entries) {
                require(entry.description.isNotEmpty() || hasMeaning(code)) {
                    "$label: $code has a note but no description and no description in a lower layer"
                }
            }
        }

        private fun forEachDataLine(
            lines: List<String>,
            source: String,
            columnCount: Int,
            onLine: (lineNumber: Int, columns: List<String>) -> Unit,
        ) {
            lines.forEachIndexed { index, line ->
                if (line.isBlank() || line.startsWith(COMMENT_PREFIX)) return@forEachIndexed
                val lineNumber = index + 1
                val columns = line.split('\t').map { it.trim() }
                require(columns.size == columnCount) {
                    "$source line $lineNumber: expected $columnCount tab-separated columns, found ${columns.size}"
                }
                onLine(lineNumber, columns)
            }
        }

        private fun <T> putUnique(map: MutableMap<String, T>, source: String, lineNumber: Int, code: String, value: T) {
            val dtc = try {
                Dtc(code)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("$source line $lineNumber: ${e.message}", e)
            }
            require(map.put(dtc.code, value) == null) { "$source line $lineNumber: duplicate code ${dtc.code}" }
        }
    }
}
