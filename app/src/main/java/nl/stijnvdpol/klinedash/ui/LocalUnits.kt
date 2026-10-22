package nl.stijnvdpol.klinedash.ui

import androidx.compose.runtime.staticCompositionLocalOf
import nl.stijnvdpol.klinedash.data.UnitSystem

/**
 * The unit system every value is shown and edited in. Provided once by [AppRoot] from the stored
 * setting, so a change applies to every screen at once; call sites read it instead of passing it
 * down. A static local: a change recomposes the whole tree, which is what it should do.
 */
val LocalUnitSystem = staticCompositionLocalOf { UnitSystem.METRIC }
