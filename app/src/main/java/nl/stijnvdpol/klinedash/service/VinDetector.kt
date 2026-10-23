package nl.stijnvdpol.klinedash.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import nl.stijnvdpol.klinedash.core.diag.VehicleInfoReader
import nl.stijnvdpol.klinedash.core.diag.VinReading
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.elm.ElmLink
import nl.stijnvdpol.klinedash.core.obd.ObdPoller
import nl.stijnvdpol.klinedash.data.CarSettingsRepository
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "VinDetector"

/**
 * Asks the car for its VIN (0902) after connecting, so SETUP > Car can tell the make by itself.
 *
 * The question is cheap but not free (about 0.5 s on a K-line car, and live polling waits for it),
 * so it is asked once per adapter for as long as the process lives, not on every reconnect:
 *  - a VIN is stored, and the adapter is not asked again;
 *  - NO DATA, or a VIN that is not valid, means this car has none: the stored detection of an earlier
 *    car goes, and the adapter is not asked again (old K-line cars never answer, and every connect
 *    would pay for it);
 *  - a lost answer is not an answer: the next connection asks again.
 * If the adapter is moved to another car while the app keeps running, the next diagnostics read
 * (which also reports the VIN) corrects the detection.
 *
 * The request goes through [ObdPoller.withPollingPaused], the same hand-over the diagnostics read
 * uses, so it never interleaves with polling. It waits until the supported sensors are known, so the
 * dashboard has its data first. The VIN is never logged.
 */
class VinDetector(private val settings: CarSettingsRepository) {
    private val answeredAdapters = ConcurrentHashMap.newKeySet<String>()

    /** Runs for the life of [link]; the caller cancels it with the link. */
    suspend fun run(adapterKey: String, link: ElmLink, poller: ObdPoller) {
        while (true) {
            link.state.first { it is ConnectionState.Connected }
            if (adapterKey !in answeredAdapters) {
                poller.supportedPids.first { it != null }
                askOnce(adapterKey, poller)
            }
            link.state.first { it !is ConnectionState.Connected }
        }
    }

    private suspend fun askOnce(adapterKey: String, poller: ObdPoller) {
        val reading = try {
            poller.withPollingPaused { VehicleInfoReader(it).readVin() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The link dropped under the request; the next connection asks again.
            Log.w(TAG, "Reading the VIN failed", e)
            return
        }
        when (reading) {
            is VinReading.Found -> settings.recordVin(reading.vin)
            VinReading.Absent -> settings.recordNoVin()
            VinReading.Unanswered -> return
        }
        answeredAdapters.add(adapterKey)
    }
}
