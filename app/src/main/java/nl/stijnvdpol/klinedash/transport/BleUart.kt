package nl.stijnvdpol.klinedash.transport

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID

/**
 * The notify/write characteristic pair that carries the ELM327 serial stream over GATT.
 * [isKnownProfile] is true for the layouts of [KNOWN_PROFILES]; a pair found by property is a guess
 * that has to prove itself by answering a probe.
 */
internal data class BleUart(
    val notify: BluetoothGattCharacteristic,
    val write: BluetoothGattCharacteristic,
    val isKnownProfile: Boolean,
)

private class UartProfile(val service: UUID, val notify: UUID, val write: UUID)

/** What the pair selection needs to know about a characteristic; keeps it testable without Android. */
internal data class GattCharacteristicInfo(val uuid: UUID, val canNotify: Boolean, val canWrite: Boolean)

internal data class GattServiceInfo(val uuid: UUID, val characteristics: List<GattCharacteristicInfo>)

/** A notify/write pair that might be the serial port of the adapter, by UUID. */
internal data class UartCandidate(val service: UUID, val notify: UUID, val write: UUID, val isKnownProfile: Boolean)

/** Full 128-bit UUID of a 16-bit Bluetooth SIG short UUID. */
internal fun uuid16(short: Int): UUID = UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", short))

private val ISSC_SERVICE = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455")
private val VGATE_SERVICE = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2")
private val VGATE_CHARACTERISTIC = UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f")

/**
 * Known layouts, in order of preference (docs/research/popular-obd-adapters.md, section 2). FFF0
 * comes first: it is the layout of the target adapter, the Konnwei KW905 (FFF1 notify-only, FFF2
 * write-without-response-only), and of the OBDLink CX and Veepeak BLE.
 */
private val KNOWN_PROFILES = listOf(
    UartProfile(uuid16(0xFFF0), uuid16(0xFFF1), uuid16(0xFFF2)),
    // Vgate iCar Pro BLE, "IOS-Vlink".
    UartProfile(uuid16(0x18F0), uuid16(0x2AF0), uuid16(0x2AF1)),
    // Vgate vLinker: one characteristic that notifies and is written.
    UartProfile(VGATE_SERVICE, VGATE_CHARACTERISTIC, VGATE_CHARACTERISTIC),
    // FFE0 with separate notify and write characteristics ("Deep OBD" firmware).
    UartProfile(uuid16(0xFFE0), uuid16(0xFFE1), uuid16(0xFFE2)),
    // HM-10 style: notify and write on one characteristic.
    UartProfile(uuid16(0xFFE0), uuid16(0xFFE1), uuid16(0xFFE1)),
    // Nordic UART service.
    UartProfile(
        UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
        UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
        UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
    ),
    // Microchip ISSC transparent UART (RN4870/BM70 modules).
    UartProfile(
        ISSC_SERVICE,
        UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616"),
        UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3"),
    ),
)

/**
 * Services that are never a serial port: Generic Access/Attribute, Device Information, Battery,
 * OBDLink's internal service and the TOPDON app-locked service. The generic search skips them.
 */
private val NON_UART_SERVICES = setOf(
    uuid16(0x1800), uuid16(0x1801), uuid16(0x180A), uuid16(0x180F), uuid16(0xFEF5),
    UUID.fromString("0783b03e-8535-b5a0-7140-a304d2495cb7"),
)

/** The 16-bit alias of the vendor serial services (FFxx) and of the Vgate 18F0 service. */
private const val VENDOR_SHORT_UUID_FIRST = 0xFF00
private const val VENDOR_SHORT_UUID_LAST = 0xFFFF
private const val VGATE_SHORT_UUID = 0x18F0

/**
 * Every notify/write pair worth trying on a device that offers [services], best first: the
 * [KNOWN_PROFILES] it fully provides, then one pair per remaining service, chosen by characteristic
 * properties (a notifiable characteristic and a writable one, possibly the same), services with a
 * vendor-serial short UUID first. With [knownOnly] just the known layouts, which works on the
 * partial service list a stalled discovery leaves behind.
 */
internal fun uartCandidates(services: List<GattServiceInfo>, knownOnly: Boolean = false): List<UartCandidate> {
    val known = KNOWN_PROFILES.mapNotNull { knownCandidate(it, services) }
    if (knownOnly) return known
    val generic = services
        .filter { it.uuid !in NON_UART_SERVICES }
        .mapNotNull { genericCandidate(it) }
        .filter { candidate -> known.none { it.sameCharacteristics(candidate) } }
        .sortedByDescending { prefersVendorUuid(it.service) }
    return known + generic
}

private fun UartCandidate.sameCharacteristics(other: UartCandidate): Boolean =
    service == other.service && notify == other.notify && write == other.write

private fun knownCandidate(profile: UartProfile, services: List<GattServiceInfo>): UartCandidate? {
    val characteristics = services.firstOrNull { it.uuid == profile.service }?.characteristics ?: return null
    val notify = characteristics.firstOrNull { it.uuid == profile.notify && it.canNotify } ?: return null
    val write = characteristics.firstOrNull { it.uuid == profile.write && it.canWrite } ?: return null
    return UartCandidate(profile.service, notify.uuid, write.uuid, isKnownProfile = true)
}

private fun genericCandidate(service: GattServiceInfo): UartCandidate? {
    val notify = service.characteristics.firstOrNull { it.canNotify } ?: return null
    val write = if (notify.canWrite) notify else service.characteristics.firstOrNull { it.canWrite }
    return write?.let { UartCandidate(service.uuid, notify.uuid, it.uuid, isKnownProfile = false) }
}

private fun prefersVendorUuid(service: UUID): Boolean {
    val short = shortUuidOrNull(service) ?: return false
    return short in VENDOR_SHORT_UUID_FIRST..VENDOR_SHORT_UUID_LAST || short == VGATE_SHORT_UUID
}

/** The 16-bit value of a UUID built on the Bluetooth base UUID, or null for any other UUID. */
private fun shortUuidOrNull(uuid: UUID): Int? {
    val short = ((uuid.mostSignificantBits ushr SHORT_UUID_SHIFT) and SHORT_UUID_MASK).toInt()
    return short.takeIf { uuid16(it) == uuid }
}

private const val SHORT_UUID_SHIFT = 32
private const val SHORT_UUID_MASK = 0xFFFFL

/**
 * The serial candidates of a discovered [gatt], resolved to its characteristics, best first. Empty
 * when the device offers no notify/write pair at all (usually a Bluetooth Classic adapter). See
 * [uartCandidates] for [knownOnly].
 */
internal fun findBleUartCandidates(gatt: BluetoothGatt, knownOnly: Boolean = false): List<BleUart> {
    val services = gatt.services.map { service ->
        GattServiceInfo(
            service.uuid,
            service.characteristics.map { GattCharacteristicInfo(it.uuid, it.canNotify(), it.canWrite()) },
        )
    }
    return uartCandidates(services, knownOnly).mapNotNull { resolve(gatt, it) }
}

private fun resolve(gatt: BluetoothGatt, candidate: UartCandidate): BleUart? {
    val service = gatt.getService(candidate.service) ?: return null
    val notify = service.getCharacteristic(candidate.notify) ?: return null
    val write = service.getCharacteristic(candidate.write) ?: return null
    return BleUart(notify, write, candidate.isKnownProfile)
}

private fun BluetoothGattCharacteristic.canNotify(): Boolean =
    (properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0

private fun BluetoothGattCharacteristic.canWrite(): Boolean =
    (properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
