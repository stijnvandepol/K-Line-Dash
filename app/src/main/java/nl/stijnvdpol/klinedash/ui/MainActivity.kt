package nl.stijnvdpol.klinedash.ui

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import nl.stijnvdpol.klinedash.service.ObdServiceState
import nl.stijnvdpol.klinedash.ui.theme.ActiveThemeHost
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.ScreenSizeHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dashboard on a car head unit: the screen must never dim while the app is in front.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            ActiveThemeHost {
                // Behind the first frame and during resizes the window shows this, so it must match the theme.
                val background = KLineTheme.colors.background
                SideEffect { window.setBackgroundDrawable(ColorDrawable(background.toArgb())) }
                ScreenSizeHost { AppRoot() }
            }
        }
    }

    // Location may only be added to the foreground service while the app is visible (see ObdServiceState.setAppVisible).
    override fun onStart() {
        super.onStart()
        ObdServiceState.setAppVisible(true)
    }

    override fun onStop() {
        ObdServiceState.setAppVisible(false)
        super.onStop()
    }

    // Re-hide on every focus gain: dialogs, the IME and the permission prompt bring the bars back.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
