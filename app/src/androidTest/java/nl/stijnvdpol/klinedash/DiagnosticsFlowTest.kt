package nl.stijnvdpol.klinedash

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * DIAG with the simulated car: it stores P0171 and P1349, and clearing them takes two confirmations
 * and an engine that is off. P1349 only has a meaning once SETUP > Car says the car is the Toyota.
 */
internal class DiagnosticsFlowTest : AppUiTest() {

    @Test
    fun storedCodesAreListedWithTheirDescriptions() {
        openDiagnostics()

        compose.waitFor(text("System Too Lean (Bank 1)"))
        compose.waitFor(text("Manufacturer-specific code"))
        assertFalse("Another make would get Toyota's meaning", compose.exists(text("VVT System Malfunction (Bank 1)")))
    }

    @Test
    fun theToyotaProfileGivesTheMakeSpecificMeaningAtOnce() {
        openDiagnostics()

        compose.openSetupSection("Car")
        compose.tap(option("Toyota 2ZZ-GE"))
        compose.openPage(Page.Diag)

        compose.waitFor(text("VVT System Malfunction (Bank 1)"))
        assertFalse(compose.exists(text("Manufacturer-specific code")))
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
