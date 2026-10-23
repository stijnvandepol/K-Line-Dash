package nl.stijnvdpol.klinedash

import nl.stijnvdpol.klinedash.core.diag.MANUFACTURER_SPECIFIC_DESCRIPTION
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * DIAG with the simulated car: it stores P0171 and P1349, and clearing them takes two confirmations
 * and an engine that is off. The simulator reports no VIN, so SETUP > Car stays on Automatic with
 * no make detected: P1349 only has a meaning once the make is set to Toyota / Lexus by hand.
 */
internal class DiagnosticsFlowTest : AppUiTest() {

    @Test
    fun storedCodesAreListedWithTheirDescriptions() {
        openDiagnostics()

        compose.waitFor(text("System Too Lean (Bank 1)"))
        compose.waitFor(text(MANUFACTURER_SPECIFIC_DESCRIPTION))
        compose.waitFor(text(MAKE_HINT))
        assertFalse("Another make would get Toyota's meaning", compose.exists(text("VVT System Malfunction (Bank 1)")))
    }

    @Test
    fun theHintOpensTheCarPageWhereTheMakeIsSet() {
        openDiagnostics()

        compose.tap(button("Set make"))

        compose.waitFor(text("Red line (rpm)"))
        compose.waitFor(option("Automatic"))
        compose.waitFor(text("No VIN reported by this car yet. Standard meanings only."))
    }

    @Test
    fun choosingTheMakeGivesTheMakeSpecificMeaningAtOnce() {
        openDiagnostics()

        compose.openSetupSection("Car")
        compose.tap(option("Toyota / Lexus"))
        compose.tap(switchRow("2ZZ-GE engine notes"))
        compose.openPage(Page.Diag)

        compose.waitFor(text("VVT System Malfunction (Bank 1)"))
        assertFalse(compose.exists(text(MANUFACTURER_SPECIFIC_DESCRIPTION)))
        assertFalse("The make is known now", compose.exists(text(MAKE_HINT)))
    }

    @Test
    fun clearingIsRefusedWhileTheEngineRuns() {
        openDiagnostics()

        compose.tap(button("Clear codes"))
        compose.waitFor(text("Turn the engine off first"))
        assertFalse("The engine is running, so the confirmations must not start", compose.exists(text("Clear fault codes?")))
        compose.tap(button("OK"))
        compose.waitUntilGone(text("Turn the engine off first"))

        compose.waitFor(text("P0171"))
        compose.waitFor(text("P1349"))
    }

    @Test
    fun cancellingEitherConfirmationLeavesTheCodesInTheCar() {
        openDiagnostics()
        compose.stopSimulatedEngine()
        compose.openPage(Page.Diag)
        compose.waitFor(text("P0171"))

        compose.tap(button("Clear codes"))
        compose.waitFor(text("Clear fault codes?"))
        compose.tap(button("Cancel"))
        compose.waitUntilGone(text("Clear fault codes?"))

        compose.tap(button("Clear codes"))
        compose.waitFor(text("Clear fault codes?"))
        compose.tap(button("Continue"))
        compose.waitFor(text("Clear codes now?"))
        compose.tap(button("Cancel"))
        compose.waitUntilGone(text("Clear codes now?"))

        // Reading the car again shows what it still stores, not what the screen remembers.
        compose.tap(button("Read"))
        compose.waitFor(text("Reading the car…"))
        compose.waitUntilGone(text("Reading the car…"), CAR_TIMEOUT_MS)
        compose.waitFor(text("P0171"))
        compose.waitFor(text("P1349"))
        assertFalse(compose.exists(textMatching(Regex("""Codes cleared\..*"""))))
    }

    @Test
    fun confirmingBothStepsWithTheEngineOffClearsTheCodes() {
        openDiagnostics()
        compose.stopSimulatedEngine()
        compose.openPage(Page.Diag)
        compose.waitFor(text("P0171"))

        compose.tap(button("Clear codes"))
        compose.tap(button("Continue"))
        compose.tap(button("Clear codes now"))

        compose.waitFor(textMatching(Regex("""Codes cleared\..*""")), CAR_TIMEOUT_MS)
        compose.waitFor(text("No codes"))
        compose.waitUntilGone(text("P0171"))
        assertFalse(compose.exists(text("P1349")))
    }

    private companion object {
        const val MAKE_HINT = "Set the make under Setup, Car to see what manufacturer codes mean."
    }

    /** Skips the setup, connects the simulator and opens DIAG once its first read has listed the stored codes. */
    private fun openDiagnostics() {
        compose.waitFor(text("Your car, live on one screen"))
        compose.skipSetup()
        compose.connectSimulatorFromDash()
        compose.openPage(Page.Diag)
        compose.waitFor(text("P0171"), CAR_TIMEOUT_MS)
        compose.waitFor(text("P1349"))
    }
}
