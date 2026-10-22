package nl.stijnvdpol.klinedash.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Screens shorter than this (a phone held in landscape) are [SizeTier.COMPACT]. */
const val COMPACT_MAX_HEIGHT_DP = 480f

/**
 * Screens shorter than this are [SizeTier.MEDIUM] (a 1024 x 600 unit). The edge sits below 720 so
 * that a 1280 x 720 head unit with a thin permanent system bar still counts as [SizeTier.EXPANDED].
 */
const val MEDIUM_MAX_HEIGHT_DP = 660f

/**
 * Which of the three designs of the instrument a screen gets, by its real height in dp. The whole
 * UI is laid out on a canvas as tall as the reference head unit (720 dp); a smaller screen draws
 * that canvas at [layoutScale], so every token (touch targets, bars, padding, type, column widths)
 * shrinks by the same factor and the proportions of the design stay. [textBoost] then enlarges
 * the type alone, because text cannot be scaled down as far as the controls without becoming hard
 * to read at arm's length. See "Screen sizes" in docs/design-system.md.
 */
enum class SizeTier(val layoutScale: Float, val textBoost: Float) {
    /** A phone in landscape, 360 to 479 dp tall: 54 dp touch targets, 17 sp body text. */
    COMPACT(layoutScale = 0.75f, textBoost = 1.12f),

    /** A small head unit, 480 to 659 dp tall: 61 dp touch targets, 17 sp body text. */
    MEDIUM(layoutScale = 0.85f, textBoost = 1f),

    /** The reference head unit and everything bigger: the design as specified, 72 dp touch targets. */
    EXPANDED(layoutScale = 1f, textBoost = 1f),
}

/** The tier of a screen [heightDp] real dp tall. */
fun sizeTierOf(heightDp: Float): SizeTier = when {
    heightDp < COMPACT_MAX_HEIGHT_DP -> SizeTier.COMPACT
    heightDp < MEDIUM_MAX_HEIGHT_DP -> SizeTier.MEDIUM
    else -> SizeTier.EXPANDED
}

/**
 * The size the UI is laid out in: [widthDp] x [heightDp] are canvas dp, i.e. the real size divided
 * by the tier's scale, so every layout rule below is written in the dp the composables see.
 */
@Immutable
data class ScreenProfile(val tier: SizeTier, val widthDp: Float, val heightDp: Float) {
    val isCompact: Boolean get() = tier == SizeTier.COMPACT

    companion object {
        /** The reference head unit; what previews and tests without a screen see. */
        val Reference = ScreenProfile(SizeTier.EXPANDED, 1_280f, 720f)
    }
}

/** The profile of a screen of [realWidthDp] x [realHeightDp] (what the system reports, before any scaling). */
fun screenProfileOf(realWidthDp: Float, realHeightDp: Float): ScreenProfile {
    val tier = sizeTierOf(realHeightDp)
    return ScreenProfile(tier, realWidthDp / tier.layoutScale, realHeightDp / tier.layoutScale)
}

val LocalScreenProfile = staticCompositionLocalOf { ScreenProfile.Reference }

/**
 * True while the on-screen keyboard is open on a compact screen, where it covers most of the
 * content. Screens then drop what is not needed to type (the bottom bar, the status above a field)
 * so the field being edited stays in view.
 */
val LocalIsKeyboardCrowding = staticCompositionLocalOf { false }

/** Whether the keyboard is showing, from the window insets (the activity resizes for it, so Compose's own ime inset stays 0). */
@Composable
private fun rememberIsKeyboardOpen(): Boolean {
    val view = LocalView.current
    var isOpen by remember { mutableStateOf(false) }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            isOpen = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return isOpen
}

/**
 * Picks the tier from the real screen size and draws [content] on the scaled canvas: provides the
 * [ScreenProfile] and a [Density] scaled by the tier, and keeps clear of a display cutout. The
 * size comes from the configuration, not from the layout, so the keyboard or a dialog cannot flip
 * the tier while the user is typing. The user's font scale is kept on top of the tier's.
 */
@Composable
fun ScreenSizeHost(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val profile = remember(window, density.density) {
        screenProfileOf(window.width / density.density, window.height / density.density)
    }
    val canvasDensity = remember(density, profile.tier) {
        Density(density.density * profile.tier.layoutScale, density.fontScale * profile.tier.textBoost)
    }
    val isKeyboardCrowding = rememberIsKeyboardOpen() && profile.isCompact
    CompositionLocalProvider(
        LocalScreenProfile provides profile,
        LocalDensity provides canvasDensity,
        LocalIsKeyboardCrowding provides isKeyboardCrowding,
    ) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout), content = { content() })
    }
}
