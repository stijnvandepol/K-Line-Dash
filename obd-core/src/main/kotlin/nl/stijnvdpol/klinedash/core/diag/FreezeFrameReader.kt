package nl.stijnvdpol.klinedash.core.diag

import nl.stijnvdpol.klinedash.core.elm.ElmLink
import nl.stijnvdpol.klinedash.core.elm.ElmResult
import nl.stijnvdpol.klinedash.core.obd.ObdReply
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.obd.Signal
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.core.obd.SupportedPidDetector

/**
 * Reads the freeze frame (service 02, frame 0) over [link]. The caller must hold the bus (inside
 * ObdPoller.withPollingPaused). Request format per J1979 is service, PID, frame number; a reply is
 * "42 PID FRAME data...", and the frame number is skipped before the Mode 01 formulas of the
 * [catalog] are applied.
 *
 * Returns null when the ECU has no freeze frame: no answer to the trigger-code request, or the
 * trigger code is 0000. Values that cannot be read are left out; link-loss exceptions from
 * [ElmLink.execute] propagate.
 */
class FreezeFrameReader(
    private val link: ElmLink,
    private val catalog: List<Signal> = Signals.all,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun read(): FreezeFrame? {
        val trigger = readTriggerCode() ?: return null
        val pids = readSupportedPids() ?: FALLBACK_PIDS
        val signalsByPid = catalog
            .filter { it.pid != null && it.pid != PID_STATUS && it.decodePayload != null && it.pid in pids }
            .groupBy { it.pid!! }
        val values = signalsByPid.flatMap { (pid, signals) -> readValues(pid, signals) }
        return FreezeFrame(trigger, values)
    }

    private suspend fun readTriggerCode(): Dtc? {
        val data = link.executeRead(request(PID_TRIGGER_DTC)) as? ElmResult.Data ?: return null
        val bytes = frameData(ObdReply.payloads(data, MODE_FREEZE_FRAME, PID_TRIGGER_DTC)) ?: return null
        if (bytes.size < DTC_BYTES) return null
        return DtcParser.decode(bytes[0], bytes[1])
    }

    /** PIDs the ECU stores for frame 0; null when the bitmap request fails. */
    private suspend fun readSupportedPids(): Set<Int>? {
        val data = link.executeRead(request(PID_SUPPORTED)) as? ElmResult.Data ?: return null
        val bytes = frameData(ObdReply.payloads(data, MODE_FREEZE_FRAME, PID_SUPPORTED)) ?: return null
        if (bytes.size < BITMAP_BYTES) return null
        return SupportedPidDetector.parseBitmap(0, bytes.copyOf(BITMAP_BYTES))
    }

    private suspend fun readValues(pid: Int, signals: List<Signal>): List<Reading> {
        val data = link.executeRead(request(pid)) as? ElmResult.Data ?: return emptyList()
        val bytes = frameData(ObdReply.payloads(data, MODE_FREEZE_FRAME, pid)) ?: return emptyList()
        val now = clock()
        return signals.mapNotNull { signal ->
            signal.decodePayload?.invoke(bytes)?.let { Reading(signal.id, it, now) }
        }
    }

    /** The data of the first reply that is for frame 0, without the frame number byte. */
    private fun frameData(payloads: List<IntArray>): IntArray? =
        payloads.firstOrNull { it.isNotEmpty() && it[0] == FRAME_NUMBER }?.let { it.copyOfRange(1, it.size) }

    private fun request(pid: Int): String = "%02X%02X%02X".format(MODE_FREEZE_FRAME, pid, FRAME_NUMBER)

    private companion object {
        const val MODE_FREEZE_FRAME = 2
        const val FRAME_NUMBER = 0
        const val PID_SUPPORTED = 0x00

        /** Mode 01 PID 01 is not data in the catalog sense, and Mode 02 PID 01 means something else. */
        const val PID_STATUS = 0x01
        const val PID_TRIGGER_DTC = 0x02
        const val DTC_BYTES = 2
        const val BITMAP_BYTES = 4

        /** Asked for when the ECU does not answer the bitmap request: engine state around the fault. */
        val FALLBACK_PIDS: Set<Int> = setOf(0x0C, 0x0D, 0x05, 0x04, 0x06, 0x07, 0x0E, 0x0F, 0x10, 0x11)
    }
}
