package nl.stijnvdpol.klinedash.core.diag

/**
 * [DtcDescriptions] backed by the two data files bundled in the jar (and so in the APK):
 * `/dtc/generic.tsv` (SAE J2012 generic meanings) and `/dtc/toyota-2zz.tsv` (Toyota P1xxx meanings
 * plus notes for this car). Both files are programmer-maintained data, so a malformed line is a
 * bug and throws [IllegalArgumentException] naming the file and line number.
 *
 * Merge rule: a vehicle description overrides the generic one when it is non-empty; the vehicle
 * note is attached either way.
 */
class BundledDtcDescriptions(genericLines: List<String>, vehicleLines: List<String>) : DtcDescriptions {

    private val generic: Map<String, String> = parseGeneric(genericLines)
    private val vehicle: Map<String, VehicleEntry> = parseVehicle(vehicleLines)

    init {
        for ((code, entry) in vehicle) {
            require(entry.description.isNotEmpty() || code in generic) {
                "$VEHICLE_SOURCE: $code has a note but no description and no generic description"
            }
        }
    }

    override fun describe(dtc: Dtc): DtcDescription? {
        val vehicleEntry = vehicle[dtc.code]
        val description = vehicleEntry?.description?.ifEmpty { null } ?: generic[dtc.code] ?: return null
        return DtcDescription(description, vehicleEntry?.note?.ifEmpty { null })
    }

    /** One row of the vehicle file; an empty field means "not given". */
    internal data class VehicleEntry(val description: String, val note: String)

    companion object {
        private const val GENERIC_RESOURCE = "/dtc/generic.tsv"
        private const val VEHICLE_RESOURCE = "/dtc/toyota-2zz.tsv"
        private const val GENERIC_SOURCE = "generic.tsv"
        private const val VEHICLE_SOURCE = "toyota-2zz.tsv"
        private const val COMMENT_PREFIX = "#"
        private const val GENERIC_COLUMNS = 2
        private const val VEHICLE_COLUMNS = 3

        private val bundled: BundledDtcDescriptions by lazy {
            BundledDtcDescriptions(readResource(GENERIC_RESOURCE), readResource(VEHICLE_RESOURCE))
        }

        /** The bundled database; parsed on first use and shared afterwards. */
        fun load(): BundledDtcDescriptions = bundled

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

        /** Parses `CODE<TAB>description<TAB>note` lines; description and note may each be empty, not both. */
        internal fun parseVehicle(lines: List<String>): Map<String, VehicleEntry> {
            val result = HashMap<String, VehicleEntry>()
            forEachDataLine(lines, VEHICLE_SOURCE, VEHICLE_COLUMNS) { lineNumber, columns ->
                val entry = VehicleEntry(columns[1], columns[2])
                require(entry.description.isNotEmpty() || entry.note.isNotEmpty()) {
                    "$VEHICLE_SOURCE line $lineNumber: description and note are both empty"
                }
                putUnique(result, VEHICLE_SOURCE, lineNumber, columns[0], entry)
            }
            return result
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

        private fun readResource(path: String): List<String> {
            val stream = BundledDtcDescriptions::class.java.getResourceAsStream(path)
                ?: error("Bundled resource missing: $path")
            return stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
        }
    }
}
