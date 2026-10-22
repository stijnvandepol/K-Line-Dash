package nl.stijnvdpol.klinedash.ui.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import nl.stijnvdpol.klinedash.core.geo.Gate
import nl.stijnvdpol.klinedash.core.geo.LatLon
import nl.stijnvdpol.klinedash.data.Track
import nl.stijnvdpol.klinedash.ui.FieldCaption
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private val RouteLineWidth = 2.dp
private val StartFinishBarWidth = 6.dp
private val SectorBarWidth = 3.dp

/** A gate bar is never drawn shorter than this, so it stays visible on a route that covers many kilometres. */
private val MinBarLength = 16.dp
private val FieldMargin = 32.dp
private const val NORTH_LABEL = "N"

private class TrackGates(val startFinish: Gate, val sectors: List<Gate>) {
    val ends: List<LatLon> get() = (listOf(startFinish) + sectors).flatMap { listOf(it.a, it.b) }
}

/**
 * The driven line on `surface`, no map tiles, north up, scaled to fit. Without [lap] the whole line is `ink`;
 * with one, the line is `inkFaint` and the [lap] part `accent`. The track's start/finish and sector
 * gates are short `accent` bars. [description] is what a screen reader says for the picture.
 */
@Composable
internal fun RouteView(route: List<LatLon>, lap: List<LatLon>?, track: Track?, description: String, modifier: Modifier = Modifier) {
    val colors = KLineTheme.colors
    val gates = remember(track) { track?.let { TrackGates(it.startFinish.toGate(), it.sectors.map { sector -> sector.toGate() }) } }
    // Clipped: a stray fix that the scale ignores (see RouteProjector) is drawn outside the field, not over the tables.
    Box(modifier.background(colors.surface).clipToBounds().semantics { contentDescription = description }) {
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val projector = RouteProjector.fit(route, size.width, size.height, FieldMargin.toPx(), gates?.ends.orEmpty())
                val stroke = Stroke(RouteLineWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val linePath = projector?.let { pathOf(route, it) }
                val lapPath = projector?.let { lap?.let { positions -> pathOf(positions, it) } }
                val minBar = MinBarLength.toPx()
                onDrawBehind {
                    if (projector == null || linePath == null) return@onDrawBehind
                    drawPath(linePath, if (lapPath == null) colors.ink else colors.inkFaint, style = stroke)
                    lapPath?.let { drawPath(it, colors.accent, style = stroke) }
                    gates?.sectors?.forEach { drawBar(it, projector, colors.accent, SectorBarWidth.toPx(), minBar) }
                    gates?.let { drawBar(it.startFinish, projector, colors.accent, StartFinishBarWidth.toPx(), minBar) }
                }
            },
        )
        // North is up; the letter says so, a compass would be a graphic too many.
        FieldCaption(NORTH_LABEL, Modifier.padding(Spacing.fieldPaddingHorizontal))
    }
}

private fun pathOf(positions: List<LatLon>, projector: RouteProjector): Path {
    val path = Path()
    positions.forEachIndexed { index, position ->
        val point = projector.project(position)
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    return path
}

private fun DrawScope.drawBar(gate: Gate, projector: RouteProjector, color: Color, widthPx: Float, minLengthPx: Float) {
    val a = projector.project(gate.a)
    val b = projector.project(gate.b)
    val length = hypot(b.x - a.x, b.y - a.y)
    // A gate of zero length has no direction; it is not drawn.
    if (length == 0f) return
    val middle = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
    val factor = maxOf(length, minLengthPx) / length / 2
    val half = Offset((b.x - a.x) * factor, (b.y - a.y) * factor)
    drawLine(color, middle - half, middle + half, widthPx)
}
