package nl.stijnvdpol.klinedash.transport

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import nl.stijnvdpol.klinedash.core.transport.TransportException
import java.util.UUID

/** What a [GattCallback] reports to the transport that owns it. Called on a Binder thread. */
internal interface GattEvents {
    /** False for the callback of a session that was torn down or replaced: its events are ignored. */
    fun isCurrent(callback: GattCallback): Boolean

    /** The stack agreed on a new MTU, also when it answers after the transport stopped waiting. */
    fun onMtuChanged(mtu: Int)

    /** The link of [callback] went down; returns the error the pending GATT steps fail with. */
    fun onDisconnected(callback: GattCallback, status: Int): TransportException

    /** A non-empty notification of the selected notify characteristic arrived. */
    fun onNotification(callback: GattCallback, value: ByteArray)
}

/**
 * The bridge between the Binder-thread GATT callbacks of one BLE session and the coroutines of
 * [BleTransport]. Android allows one GATT operation at a time, so each step sets its result slot
 * ([mtuResult], [servicesResult], ...) before it starts and the callback completes it (see
 * BleGattSteps.kt). [responses] carries the reassembled ELM327 replies of this session.
 */
internal class GattCallback(
    val responses: Channel<String>,
    private val events: GattEvents,
) : BluetoothGattCallback() {
    val connectResult = CompletableDeferred<Unit>()
    val disconnected = CompletableDeferred<Unit>()

    @Volatile var mtuResult: CompletableDeferred<Int>? = null
    @Volatile var servicesResult: CompletableDeferred<Int>? = null
    @Volatile var descriptorResult: CompletableDeferred<Int>? = null
    @Volatile var writeResult: CompletableDeferred<Int>? = null
    @Volatile var notifyUuid: UUID? = null

    private val isCurrent: Boolean get() = events.isCurrent(this)

    fun failAll(ex: TransportException) {
        connectResult.completeExceptionally(ex)
        mtuResult?.completeExceptionally(ex)
        servicesResult?.completeExceptionally(ex)
        descriptorResult?.completeExceptionally(ex)
        writeResult?.completeExceptionally(ex)
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (!isCurrent) return
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    connectResult.complete(Unit)
                } else {
                    connectResult.completeExceptionally(TransportException("BLE connection failed (GATT status $status)"))
                }
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                disconnected.complete(Unit)
                failAll(events.onDisconnected(this, status))
            }
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        if (!isCurrent) return
        if (status == BluetoothGatt.GATT_SUCCESS) events.onMtuChanged(mtu)
        mtuResult?.complete(status)
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (!isCurrent) return
        servicesResult?.complete(status)
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (!isCurrent) return
        descriptorResult?.complete(status)
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (!isCurrent) return
        writeResult?.complete(status)
    }

    /** API 33+: value delivered as a parameter (the characteristic's cached value may be stale). */
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        deliver(characteristic.uuid, value)
    }

    /** Pre-33 path; on API 33+ the platform calls the overload above instead. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        deliver(characteristic.uuid, characteristic.value)
    }

    private fun deliver(uuid: UUID, value: ByteArray?) {
        if (value == null || value.isEmpty()) return
        if (uuid != notifyUuid) return
        events.onNotification(this, value)
    }
}
