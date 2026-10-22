package nl.stijnvdpol.klinedash

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** A screen to run a test on: [size] and [density] are the arguments of `wm size` and `wm density`; null is the device's own. */
internal class DisplaySize(private val name: String, val size: String?, val density: Int?) {
    /** The width of the screen in pixels, for a test to see that the change has reached the app. */
    val widthPx: Int? = size?.substringBefore('x')?.toInt()

    override fun toString(): String = name

    companion object {
        /** The reference head unit, which is what the emulator is. */
        val HeadUnit = DisplaySize("headunit", size = null, density = null)

        /** A phone held in landscape. */
        val Phone = DisplaySize("phone", size = "2400x1080", density = 420)
    }
}

/**
 * Changes the display of the device for one test and puts the device's own back afterwards, also when
 * the test fails. Put it around the activity rule so the reset comes after the activity is gone.
 *
 * [use] is called from the test once the app is on screen, not before it starts: the shell commands go
 * through UiAutomation, and a UiAutomation connection made in the first moments of a test process can
 * collide with the one the test orchestrator still holds from clearing the app data. The activity
 * is recreated for the new size, as it is when a user plugs a device into a dock.
 */
internal class DisplaySizeRule : ExternalResource() {
    private var changed: DisplaySize? = null

    fun use(display: DisplaySize) {
        changed = display
        display.size?.let { shell("wm size $it") }
        display.density?.let { shell("wm density $it") }
    }

    override fun after() {
        val display = changed ?: return
        if (display.size != null) shell("wm size reset")
        if (display.density != null) shell("wm density reset")
    }

    /** Runs [command] as the shell user and waits until it has finished, which is when its output ends. */
    private fun shell(command: String): String {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }
    }
}
