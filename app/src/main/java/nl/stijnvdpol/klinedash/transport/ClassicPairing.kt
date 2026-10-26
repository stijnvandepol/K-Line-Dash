package nl.stijnvdpol.klinedash.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "KLineDash"

/**
 * Pairs with a Bluetooth Classic adapter that was found by [searchClassic] and returns how it ended:
 * [ClassicPairing.Paired] or [ClassicPairing.Failed]. Starts with `createBond`, follows
 * ACTION_BOND_STATE_CHANGED, and answers a plain PIN request with the PIN of this [attempt]
 * (see [pinForAttempt]) through the public `setPin`, so the user need not type it. Any other kind of
 * request, or a refused `setPin`, is left to Android's own dialog; the broadcast is never aborted.
 *
 * Needs BLUETOOTH_CONNECT on API 31+ (BLUETOOTH_ADMIN, declared up to API 30, before that). Throws
 * [SecurityException] when it is missing and [ScanBlockedException] when there is no adapter or it is off.
 */
@SuppressLint("MissingPermission")
internal suspend fun pairClassic(context: Context, device: BtDeviceInfo, attempt: Int): ClassicPairing {
    requireBtConnectPermission(context)
    val appContext = context.applicationContext
    val adapter = bluetoothAdapter(appContext)
    val block = decideScanBlock(scanEnvironment(appContext, adapter), throttleWaitMs = 0, needsLeScanner = false)
    if (block != null) throw ScanBlockedException(block)
    checkNotNull(adapter) // decideScanBlock lets a pairing through only when the adapter exists and is on.
    val remote = adapter.getRemoteDevice(device.address)
    val pairing = PairingSession(device, attempt, pinForAttempt(attempt))
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = pairing.onBroadcast(intent)
    }
    val filter = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
    }
    // RECEIVER_EXPORTED: protected system broadcasts, as for discovery (see DiscoverySession).
    ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    try {
        // A running discovery makes bonding fail on many stacks.
        adapter.cancelDiscovery()
        if (!remote.createBond()) return pairing.state.next(PairingEvent.Refused)
        withTimeoutOrNull(PAIRING_TIMEOUT_MS) { pairing.isOver.await() } ?: pairing.onTimeout()
        return pairing.state
    } finally {
        appContext.unregisterReceiver(receiver)
    }
}

/** The broadcasts of one pairing, delivered on the main thread, and the state they lead to. */
@SuppressLint("MissingPermission")
private class PairingSession(device: BtDeviceInfo, attempt: Int, private val pin: String) {
    private val address = device.address
    var state: ClassicPairing = ClassicPairing.Pairing(device, attempt)
        private set

    /** Completed when the state is no longer [ClassicPairing.Pairing]. */
    val isOver = CompletableDeferred<Unit>()

    fun onBroadcast(intent: Intent) {
        val from = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        if (from.address != address) return
        when (intent.action) {
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> advance(
                PairingEvent.BondChanged(
                    address,
                    bondStateOf(intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)),
                    bondStateOf(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)),
                ),
            )
            BluetoothDevice.ACTION_PAIRING_REQUEST -> answer(from, intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR))
        }
    }

    fun onTimeout() = advance(PairingEvent.TimedOut)

    private fun advance(event: PairingEvent) {
        state = state.next(event)
        if (state !is ClassicPairing.Pairing) isOver.complete(Unit)
    }

    private fun answer(from: BluetoothDevice, variant: Int) {
        if (answerPairingRequest(variant, pin) !is PairingAnswer.SetPin) return
        val isAccepted = try {
            from.setPin(pin.toByteArray())
        } catch (e: SecurityException) {
            Log.w(TAG, "setPin refused: permission", e)
            false
        }
        // Not accepted: Android's own PIN dialog is still on screen for the user.
        if (!isAccepted) Log.w(TAG, "setPin was not accepted for $address")
    }
}
