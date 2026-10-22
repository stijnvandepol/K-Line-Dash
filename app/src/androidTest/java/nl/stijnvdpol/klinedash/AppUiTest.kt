package nl.stijnvdpol.klinedash

import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import nl.stijnvdpol.klinedash.ui.MainActivity
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runners.model.Statement

private const val TAG = "KLineDashUiTest"

/** A rule that does nothing, for tests that need no rule around the activity rule. */
internal object NoRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = base
}

/**
 * Base of every end-to-end test: starts [MainActivity] on an app with nothing stored.
 *
 * Isolation is not done here but by the AndroidX test orchestrator (see app/build.gradle.kts and
 * docs/RELEASING.md): it runs each test in its own instrumentation process and clears the app's
 * data and permissions before it. That is needed because the app keeps its connection, simulator
 * engine and trip recorder in process-wide singletons that no repository call can reset.
 *
 * No runtime permission is granted: the app asks for none by itself (only a tap on "Allow" does),
 * and no flow here needs Bluetooth, location or notifications. Granting needs UiAutomation, which
 * can fail in the first moments of a process under the orchestrator.
 *
 * A failing test writes what was on screen to logcat (tag [TAG]), which is the only picture of it.
 *
 * @param outerRule wraps the activity rule, for example one that changes the display size.
 */
internal abstract class AppUiTest(outerRule: TestRule = NoRule) {
    protected val compose = createAndroidComposeRule<MainActivity>()

    private val screenOnFailure = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            try {
                compose.onRoot(useUnmergedTree = true).printToLog(TAG)
            } catch (dumpFailure: Exception) {
                // The test's own failure is the one to report; the dump is only a help.
                Log.w(TAG, "Could not print the screen of ${description.displayName}", dumpFailure)
            }
        }
    }

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(outerRule).around(compose).around(screenOnFailure)
}
