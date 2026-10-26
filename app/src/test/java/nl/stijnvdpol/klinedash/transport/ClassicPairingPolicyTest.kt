package nl.stijnvdpol.klinedash.transport

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicPairingPolicyTest {
    private val bridge = btDeviceInfo("OBDII-Bridge", "AA:BB:CC:DD:EE:01")
    private val other = btDeviceInfo("JBL Speaker", "AA:BB:CC:DD:EE:02")
    private val pairing = ClassicPairing.Pairing(bridge, attempt = 0)

    private fun bond(address: String, previous: BondState, current: BondState) = PairingEvent.BondChanged(address, previous, current)

    @Test
    fun `a pairing ends paired when the bond is made`() {
        val state = pairing.next(bond(bridge.address, BondState.BONDING, BondState.BONDED))
        assertEquals(ClassicPairing.Paired(bridge), state)
    }

    @Test
    fun `a bond that breaks off after bonding is a rejection`() {
        val state = pairing.next(bond(bridge.address, BondState.BONDING, BondState.NONE))
        assertEquals(ClassicPairing.Failed(bridge, 0, PairingFailure.REJECTED), state)
    }

    @Test
    fun `progress and other devices do not end a pairing`() {
        assertSame(pairing, pairing.next(bond(bridge.address, BondState.NONE, BondState.BONDING)))
        assertSame(pairing, pairing.next(bond(bridge.address, BondState.NONE, BondState.NONE)))
        assertSame(pairing, pairing.next(bond(other.address, BondState.BONDING, BondState.BONDED)))
    }

    @Test
    fun `refusal and timeout fail a pairing with their own reason`() {
        assertEquals(ClassicPairing.Failed(bridge, 0, PairingFailure.NOT_STARTED), pairing.next(PairingEvent.Refused))
        assertEquals(ClassicPairing.Failed(bridge, 0, PairingFailure.TIMED_OUT), pairing.next(PairingEvent.TimedOut))
    }

    @Test
    fun `events outside a pairing change nothing`() {
        val idle = ClassicPairing.Idle
        assertSame(idle, idle.next(bond(bridge.address, BondState.BONDING, BondState.BONDED)))
        assertSame(idle, idle.next(PairingEvent.TimedOut))
        val paired = ClassicPairing.Paired(bridge)
        assertSame(paired, paired.next(PairingEvent.Refused))
    }

    @Test
    fun `start begins a pairing from idle, failed and paired but not while one runs`() {
        val start = PairingEvent.Start(bridge, attempt = 1)
        assertEquals(ClassicPairing.Pairing(bridge, 1), ClassicPairing.Idle.next(start))
        assertEquals(ClassicPairing.Pairing(bridge, 1), ClassicPairing.Failed(bridge, 0, PairingFailure.REJECTED).next(start))
        assertEquals(ClassicPairing.Pairing(bridge, 1), ClassicPairing.Paired(other).next(start))
        assertSame(pairing, pairing.next(PairingEvent.Start(other, 0)))
    }

    @Test
    fun `dismiss returns to idle`() {
        assertEquals(ClassicPairing.Idle, ClassicPairing.Failed(bridge, 2, PairingFailure.TIMED_OUT).next(PairingEvent.Dismiss))
    }

    @Test
    fun `the whole flow from search result to paired adapter`() {
        var state: ClassicPairing = ClassicPairing.Idle
        state = state.next(PairingEvent.Start(bridge, 0))
        state = state.next(bond(bridge.address, BondState.NONE, BondState.BONDING))
        assertTrue(state is ClassicPairing.Pairing)
        state = state.next(bond(bridge.address, BondState.BONDING, BondState.BONDED))
        assertEquals(ClassicPairing.Paired(bridge), state)
    }

    @Test
    fun `a plain pin request is answered with the pin`() {
        assertEquals(PairingAnswer.SetPin("1234"), answerPairingRequest(BluetoothDevice.PAIRING_VARIANT_PIN, "1234"))
    }

    @Test
    fun `other pairing variants are left to the system dialog`() {
        for (variant in listOf(BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION, BluetoothDevice.ERROR, 3, 7)) {
            assertEquals(PairingAnswer.LetSystemAsk, answerPairingRequest(variant, "1234"))
        }
    }

    @Test
    fun `retries walk through the common pins and start over`() {
        assertEquals(listOf("1234", "0000", "6789", "1234"), (0..3).map(::pinForAttempt))
        assertThrows(IllegalArgumentException::class.java) { pinForAttempt(-1) }
    }

    @Test
    fun `bond states map from the android constants and unknown means none`() {
        assertEquals(BondState.NONE, bondStateOf(BluetoothDevice.BOND_NONE))
        assertEquals(BondState.BONDING, bondStateOf(BluetoothDevice.BOND_BONDING))
        assertEquals(BondState.BONDED, bondStateOf(BluetoothDevice.BOND_BONDED))
        assertEquals(BondState.NONE, bondStateOf(99))
    }

    @Test
    fun `classic and dual devices that are not paired are listed, all classes`() {
        assertTrue(isPairableClassic(BluetoothDevice.DEVICE_TYPE_CLASSIC, BondState.NONE))
        assertTrue(isPairableClassic(BluetoothDevice.DEVICE_TYPE_DUAL, BondState.NONE))
        assertTrue(isPairableClassic(BluetoothDevice.DEVICE_TYPE_UNKNOWN, BondState.BONDING))
        assertFalse(isPairableClassic(BluetoothDevice.DEVICE_TYPE_LE, BondState.NONE))
        assertFalse(isPairableClassic(BluetoothDevice.DEVICE_TYPE_CLASSIC, BondState.BONDED))
    }

    @Test
    fun `search results list likely obd adapters first, then named ones by name`() {
        val unnamed = btDeviceInfo(null, "00:00:00:00:00:09")
        val tv = btDeviceInfo("Samsung TV", "00:00:00:00:00:08")
        val elm = btDeviceInfo("OBDII", "00:00:00:00:00:07")
        val list = listOf(unnamed, other, tv, bridge, elm).fold(emptyList<BtDeviceInfo>(), ::mergeClassicResult)
        assertEquals(listOf(elm, bridge, other, tv, unnamed), list)
    }

    @Test
    fun `a device seen twice is listed once and a later name replaces the unnamed entry`() {
        val unnamedBridge = btDeviceInfo(null, bridge.address)
        val list = mergeClassicResult(mergeClassicResult(emptyList(), unnamedBridge), bridge)
        assertEquals(listOf(bridge), list)
    }

    @Test
    fun `a sighting without a name does not erase a known name`() {
        val named = mergeClassicResult(emptyList(), bridge)
        assertSame(named, mergeClassicResult(named, btDeviceInfo("", bridge.address)))
    }

    @Test
    fun `the notice after a search`() {
        assertEquals(BleScanNotice.DiscoveryRefused, classicSearchNotice(isDiscoveryStarted = false, hasDevices = false))
        assertEquals(BleScanNotice.NothingFoundClassic, classicSearchNotice(isDiscoveryStarted = true, hasDevices = false))
        assertNull(classicSearchNotice(isDiscoveryStarted = true, hasDevices = true))
    }

    @Test
    fun `the checklist names location only where location matters and always the bridge`() {
        assertTrue(ClassicSearchCheck.LOCATION_ON in classicSearchChecks(27))
        assertFalse(ClassicSearchCheck.LOCATION_ON in classicSearchChecks(34))
        assertTrue(ClassicSearchCheck.BRIDGE_FREE in classicSearchChecks(34))
    }

    @Test
    fun `classic discovery needs no le scanner but still needs bluetooth on and location where required`() {
        val env = ScanEnvironment(
            sdkInt = 27, androidRelease = "8.1.0", hasBleFeature = false, hasAdapter = true, isBluetoothOn = true,
            hasLeScanner = false, isLocationOn = true, hasLocationPermission = true, hasNearbyPermission = null,
        )
        assertNull(decideScanBlock(env, throttleWaitMs = 0, needsLeScanner = false))
        assertEquals(ScanBlock.NoLeScanner, decideScanBlock(env, throttleWaitMs = 0))
        assertEquals(ScanBlock.LocationOff, decideScanBlock(env.copy(isLocationOn = false), 0, needsLeScanner = false))
        assertEquals(ScanBlock.BluetoothOff, decideScanBlock(env.copy(isBluetoothOn = false), 0, needsLeScanner = false))
    }
}
