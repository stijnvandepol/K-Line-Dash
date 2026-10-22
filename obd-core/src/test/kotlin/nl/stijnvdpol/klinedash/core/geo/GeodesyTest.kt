package nl.stijnvdpol.klinedash.core.geo

import nl.stijnvdpol.klinedash.core.assertFails
import org.junit.Assert.assertEquals
import org.junit.Test

class GeodesyTest {

    private val oneDegreeM = Math.toRadians(1.0) * Geodesy.EARTH_RADIUS_M

    @Test
    fun distanceOfOneDegreeOfLatitudeIsAboutOneHundredElevenKilometres() {
        assertEquals(oneDegreeM, Geodesy.distanceM(LatLon(52.0, 5.0), LatLon(53.0, 5.0)), 0.01)
        assertEquals(111_195.0, oneDegreeM, 1.0)
    }

    @Test
    fun distanceIsSymmetricAndZeroForTheSamePoint() {
        val a = LatLon(52.0, 5.0)
        val b = LatLon(51.5, 4.2)
        assertEquals(Geodesy.distanceM(a, b), Geodesy.distanceM(b, a), 1e-6)
        assertEquals(0.0, Geodesy.distanceM(a, a), 0.0)
    }

    @Test
    fun distanceOfLongitudeShrinksWithLatitude() {
        val atEquator = Geodesy.distanceM(LatLon(0.0, 5.0), LatLon(0.0, 6.0))
        val at60North = Geodesy.distanceM(LatLon(60.0, 5.0), LatLon(60.0, 6.0))
        assertEquals(0.5, at60North / atEquator, 1e-3)
    }

    @Test
    fun bearingFollowsTheCompass() {
        val origin = LatLon(52.0, 5.0)
        assertEquals(0.0, Geodesy.bearingDeg(origin, LatLon(53.0, 5.0)), 1e-6)
        assertEquals(180.0, Geodesy.bearingDeg(origin, LatLon(51.0, 5.0)), 1e-6)
        assertEquals(90.0, Geodesy.bearingDeg(origin, LatLon(52.0, 5.01)), 0.01)
        assertEquals(270.0, Geodesy.bearingDeg(origin, LatLon(52.0, 4.99)), 0.01)
    }

    @Test
    fun destinationIsTheInverseOfDistanceAndBearing() {
        val origin = LatLon(52.0, 5.0)
        val target = Geodesy.destination(origin, 60.0, 2_500.0)
        assertEquals(2_500.0, Geodesy.distanceM(origin, target), 1e-3)
        assertEquals(60.0, Geodesy.bearingDeg(origin, target), 1e-3)
    }

    @Test
    fun destinationWrapsAcrossTheAntimeridian() {
        val target = Geodesy.destination(LatLon(0.0, 179.9999), 90.0, 100.0)
        assertEquals(-179.9992, target.lon, 1e-4)
    }

    @Test
    fun normalizeDegreesWrapsIntoZeroToThreeSixty() {
        assertEquals(350.0, Geodesy.normalizeDegrees(-10.0), 1e-9)
        assertEquals(10.0, Geodesy.normalizeDegrees(370.0), 1e-9)
        assertEquals(0.0, Geodesy.normalizeDegrees(360.0), 1e-9)
    }

    @Test
    fun invalidPositionsAreRejected() {
        assertFails<IllegalArgumentException> { LatLon(91.0, 0.0) }
        assertFails<IllegalArgumentException> { LatLon(0.0, 181.0) }
        assertFails<IllegalArgumentException> { GeoFix(0L, Double.NaN, 0.0) }
    }

    @Test
    fun localFrameMeasuresMetresEastAndNorth() {
        val frame = LocalFrame(LatLon(52.0, 5.0))
        val point = frame.project(TestWorld.position(120.0, -80.0))
        assertEquals(120.0, point.x, 1e-3)
        assertEquals(-80.0, point.y, 1e-3)
    }
}
