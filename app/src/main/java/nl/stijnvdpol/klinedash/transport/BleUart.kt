package nl.stijnvdpol.klinedash.transport

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID

/** The notify/write characteristic pair that carries the ELM327 serial stream over GATT. */
internal data class BleUart(val notify: BluetoothGattCharacteristic, val write: BluetoothGattCharacteristic)

private class UartProfile(val service: UUID, val notify: UUID, val write: UUID)

/** Full 128-bit UUID of a 16-bit Bluetooth SIG short UUID. */
internal fun uuid16(short: Int): UUID = UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", short))

/**
 * Known clone layouts, in order of preference. FFF0 comes first: it is the layout of the target
 * adapter, the Konnwei KW905 (FFF1 notify-only, FFF2 write-without-response-only).
 */
private val KNOWN_PROFILES = listOf(
    UartProfile(uuid16(0xFFF0), uuid16(0xFFF1), uuid16(0xFFF2)),
    // HM-10 style: notify and write on one characteristic.
    UartProfile(uuid16(0xFFE0), uuid16(0xFFE1), uuid16(0xFFE1)),
    // Vgate, "IOS-Vlink".
    UartProfile(uuid16(0x18F0), uuid16(0x2AF0), uuid16(0x2AF1)),
    // Nordic UART service.
    UartProfile(
        UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
        UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
        UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
    ),
)

/**
 * Picks the serial characteristics of a discovered [gatt]: a known layout from [KNOWN_PROFILES]
 * if present, otherwise the first service offering a notifiable and a writable characteristic.
 * Null when the device has no such service (usually a Bluetooth Classic adapter).
 */
internal fun findBleUart(gatt: BluetoothGatt): BleUart? {
    findKnownBleUart(gatt)?.let { return it }
    for (service in gatt.services) {
        val chars = service.characteristics
        val notify = chars.firstOrNull { it.canNotify() } ?: continue
        val write = if (notify.canWrite()) notify else chars.firstOrNull { it.canWrite() } ?: continue
        return BleUart(notify, write)
    }
    return null
}

/**
 * The first [KNOWN_PROFILES] layout the discovered services fully provide (service, a notifiable
 * and a writable characteristic), or null. Works on a partial service list, which is what a
 * stalled discovery leaves behind.
 */
internal fun findKnownBleUart(gatt: BluetoothGatt): BleUart? {
    for (profile in KNOWN_PROFILES) {
        val service = gatt.getService(profile.service) ?: continue
        val notify = service.getCharacteristic(profile.notify) ?: continue
        val write = service.getCharacteristic(profile.write) ?: continue
        if (notify.canNotify() && write.canWrite()) return BleUart(notify, write)
    }
    return null
}

private fun BluetoothGattCharacteristic.canNotify(): Boolean =
    (properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0

private fun BluetoothGattCharacteristic.canWrite(): Boolean =
    (properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
