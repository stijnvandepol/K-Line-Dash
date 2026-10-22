package nl.stijnvdpol.klinedash

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Every bottom-bar page and every SETUP section opens and draws its own content, on the head unit
 * and on a phone in landscape (where SETUP shows its menu and a section one at a time). Nothing is
 * connected, so each page shows what it says to a user who has not set up an adapter yet.
 */
@RunWith(Parameterized::class)
internal class NavigationSmokeTest private constructor(
    private val display: DisplaySize,
    private val displayRule: DisplaySizeRule,
) : AppUiTest(displayRule) {

    constructor(display: DisplaySize) : this(display, DisplaySizeRule())

    @Test
    fun everyPageOpens() {
        startOn(display)

        for ((page, content) in pages) {
            compose.openPage(page)
            compose.waitFor(text(content))
        }
    }

    @Test
    fun everySetupSectionOpens() {
        startOn(display)

        for ((section, content) in sections) {
            compose.openSetupSection(section)
            compose.waitFor(text(content))
        }
    }

    /** Waits for the first run, switches the screen to [display] and leaves the setup. */
    private fun startOn(display: DisplaySize) {
        compose.waitFor(text("Your car, live on one screen"))
        displayRule.use(display)
        display.widthPx?.let { width ->
            compose.waitUntil("the activity is $width px wide", UI_TIMEOUT_MS) { compose.activity.resources.displayMetrics.widthPixels == width }
        }
        compose.waitFor(text("Your car, live on one screen"))
        compose.skipSetup()
    }

    private companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun displays(): List<DisplaySize> = listOf(DisplaySize.HeadUnit, DisplaySize.Phone)

        val pages = listOf(
            Page.Dash to "Connect your adapter to see live data",
            Page.Sensors to "Not connected yet. Connect under Setup, Connection.",
            Page.Diag to "Not connected yet. Connect to the car under Setup, Connection, then read its fault codes.",
            Page.Trips to "Not recording",
            Page.Setup to "Connection",
        )

        val sections = listOf(
            "Connection" to "Adapter type",
            "Warnings" to "Coolant too hot",
            "Dashboard" to "Pages",
            "Display" to "Day and night",
            "Startup" to "Start with the head unit",
            "GPS & Tracks" to "Satellites",
            "Help" to "Run setup again",
            "About" to "Privacy",
            "Debug" to "Command",
        )
    }
}
