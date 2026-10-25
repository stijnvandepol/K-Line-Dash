package nl.stijnvdpol.klinedash.service

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * Runs one round of a long-running loop (a sample tick, one emission of a collected flow). An
 * unexpected exception is logged and the loop carries on with the next round: a bug in one feature
 * must not end the service and with it the running trip. Cancellation always passes through, or the
 * loop could no longer be stopped.
 */
internal inline fun survivingErrors(tag: String, what: String, block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(tag, "$what failed, carrying on", e)
    }
}
