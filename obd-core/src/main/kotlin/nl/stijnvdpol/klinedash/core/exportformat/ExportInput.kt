package nl.stijnvdpol.klinedash.core.exportformat

import nl.stijnvdpol.klinedash.core.geo.Lap
import nl.stijnvdpol.klinedash.core.geo.LapGates
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.core.trip.TripColumn
import nl.stijnvdpol.klinedash.core.trip.TripSample

/**
 * A recorded trip, ready to be written as one of the PC formats.
 *
 * @property title shown inside the file (VBO comment, GPX name).
 * @property createdMs when the export is made, UTC epoch milliseconds; injected so output is reproducible.
 * @property samples read once, in time order; kept as a sequence so a long trip is never held in memory.
 * @property laps the laps of this trip for the current gates, as [nl.stijnvdpol.klinedash.core.geo.detectLaps]
 *   or the analyzer produced them; empty when no gate was set.
 * @property gates written as waypoints where the format has a place for them.
 */
class TripExportInput(
    val title: String,
    val createdMs: Long,
    val columns: List<TripColumn>,
    val samples: Sequence<TripSample>,
    val laps: List<Lap> = emptyList(),
    val gates: LapGates? = null,
)

private const val FALLBACK_DECIMALS = 2

/** Decimals the catalog gives this signal; unknown signals get [FALLBACK_DECIMALS]. */
internal fun TripColumn.decimals(): Int = Signals.byId(id)?.decimals ?: FALLBACK_DECIMALS

/** The samples that carry a GPS fix not seen before, so a fix repeated over several rows is written once. */
internal fun Sequence<TripSample>.withNewFix(): Sequence<TripSample> = sequence {
    var lastFixMs = Long.MIN_VALUE
    for (sample in this@withNewFix) {
        val fixMs = sample.gps?.timeMs ?: continue
        if (fixMs <= lastFixMs) continue
        lastFixMs = fixMs
        yield(sample)
    }
}
