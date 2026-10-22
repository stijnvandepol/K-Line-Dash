package nl.stijnvdpol.klinedash

import androidx.compose.ui.test.junit4.ComposeTestRule
import org.junit.Test

/** SETUP > Display > Units changes every unit on the dashboard at once, and back again. */
internal class UnitsFlowTest : AppUiTest() {

    @Test
    fun imperialUnitsShowMphAndFahrenheitOnTheDashboardAndMetricBringsThemBack() {
        compose.waitFor(text("Your car, live on one screen"))
        compose.skipSetup()
        compose.connectSimulatorFromDash()
        compose.waitForSpeedAndCoolant(speedUnit = "km/h", temperatureUnit = "°C")

        compose.openSetupSection("Display")
        compose.tap(button("Imperial"))
        compose.openPage(Page.Dash)
        compose.waitForSpeedAndCoolant(speedUnit = "mph", temperatureUnit = "°F")
        compose.waitFor(text("MPH"))

        compose.openSetupSection("Display")
        compose.tap(button("Metric"))
        compose.openPage(Page.Dash)
        compose.waitForSpeedAndCoolant(speedUnit = "km/h", temperatureUnit = "°C")
        compose.waitFor(text("KM/H"))
    }

    /** Both fields announce a value in the unit, which they only do once the car has reported it. */
    private fun ComposeTestRule.waitForSpeedAndCoolant(speedUnit: String, temperatureUnit: String) {
        val speed = Regex("""Vehicle speed: \d+ ${Regex.escape(speedUnit)}""")
        val coolant = Regex("""Coolant temperature: -?\d+ ${Regex.escape(temperatureUnit)}""")
        waitUntil(CAR_TIMEOUT_MS) {
            fieldDescription("Vehicle speed")?.let(speed::matches) == true &&
                fieldDescription("Coolant temperature")?.let(coolant::matches) == true
        }
    }
}
