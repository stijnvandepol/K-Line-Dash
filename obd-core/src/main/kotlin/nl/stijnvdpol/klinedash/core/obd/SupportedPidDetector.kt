package nl.stijnvdpol.klinedash.core.obd

import nl.stijnvdpol.klinedash.core.elm.ElmResult

/** The ECU did not give a usable answer to the first supported-PID request (0100). */
class SupportedPidDetectionException(message: String) : Exception(message)

/**
 * Finds out which Mode 01 PIDs the ECU supports (SAE J1979): 0100 answers with a 4-byte bitmap
 * for PIDs 01-20, 0120 for 21-40, and so on; the last bit of each bitmap says whether the next
 * block exists. Only blocks announced that way are requested, because on the target car
 * (docs/research/esp32-obd2-lessons.md) every needless request costs about 0.5 s.
 *
 * Every reply line is merged, since more than one ECU may answer. The result never contains the
 * bitmap PIDs themselves (0x20, 0x40, ...): they are chain links, not live data.
 */
object SupportedPidDetector {

    private const val MODE_CURRENT_DATA = 1
    private const val BITMAP_BYTES = 4
    private const val BITS_PER_BYTE = 8
    private const val BLOCK_SIZE = 0x20
    private const val LAST_BLOCK_BASE = 0xE0
    private const val NEXT_BLOCK_BIT_MASK = 1

    /**
     * Runs the 0100 -> 0120 -> ... chain through [execute] (sends a command, returns its parsed
     * reply; timeouts are its business).
     *
     * [firstBlockReply] is an answer to 0100 that was already obtained (the connect probes the ECU
     * with it); it replaces sending that request, which on K-line costs half a second.
     *
     * A failure on 0100 throws [SupportedPidDetectionException]: without it nothing is known and
     * an empty set would look like "ECU supports nothing". A failure on a later block ends the
     * chain and keeps what was found so far.
     */
    suspend fun detect(execute: suspend (String) -> ElmResult, firstBlockReply: ElmResult? = null): Set<Int> {
        val supported = HashSet<Int>()
        var base = 0
        while (base <= LAST_BLOCK_BASE) {
            val command = ObdHex.request(MODE_CURRENT_DATA, base)
            val result = if (base == 0 && firstBlockReply != null) firstBlockReply else execute(command)
            val merged = mergeBitmaps(result, base)
            if (merged == null) {
                if (base == 0) {
                    throw SupportedPidDetectionException(
                        "No supported-PID bitmap in the reply to $command: ${result::class.simpleName} " +
                            "(raw: ${result.raw.trim()})",
                    )
                }
                break
            }
            supported += parseBitmap(base, merged)
            if (merged[BITMAP_BYTES - 1] and NEXT_BLOCK_BIT_MASK == 0) break
            base += BLOCK_SIZE
        }
        return supported
    }

    /**
     * PIDs set in a 4-byte [bytes] bitmap answering the request for block [base] (0x00, 0x20, ...).
     * The most significant bit of the first byte is PID base+1, the least significant bit of the
     * last byte is PID base+0x20 (next-block pointer), which is left out of the result.
     */
    fun parseBitmap(base: Int, bytes: IntArray): Set<Int> {
        require(bytes.size == BITMAP_BYTES) { "A PID bitmap has $BITMAP_BYTES bytes, got ${bytes.size}" }
        val pids = HashSet<Int>()
        for (byteIndex in 0 until BITMAP_BYTES) {
            for (bitIndex in 0 until BITS_PER_BYTE) {
                val mask = 1 shl (BITS_PER_BYTE - 1 - bitIndex)
                if (bytes[byteIndex] and mask == 0) continue
                val pid = base + byteIndex * BITS_PER_BYTE + bitIndex + 1
                if (pid != base + BLOCK_SIZE) pids += pid
            }
        }
        return pids
    }

    /** OR of all complete bitmaps in [result]; null if it is not Data or holds none. */
    private fun mergeBitmaps(result: ElmResult, base: Int): IntArray? {
        if (result !is ElmResult.Data) return null
        val bitmaps = ObdReply.payloads(result, MODE_CURRENT_DATA, base).filter { it.size >= BITMAP_BYTES }
        if (bitmaps.isEmpty()) return null
        val merged = IntArray(BITMAP_BYTES)
        for (bitmap in bitmaps) {
            for (i in 0 until BITMAP_BYTES) merged[i] = merged[i] or bitmap[i]
        }
        return merged
    }
}
