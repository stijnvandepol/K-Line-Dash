package nl.stijnvdpol.klinedash

import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isOn
import org.junit.Assert.assertEquals
import org.junit.Test

/** TRIPS with the simulated car: record a trip, find it in the list, export it for a PC. */
internal class TripsFlowTest : AppUiTest() {

    @Test
    fun aRecordedTripShowsLiveStatisticsAppearsInTheListAndExports() {
        compose.waitFor(text("Your car, live on one screen"))
        compose.skipSetup()
        compose.switchOffAutomaticTrips()
        compose.waitFor(text("No trips yet. Start a trip to record one."))
        compose.openPage(Page.Dash)
        compose.connectSimulatorFromDash()
        // The trip has a route only when the simulated circuit stands in for the GPS; choosing the
        // simulator switches it on.
        compose.openSetupSection("GPS & Tracks")
        compose.waitFor(switchRow("Use the simulated circuit") and isOn())

        compose.openPage(Page.Trips)
        compose.tap(button("Start trip") and isEnabled(), CAR_TIMEOUT_MS)
        compose.waitFor(text("Recording"))

        // The clock of the running trip moves with every sample: three different readings mean the statistics are live.
        val clock = Regex("""\d{2}:\d{2}""")
        val durations = linkedSetOf<String>()
        compose.waitUntil(CAR_TIMEOUT_MS) {
            durations += compose.textsMatching(clock)
            durations.size >= 3
        }

        compose.tap(button("Stop"))
        compose.waitFor(text("Not recording"))
        compose.waitUntilGone(text("Recording"))
        compose.waitFor(button("Delete"))
        assertEquals("One trip was recorded", 1, compose.onAllNodes(button("Delete")).fetchSemanticsNodes().size)

        compose.tap(textMatching(Regex("""\d{1,2} \w{3} \d{2}:\d{2}""")))
        compose.tap(button("Export for PC"), CAR_TIMEOUT_MS)
        for (extension in listOf("csv", "vbo", "gpx")) {
            compose.tap(button(extension))
            compose.waitFor(textMatching(Regex(""".+\.$extension""", RegexOption.IGNORE_CASE)), CAR_TIMEOUT_MS)
        }
        compose.waitFor(text("Get them off the head unit with the Wi-Fi export or Copy to USB on the Trips page."))
    }
}
