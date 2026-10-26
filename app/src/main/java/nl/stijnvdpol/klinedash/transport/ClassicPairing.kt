package nl.stijnvdpol.klinedash.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "KLineDash"

/** Hidden methods of BluetoothDevice, reached by reflection (greylisted up to Android 8.1, the head unit this is for). */
private const val CANCEL_BOND_METHOD = "cancelBondProcess"
private const val REMOVE_BOND_METHOD = "removeBond"

/**
 * Pairs with a Bluetooth Classic adapter that was found by [searchClassic] and returns how it ended:
 * [ClassicPairing.Paired] or [ClassicPairing.Failed]. A device that is already paired counts as paired
 * at once. Otherwise it stops discovery, starts `createBond`, follows ACTION_BOND_STATE_CHANGED, and
 * answers a plain PIN request with the PIN of this [attempt] (see [pinForAttempt]) through the public
 * `setPin`, so the user need not type it. The receiver has a high priority and aborts the ordered
 * ACTION_PAIRING_REQUEST once `setPin` is accepted, so a vendor app or Android's dialog does not answer
 * as well. Any other kind of request, or a refused `setPin`, is left to Android's own dialog. After a
 * fresh bond it waits briefly for Android's own service lookup (ACTION_UUID) before returning.
 *
 * Needs BLUETOOTH_CONNECT on API 31+ (BLUETOOTH_ADMIN, declared up to API 30, before that). Does not
 * need Location. Throws [SecurityException] when the permission is missing; Bluetooth off or no adapter
 * is a [ClassicPairing.Failed] with its own reason.
 */
@SuppressLint("MissingPermission")
internal suspend fun pairClassic(context: Context, device: BtDeviceInfo, attempt: Int): ClassicPairing {
    requireBtConnectPermission(context)
    val appContext = context.applicationContext
    val adapter = bluetoothAdapter(appContext)
    val session = PairingSession(device, attempt, pinForAttempt(attempt))
    decidePairingBlock(scanEnvironment(appContext, adapter))?.let { return session.state.next(PairingEvent.Blocked(it)) }
    checkNotNull(adapter) // decidePairingBlock lets a pairing through only when the adapter exists and is on.
    val remote = adapter.getRemoteDevice(device.address)
    if (remote.bondState == BluetoothDevice.BOND_BONDED) return ClassicPairing.Paired(device)
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (session.onBroadcast(intent) && isOrderedBroadcast) abortBroadcast()
        }
    }
    val filter = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
        addAction(BluetoothDevice.ACTION_UUID)
        addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        // Ahead of a vendor app or the system dialog that would take the PIN request first.
        priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
    }
    // RECEIVER_EXPORTED: protected system broadcasts, as for discovery (see DiscoverySession).
    ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    try {
        stopDiscovery(adapter, session)
        if (!remote.createBond()) return session.state.next(PairingEvent.Refused)
        awaitOutcome(remote, session)
        if (session.state is ClassicPairing.Paired) withTimeoutOrNull(UUID_WAIT_MS) { session.isUuidSeen.await() }
        return session.state
    } finally {
        appContext.unregisterReceiver(receiver)
    }
}

/** Cancels discovery and waits until it has really stopped: a running discovery makes bonding fail on many stacks. */
@SuppressLint("MissingPermission")
private suspend fun stopDiscovery(adapter: BluetoothAdapter, session: PairingSession) {
    adapter.cancelDiscovery()
    if (!adapter.isDiscovering) return
    val isStopped = withTimeoutOrNull(DISCOVERY_STOP_WAIT_MS) { session.isDiscoveryFinished.await() }
    if (isStopped == null) Log.w(TAG, "Discovery was still running ${DISCOVERY_STOP_WAIT_MS}ms after cancelDiscovery")
}

/**
 * Waits for the pairing to end. On the timeout it looks at the bond once more (the broadcast may have
 * been missed) and otherwise cancels the bond process, so it does not complete after we reported failure.
 */
@SuppressLint("MissingPermission")
private suspend fun awaitOutcome(remote: BluetoothDevice, session: PairingSession) {
    if (withTimeoutOrNull(PAIRING_TIMEOUT_MS) { session.isOver.await() } != null) return
    val bond = remote.bondStateOrNone()
    if (bond != BondState.BONDED) callHiddenBondMethod(remote, CANCEL_BOND_METHOD)
    session.onTimeout(bond)
}

/**
 * Removes the pairing with [address] and waits until Android reports it gone. False when the call is
 * not available on this Android version or the bond is still there after [BOND_REMOVE_WAIT_MS].
 * Needs BLUETOOTH_CONNECT on API 31+; throws [SecurityException] without it.
 */
@SuppressLint("MissingPermission")
internal suspend fun removeClassicBond(context: Context, address: String): Boolean {
    requireBtConnectPermission(context)
    val adapter = bluetoothAdapter(context) ?: return false
    val remote = adapter.getRemoteDevice(address)
    if (remote.bondStateOrNone() == BondState.NONE) return true
    if (!callHiddenBondMethod(remote, REMOVE_BOND_METHOD)) return false
    val isGone = withTimeoutOrNull(BOND_REMOVE_WAIT_MS) {
        while (remote.bondStateOrNone() != BondState.NONE) delay(BOND_POLL_MS)
        true
    }
    return isGone == true
}

/** Calls the hidden no-argument `BluetoothDevice.<methodName>()`; false (and a log line) when this Android blocks or lacks it. */
private fun callHiddenBondMethod(remote: BluetoothDevice, methodName: String): Boolean = try {
    remote.javaClass.getMethod(methodName).invoke(remote) as? Boolean ?: false
} catch (e: ReflectiveOperationException) {
    Log.w(TAG, "BluetoothDevice.$methodName is not available", e)
    false
} catch (e: SecurityException) {
    Log.w(TAG, "BluetoothDevice.$methodName refused: permission", e)
    false
}

/** The broadcasts of one pairing, delivered on the main thread, and the state they lead to. */
@SuppressLint("MissingPermission")
private class PairingSession(device: BtDeviceInfo, attempt: Int, private val pin: String) {
    private val address = device.address
    var state: ClassicPairing = ClassicPairing.Pairing(device, attempt)
        private set

    /** Completed when the state is no longer [ClassicPairing.Pairing]. */
    val isOver = CompletableDeferred<Unit>()

    /** Completed by the service lookup (ACTION_UUID) of this device. */
    val isUuidSeen = CompletableDeferred<Unit>()

    /** Completed when a discovery ends. */
    val isDiscoveryFinished = CompletableDeferred<Unit>()

    /** Handles [intent]; true when the ordered broadcast was answered here and must not travel on. */
    fun onBroadcast(intent: Intent): Boolean {
        if (intent.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
            isDiscoveryFinished.complete(Unit)
            return false
        }
        val from = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return false
        if (from.address != address) return false
        when (intent.action) {
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> advance(bondEvent(intent))
            BluetoothDevice.ACTION_UUID -> isUuidSeen.complete(Unit)
            BluetoothDevice.ACTION_PAIRING_REQUEST ->
                return answer(from, intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR))
        }
        return false
    }

    fun onTimeout(bond: BondState) = advance(PairingEvent.TimedOut(bond))

    private fun bondEvent(intent: Intent) = PairingEvent.BondChanged(
        address,
        bondStateOf(intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)),
        bondStateOf(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)),
        if (intent.hasExtra(EXTRA_UNBOND_REASON)) intent.getIntExtra(EXTRA_UNBOND_REASON, 0) else null,
    )

    private fun advance(event: PairingEvent) {
        state = state.next(event)
        if (state !is ClassicPairing.Pairing) isOver.complete(Unit)
    }

    /** True when the request was a PIN request and `setPin` took the PIN. The PIN itself is never logged. */
    private fun answer(from: BluetoothDevice, variant: Int): Boolean {
        Log.i(TAG, "Pairing request from $address, variant ${pairingVariantName(variant)}")
        if (answerPairingRequest(variant, pin) !is PairingAnswer.SetPin) return false
        val isAccepted = try {
            from.setPin(pin.toByteArray())
        } catch (e: SecurityException) {
            Log.w(TAG, "setPin refused: permission", e)
            false
        }
        // Not accepted: Android's own PIN dialog is still on screen for the user.
        if (!isAccepted) Log.w(TAG, "setPin was not accepted for $address")
        return isAccepted
    }
}
