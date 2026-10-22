package nl.stijnvdpol.klinedash.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.ui.theme.ScreenProfile
import nl.stijnvdpol.klinedash.ui.theme.SizeTier

/*
 * The layout decisions that depend on the screen, as pure functions of the canvas width (see
 * ScreenProfile) so each can be tested and the screens only ask. Thresholds are canvas dp: a
 * 640 dp phone is an 853 dp canvas, a 914 dp phone a 1219 dp one, the reference unit 1280.
 */

/**
 * The one width that divides a phone from the rest: below it SETUP is a menu and one section at a time,
 * the link status drops the protocol name, the sensor table is one column, the two-column SETUP pages
 * and the Trips list give the narrower side more room. The 853 dp canvas of a 640 dp phone is below it,
 * the 1 219 dp canvas of a 914 dp phone and the 1 204 dp canvas of a 1024 x 600 unit are above.
 */
internal const val NARROW_BELOW_DP = 1_000f

/** A small field (label, unit, value) needs about this much width; narrower and the field row wraps into two rows. */
private const val MIN_SMALL_FIELD_WIDTH_DP = 130f
private const val WRAPPED_FIELD_COLUMNS = 3

/**
 * A section narrower than this cannot hold the warning's name, the sound switch and two steppers on
 * one line: the steppers move to a line of their own. 960 dp is the width at which the name still gets about 280 dp.
 */
private const val WARNINGS_STACKED_BELOW_DP = 960f

/** A grid of name and value cells keeps two columns while its panel is at least this wide; narrower, each cell gets a row so no name is cut off. */
private const val TWO_CELL_GRID_MIN_WIDTH_DP = 520f

/** The statistics of a trip are three fields to a row from this width (a long label such as FULL THROTTLE % needs about 190 dp); narrower, two. */
private const val THREE_STAT_COLUMNS_MIN_WIDTH_DP = 570f

/** The lap table fits all seven columns from this width; narrower, the max rpm and the full-throttle share are left out. */
private const val LAP_TABLE_ALL_COLUMNS_MIN_WIDTH_DP = 680f

/** From this width a page of two halves (wizard, About) shows them side by side; below it they stack. */
internal const val TWO_PANE_MIN_WIDTH_DP = 840f

/**
 * A screen with a canvas lower than this (the phones: 480 and 548 dp) cannot show a running lap
 * session and its buttons at once. The Stop and sector buttons then stay at the bottom of the TRIPS
 * session column while the rest scrolls, and the lap timer rows are lower, so the driver never has to
 * scroll for them. Decided by the screen, not by the room left over, so a warning banner that comes
 * and goes does not rearrange the panel.
 */
private const val SESSION_ACTIONS_PINNED_BELOW_DP = 600f

internal fun areSessionActionsPinned(profile: ScreenProfile): Boolean = profile.heightDp < SESSION_ACTIONS_PINNED_BELOW_DP

internal fun isWarningsStacked(sectionWidthDp: Float): Boolean = sectionWidthDp < WARNINGS_STACKED_BELOW_DP

internal fun lapTableShowsAllColumns(tableWidthDp: Float): Boolean = tableWidthDp >= LAP_TABLE_ALL_COLUMNS_MIN_WIDTH_DP

internal fun gridColumnsFor(panelWidthDp: Float): Int = if (panelWidthDp >= TWO_CELL_GRID_MIN_WIDTH_DP) 2 else 1

internal fun statColumnsFor(tableWidthDp: Float): Int = if (tableWidthDp >= THREE_STAT_COLUMNS_MIN_WIDTH_DP) 3 else 2

internal fun isSetupSplit(profile: ScreenProfile): Boolean = profile.widthDp >= NARROW_BELOW_DP

internal fun showsLinkProtocol(profile: ScreenProfile): Boolean = profile.widthDp >= NARROW_BELOW_DP

internal fun sensorColumnCount(profile: ScreenProfile): Int = if (profile.widthDp >= NARROW_BELOW_DP) 2 else 1

/**
 * How many small fields share a row of the dashboard: all [fieldCount] in one row while each keeps
 * [MIN_SMALL_FIELD_WIDTH_DP], otherwise three per row. One row is preferred on a short screen,
 * where height is scarcer than width.
 */
internal fun smallFieldColumns(profile: ScreenProfile, fieldCount: Int): Int =
    if (profile.widthDp / fieldCount >= MIN_SMALL_FIELD_WIDTH_DP) fieldCount else WRAPPED_FIELD_COLUMNS

/**
 * The sizes on the dashboard pages that are chosen per tier instead of simply scaling: on a short
 * screen the RPM bar and the rows of small fields give height back to the big numerals.
 */
internal data class DashMetrics(val barHeight: Dp, val barPaddingTop: Dp, val smallFieldRowHeight: Dp)

internal fun dashMetricsOf(tier: SizeTier): DashMetrics = when (tier) {
    SizeTier.COMPACT -> DashMetrics(barHeight = 64.dp, barPaddingTop = 8.dp, smallFieldRowHeight = 88.dp)
    SizeTier.MEDIUM, SizeTier.EXPANDED -> DashMetrics(barHeight = 96.dp, barPaddingTop = 12.dp, smallFieldRowHeight = 104.dp)
}

/**
 * The width of a side column that is [designWidth] on the reference unit: it keeps [share] of the
 * [totalWidth] it sits in on a narrower screen, but never less than [minWidth] and never more than
 * the design width, so a wide screen is laid out as designed.
 */
internal fun sideColumnWidth(totalWidth: Dp, share: Float, minWidth: Dp, designWidth: Dp): Dp =
    (totalWidth * share).coerceIn(minWidth, designWidth)

/** Running text is never set wider than this, so a line stays readable on a 1920 dp unit (about 80 characters of body text). */
internal val ReadingMaxWidth = 800.dp

/** The single-column wizard steps are a centred panel no wider than this on a wide unit. */
internal val SinglePaneMaxWidth = 960.dp
