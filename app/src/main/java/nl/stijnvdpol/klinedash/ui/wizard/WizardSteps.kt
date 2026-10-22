package nl.stijnvdpol.klinedash.ui.wizard

import nl.stijnvdpol.klinedash.core.transport.TransportKind
import nl.stijnvdpol.klinedash.data.ConnectionSettings
import nl.stijnvdpol.klinedash.ui.validPort

/*
 * The order of the first-run setup and what lets it move on, as plain functions: the screens only
 * draw the result and the tests read the rules without a screen.
 */

/** The screens of the setup, in order. */
internal enum class WizardStep { WELCOME, ADAPTER, CONNECT, TEST, PREFERENCES, DONE }

/** "Step 3 of 6": [number] counts from 1. */
internal data class StepPosition(val number: Int, val total: Int)

/**
 * The steps of the setup for the chosen adapter. The simulator needs nothing connected, so it skips
 * the Connect step; every real adapter type has one.
 */
internal fun wizardSteps(kind: TransportKind): List<WizardStep> =
    if (kind == TransportKind.SIMULATOR) WizardStep.entries - WizardStep.CONNECT else WizardStep.entries

internal fun stepPosition(step: WizardStep, kind: TransportKind): StepPosition {
    val steps = wizardSteps(kind)
    return StepPosition(steps.indexOf(step) + 1, steps.size)
}

/** The step after [step], or null at the end (the setup is over). */
internal fun nextStep(step: WizardStep, kind: TransportKind): WizardStep? {
    val steps = wizardSteps(kind)
    return steps.getOrNull(steps.indexOf(step) + 1)
}

/** The step before [step], or null on the first. */
internal fun previousStep(step: WizardStep, kind: TransportKind): WizardStep? {
    val steps = wizardSteps(kind)
    return steps.getOrNull(steps.indexOf(step) - 1)
}

/** True when the Connect step has what the Test step needs: a device for Bluetooth, a usable address for Wi-Fi. */
internal fun isAdapterChosen(settings: ConnectionSettings): Boolean = when (settings.kind) {
    TransportKind.SIMULATOR -> true
    TransportKind.BT_CLASSIC -> settings.btAddress != null
    TransportKind.BLE -> settings.bleAddress != null
    TransportKind.WIFI -> settings.wifiHost.isNotBlank() && validPort(settings.wifiPort.toString()) != null
}

/** Whether "Next" works on [step]: Connect needs an adapter, Test needs a connection; the others always move on. */
internal fun canContinue(step: WizardStep, settings: ConnectionSettings, check: LinkCheck): Boolean = when (step) {
    WizardStep.CONNECT -> isAdapterChosen(settings)
    WizardStep.TEST -> check is LinkCheck.Connected
    WizardStep.WELCOME, WizardStep.ADAPTER, WizardStep.PREFERENCES, WizardStep.DONE -> true
}
