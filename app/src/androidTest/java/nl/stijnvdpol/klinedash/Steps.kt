@file:OptIn(ExperimentalTestApi::class)

package nl.stijnvdpol.klinedash

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo

/*
 * What the flows share: finding things the way a user does (by the words on the screen), the
 * timeouts that fit the simulator, and the few multi-step moves (skip the setup, connect the
 * simulator, open a page). Buttons draw their label in capitals, so every text match ignores case.
 */

/**
 * The simulator needs about 0.5 s per request, 2.5 s for the bus start and the first connection also
 * searches for the protocol and asks which sensors exist. Everything that waits for the car uses this.
 */
internal const val CAR_TIMEOUT_MS = 90_000L

/** Something that needs the database or one recomposition, not the car. */
internal const val UI_TIMEOUT_MS = 30_000L

internal const val SIMULATOR_BUTTON = "Try the simulator"
internal const val SET_UP_ADAPTER_BUTTON = "Set up adapter"

internal enum class Page(val label: String) {
    Dash("Dash"), Sensors("Sensors"), Diag("Diag"), Trips("Trips"), Setup("Setup"),
}

/** Text that is exactly [text], whatever its case. */
internal fun text(text: String): SemanticsMatcher = hasText(text, ignoreCase = true)

/** Text that matches [pattern] as a whole. */
internal fun textMatching(pattern: Regex): SemanticsMatcher =
    SemanticsMatcher("text matches $pattern") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { pattern.matches(it.text) } == true
    }

internal fun button(label: String): SemanticsMatcher = text(label) and hasClickAction()

internal fun option(label: String): SemanticsMatcher = text(label) and isSelectable()

internal fun switchRow(label: String): SemanticsMatcher = text(label) and isToggleable()

internal fun tab(page: Page): SemanticsMatcher =
    text(page.label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

/** A dashboard field announces itself as "Engine speed: 830 rpm" or "Vehicle speed: 0 km/h". */
internal fun field(signalName: String): SemanticsMatcher = hasContentDescription("$signalName:", substring = true)

internal fun ComposeTestRule.exists(matcher: SemanticsMatcher): Boolean =
    onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

internal fun ComposeTestRule.waitFor(matcher: SemanticsMatcher, timeoutMs: Long = UI_TIMEOUT_MS) =
    waitUntilAtLeastOneExists(matcher, timeoutMs)

internal fun ComposeTestRule.waitUntilGone(matcher: SemanticsMatcher, timeoutMs: Long = UI_TIMEOUT_MS) =
    waitUntilDoesNotExist(matcher, timeoutMs)

/** Taps the first node that matches, once it is on screen. */
internal fun ComposeTestRule.tap(matcher: SemanticsMatcher, timeoutMs: Long = UI_TIMEOUT_MS) {
    waitFor(matcher, timeoutMs)
    onAllNodes(matcher).onFirst().performClick()
}

/** The description of the dashboard field for [signalName], for example "Engine speed: 830 rpm"; null while there is none. */
internal fun ComposeTestRule.fieldDescription(signalName: String): String? =
    onAllNodes(field(signalName)).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.ContentDescription)
        ?.firstOrNull { it.startsWith("$signalName:") }

/** Engine speed shown on the dashboard, null while the car has not reported one. */
internal fun ComposeTestRule.rpmShown(): Int? =
    fieldDescription("Engine speed")?.let { Regex("""Engine speed: (\d+) rpm""").matchEntire(it) }?.groupValues?.get(1)?.toInt()

/** Waits until the dashboard has watched [count] different engine speeds, which only a live car produces. */
internal fun ComposeTestRule.waitForLiveRpm(count: Int = 3): Set<Int> {
    val seen = linkedSetOf<Int>()
    waitUntil(CAR_TIMEOUT_MS) {
        rpmShown()?.let(seen::add)
        seen.size >= count
    }
    return seen
}

internal fun ComposeTestRule.openPage(page: Page) {
    tap(tab(page))
}

/** Leaves the first-run setup at once; the dashboard is then the page with the invitation to connect. */
internal fun ComposeTestRule.skipSetup() {
    tap(button("Skip setup"))
    waitFor(text("Connect your adapter to see live data"))
}

/** From the dashboard's empty state to a connected simulator that reports engine speed. */
internal fun ComposeTestRule.connectSimulatorFromDash() {
    tap(button(SIMULATOR_BUTTON))
    waitUntil(CAR_TIMEOUT_MS) { rpmShown() != null }
}

/**
 * SETUP always opens at its menu, so this works from any page and from any section. The menu scrolls
 * when it is taller than the screen, so the row is brought into view first.
 */
internal fun ComposeTestRule.openSetupSection(label: String) {
    openPage(Page.Setup)
    waitFor(option(label))
    onAllNodes(option(label)).onFirst().performScrollTo().performClick()
}

/**
 * Switches the simulated engine off (SETUP > Connection) and waits until the dashboard has seen the
 * car report 0 rpm, which is what lets codes be cleared.
 */
internal fun ComposeTestRule.stopSimulatedEngine() {
    openSetupSection("Connection")
    tap(switchRow("Simulator engine"))
    openPage(Page.Dash)
    waitUntil(CAR_TIMEOUT_MS) { rpmShown() == 0 }
}

/**
 * Turns off recording by itself on TRIPS, so the trip a test records is the one it starts with "Start trip"
 * (with the simulated engine running, an automatic one would begin at once).
 */
internal fun ComposeTestRule.switchOffAutomaticTrips() {
    openPage(Page.Trips)
    tap(switchRow("When the engine runs"))
    waitFor(switchRow("When the engine runs") and isOff())
}

/** Every text on screen that matches [pattern] as a whole. */
internal fun ComposeTestRule.textsMatching(pattern: Regex): List<String> =
    onAllNodes(textMatching(pattern)).fetchSemanticsNodes()
        .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        .filter(pattern::matches)
