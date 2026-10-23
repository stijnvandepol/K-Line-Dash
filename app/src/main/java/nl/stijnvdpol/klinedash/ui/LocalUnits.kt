package nl.stijnvdpol.klinedash.ui

import androidx.compose.runtime.staticCompositionLocalOf
import nl.stijnvdpol.klinedash.data.DEFAULT_REDLINE_RPM
import nl.stijnvdpol.klinedash.data.UnitSystem
import nl.stijnvdpol.klinedash.data.rpmScale

/**
 * The unit system every value is shown and edited in. Provided once by [AppRoot] from the stored
 * setting, so a change applies to every screen at once; call sites read it instead of passing it
 * down. A static local: a change recomposes the whole tree, which is what it should do.
 */
val LocalUnitSystem = staticCompositionLocalOf { UnitSystem.METRIC }

/**
 * The tachometer scale of the user's car (its redline sets the end of the scale and the zones).
 * Provided once by [AppRoot] from the car settings; every tachometer reads it. Screens drawn
 * without a setting (previews) show the scale of the default redline.
 */
val LocalRpmScale = staticCompositionLocalOf { rpmScale(DEFAULT_REDLINE_RPM) }
