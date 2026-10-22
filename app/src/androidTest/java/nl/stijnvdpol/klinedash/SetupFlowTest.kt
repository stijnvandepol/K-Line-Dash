package nl.stijnvdpol.klinedash

import androidx.compose.ui.test.isSelected
import org.junit.Assert.assertTrue
import org.junit.Test

/** The first run: the setup wizard, and the way out of it without an adapter. */
internal class SetupFlowTest : AppUiTest() {

    @Test
    fun firstRunWithTheSimulatorEndsOnALiveDashboard() {
        compose.waitFor(text("Your car, live on one screen"))
        compose.tap(button("Get started"))

        compose.waitFor(text("Which adapter do you have?"))
        compose.tap(option("No adapter yet: try the simulator"))
        compose.waitFor(option("No adapter yet: try the simulator") and isSelected())
        compose.tap(button("Next"))

        compose.waitFor(text("Test the connection"))
        compose.waitFor(text("Connected"), CAR_TIMEOUT_MS)
        compose.waitFor(textMatching(Regex("""Your car reports [1-9]\d* sensors""")), CAR_TIMEOUT_MS)
        compose.tap(button("Next"))

        compose.waitFor(text("Record trips automatically"))
        compose.tap(button("Next"))

        compose.waitFor(text("You are ready to drive"))
        compose.tap(button("Open dashboard"))

        val engineSpeeds = compose.waitForLiveRpm()
        assertTrue("The dashboard showed $engineSpeeds", engineSpeeds.size >= 3)
    }

    @Test
    fun skippingTheSetupLeavesTheDashboardWithTheTwoWaysToConnect() {
        compose.waitFor(text("Your car, live on one screen"))
        compose.skipSetup()

        compose.waitFor(button(SET_UP_ADAPTER_BUTTON))
        compose.waitFor(button(SIMULATOR_BUTTON))
    }
}
