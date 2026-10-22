package nl.stijnvdpol.klinedash.ui.trips

import kotlin.math.cos
import nl.stijnvdpol.klinedash.core.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProjectorTest {
    private val origin = LatLon(51.0, 5.0)

    /** Metres per degree of latitude on the sphere the app uses; only used to build test positions. */
    private val metresPerDegree = 111_195.0

    private fun north(metres: Double) = LatLon(origin.lat + metres / metresPerDegree, origin.lon)

    private fun east(metres: Double) = LatLon(origin.lat, origin.lon + metres / (metresPerDegree * cos(Math.toRadians(origin.lat))))

    private fun fit(positions: List<LatLon>, width: Float = 400f, height: Float = 400f, margin: Float = 20f): RouteProjector =
        checkNotNull(RouteProjector.fit(positions, width, height, margin))

    @Test
    fun `north is up`() {
        val projector = fit(listOf(origin, north(100.0)))
        assertTrue(projector.project(north(100.0)).y < projector.project(origin).y)
    }

    @Test
    fun `east is to the right`() {
        val projector = fit(listOf(origin, east(100.0)))
        assertTrue(projector.project(east(100.0)).x > projector.project(origin).x)
    }

    @Test
    fun `the route fits the field inside its margin`() {
        val positions = listOf(origin, north(300.0), east(150.0))
        val points = positions.map(fit(positions, width = 600f, height = 300f, margin = 30f)::project)
        assertTrue(points.all { it.x >= 30f - 0.01f && it.x <= 570f + 0.01f })
        assertTrue(points.all { it.y >= 30f - 0.01f && it.y <= 270f + 0.01f })
    }

    @Test
    fun `the longer side touches the margin and the shape is not distorted`() {
        val projector = fit(listOf(origin, north(200.0), east(100.0)), width = 400f, height = 400f, margin = 20f)
        val bottom = projector.project(origin)
        val top = projector.project(north(200.0))
        val right = projector.project(east(100.0))
        assertEquals(360f, bottom.y - top.y, 0.5f)
        assertEquals(180f, right.x - bottom.x, 0.5f)
    }

    @Test
    fun `the route is centred in the field`() {
        val projector = fit(listOf(origin, east(100.0)), width = 400f, height = 300f, margin = 20f)
        val left = projector.project(origin)
        val right = projector.project(east(100.0))
        assertEquals(200f, (left.x + right.x) / 2, 0.5f)
        assertEquals(150f, left.y, 0.5f)
    }

    @Test
    fun `a single point is drawn in the middle`() {
        val point = fit(listOf(origin), width = 400f, height = 300f).project(origin)
        assertEquals(200f, point.x, 0.01f)
        assertEquals(150f, point.y, 0.01f)
    }

    @Test
    fun `nothing to draw or no room gives no projector`() {
        assertNull(RouteProjector.fit(emptyList(), 400f, 400f, 20f))
        assertNull(RouteProjector.fit(listOf(origin), 30f, 400f, 20f))
    }
}
