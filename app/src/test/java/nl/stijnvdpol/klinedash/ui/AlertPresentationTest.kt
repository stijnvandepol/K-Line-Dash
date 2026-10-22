package nl.stijnvdpol.klinedash.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.stijnvdpol.klinedash.core.alert.Alert
import nl.stijnvdpol.klinedash.core.alert.AlertKind
import nl.stijnvdpol.klinedash.core.alert.AlertSeverity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlertPresentationTest {
    private fun alert(
        kind: AlertKind,
        severity: AlertSeverity = AlertSeverity.CAUTION,
        value: Double? = null,
        sinceMs: Long = 1_000,
    ) = Alert(kind, severity, value, sinceMs)

    private val coolantCaution = alert(AlertKind.COOLANT_HIGH, value = 106.0)
    private val coolantDanger = alert(AlertKind.COOLANT_HIGH, AlertSeverity.DANGER, 110.0)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the most severe alert comes first`() {
        val ordered = orderAlerts(
            listOf(
                alert(AlertKind.MIL_ON, sinceMs = 1),
                alert(AlertKind.VOLTAGE_LOW, AlertSeverity.DANGER, 10.0, sinceMs = 9),
            ),
        )
        assertEquals(listOf(AlertKind.VOLTAGE_LOW, AlertKind.MIL_ON), ordered.map { it.kind })
    }

    @Test
    fun `at equal severity the oldest comes first`() {
        val ordered = orderAlerts(
            listOf(
                alert(AlertKind.MIL_ON, sinceMs = 500),
                alert(AlertKind.NEW_DTC, sinceMs = 100),
                alert(AlertKind.CONNECTION_LOST, sinceMs = 300),
            ),
        )
        assertEquals(listOf(AlertKind.NEW_DTC, AlertKind.CONNECTION_LOST, AlertKind.MIL_ON), ordered.map { it.kind })
    }

    @Test
    fun `alerts with the same severity and start keep a fixed order`() {
        val first = alert(AlertKind.NEW_DTC, sinceMs = 5)
        val second = alert(AlertKind.MIL_ON, sinceMs = 5)
        assertEquals(orderAlerts(listOf(first, second)), orderAlerts(listOf(second, first)))
    }

    @Test
    fun `banner texts say what and how much`() {
        assertEquals("WATER 106 °C", bannerText(coolantCaution))
        assertEquals("BATTERY 11.4 V", bannerText(alert(AlertKind.VOLTAGE_LOW, value = 11.4)))
        assertEquals("BATTERY 15.6 V", bannerText(alert(AlertKind.VOLTAGE_HIGH, AlertSeverity.DANGER, 15.6)))
        assertEquals("NEW FAULT CODE: CHECK DIAG", bannerText(alert(AlertKind.NEW_DTC)))
        assertEquals("CHECK ENGINE LIGHT ON", bannerText(alert(AlertKind.MIL_ON)))
        assertEquals("ADAPTER CONNECTION LOST", bannerText(alert(AlertKind.CONNECTION_LOST)))
    }

    @Test
    fun `a reading that is missing is left out of the text`() {
        assertEquals("WATER", bannerText(alert(AlertKind.COOLANT_HIGH, value = null)))
    }

    @Test
    fun `without alerts there is no frame and no banner`() {
        val overlay = alertOverlay(emptyList(), emptyMap())
        assertNull(overlay.frame)
        assertNull(overlay.banner)
    }

    @Test
    fun `the frame has the colour of the most severe alert`() {
        assertEquals(AlertSeverity.CAUTION, alertOverlay(listOf(coolantCaution), emptyMap()).frame)
        val both = alertOverlay(listOf(coolantCaution, alert(AlertKind.MIL_ON, AlertSeverity.DANGER)), emptyMap())
        assertEquals(AlertSeverity.DANGER, both.frame)
    }

    @Test
    fun `the banner shows the leading alert and counts the others`() {
        val overlay = alertOverlay(
            listOf(alert(AlertKind.MIL_ON, sinceMs = 1), coolantDanger, alert(AlertKind.NEW_DTC, sinceMs = 2)),
            emptyMap(),
        )
        assertEquals(AlertKind.COOLANT_HIGH, overlay.banner?.alert?.kind)
        assertEquals(2, overlay.banner?.moreCount)
    }

    @Test
    fun `a single alert has no more count`() {
        assertEquals(0, alertOverlay(listOf(coolantCaution), emptyMap()).banner?.moreCount)
    }

    @Test
    fun `a dismissed banner is hidden but the frame stays`() {
        val dismissals = withDismissed(emptyMap(), coolantCaution)
        val overlay = alertOverlay(listOf(coolantCaution), dismissals)
        assertNull(overlay.banner)
        assertEquals(AlertSeverity.CAUTION, overlay.frame)
    }

    @Test
    fun `dismissing the leading alert moves the banner to the next one`() {
        val mil = alert(AlertKind.MIL_ON, sinceMs = 2_000)
        val dismissals = withDismissed(emptyMap(), coolantDanger)
        val overlay = alertOverlay(listOf(coolantDanger, mil), dismissals)
        assertEquals(AlertKind.MIL_ON, overlay.banner?.alert?.kind)
        assertEquals(0, overlay.banner?.moreCount)
        assertEquals(AlertSeverity.DANGER, overlay.frame)
    }

    @Test
    fun `an alert that cleared and came back shows its banner again`() {
        val dismissals = withDismissed(emptyMap(), coolantCaution)
        val returned = coolantCaution.copy(sinceMs = 90_000)
        assertFalse(isDismissed(returned, dismissals))
    }

    @Test
    fun `an alert that got worse shows its banner again`() {
        val dismissals = withDismissed(emptyMap(), coolantCaution)
        assertTrue(isDismissed(coolantCaution, dismissals))
        assertFalse(isDismissed(coolantDanger, dismissals))
    }

    @Test
    fun `an alert that eases from danger to caution stays hidden`() {
        val dismissals = withDismissed(emptyMap(), coolantDanger)
        assertTrue(isDismissed(coolantCaution, dismissals))
    }

    @Test
    fun `dismissing one kind leaves the others visible`() {
        val dismissals = withDismissed(emptyMap(), coolantCaution)
        assertFalse(isDismissed(alert(AlertKind.MIL_ON), dismissals))
    }

    @Test
    fun `the view model keeps a banner hidden until the alert clears and returns`() = runTest {
        val alerts = MutableStateFlow(listOf(coolantCaution))
        val vm = AlertOverlayViewModel(alerts)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.overlay.collect { } }

        assertEquals(AlertKind.COOLANT_HIGH, vm.overlay.value.banner?.alert?.kind)
        vm.dismiss(coolantCaution)
        assertNull(vm.overlay.value.banner)
        assertEquals(AlertSeverity.CAUTION, vm.overlay.value.frame)

        alerts.value = listOf(coolantCaution.copy(value = 107.0))
        assertNull("the same occurrence stays hidden when its value changes", vm.overlay.value.banner)

        alerts.value = emptyList()
        assertNull(vm.overlay.value.frame)

        alerts.value = listOf(coolantCaution.copy(sinceMs = 50_000))
        assertEquals(AlertKind.COOLANT_HIGH, vm.overlay.value.banner?.alert?.kind)
    }
}
