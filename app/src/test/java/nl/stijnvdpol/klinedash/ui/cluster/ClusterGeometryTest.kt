package nl.stijnvdpol.klinedash.ui.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private const val EPSILON = 0.001f

class ArcScaleTest {
    @Test
    fun aRoundDialOf250DegreesStartsAtTheBottomLeftAndEndsAtTheBottomRight() {
        assertEquals(145f, DialTach.startDeg, EPSILON)
        assertEquals(395f, DialTach.startDeg + DialTach.sweepDeg, EPSILON)
        assertEquals(270f, DialTach.angleDeg(4_500.0), EPSILON)
    }

    @Test
    fun theArcTachometerOf200DegreesIsCentredOnTwelveOClock() {
        assertEquals(170f, ArcTach.angleDeg(0.0), EPSILON)
        assertEquals(370f, ArcTach.angleDeg(TACH_MAX_RPM), EPSILON)
        assertEquals(270f, ArcTach.angleDeg(4_500.0), EPSILON)
    }

    @Test
    fun valuesOutsideTheScaleStopAtItsEnds() {
        assertEquals(DialTach.angleDeg(0.0), DialTach.angleDeg(-300.0), 0f)
        assertEquals(DialTach.angleDeg(TACH_MAX_RPM), DialTach.angleDeg(12_000.0), 0f)
        assertEquals(0f, DialTach.fraction(-1.0), 0f)
        assertEquals(1f, DialTach.fraction(99_999.0), 0f)
    }

    @Test
    fun theSpeedometerCoversZeroTo260() {
        assertEquals(145f, DialSpeedo.angleDeg(0.0), EPSILON)
        assertEquals(395f, DialSpeedo.angleDeg(SPEEDO_MAX_KMH), EPSILON)
    }

    @Test
    fun theRingsFillUpTheInsideOfTheScreenAndMirrorEachOther() {
        assertEquals(118f, LeftRingTach.angleDeg(0.0), EPSILON)
        assertEquals(-118f, LeftRingTach.angleDeg(TACH_MAX_RPM), EPSILON)
        assertEquals(0f, LeftRingTach.angleDeg(4_500.0), EPSILON)
        for (fraction in listOf(0.0, 0.25, 0.5, 1.0)) {
            val left = LeftRingTach.angleDeg(fraction * TACH_MAX_RPM)
            val right = RightRingSpeed.angleDeg(fraction * SPEEDO_MAX_KMH)
            assertEquals(180f - left, right, EPSILON)
        }
    }

    @Test
    fun theCoolantDialIsAQuarterCircleAcrossTheTop() {
        assertEquals(225f, CoolantScale.angleDeg(40.0), EPSILON)
        assertEquals(315f, CoolantScale.angleDeg(130.0), EPSILON)
    }
}

class DialTicksTest {
    @Test
    fun theTachHasATickEvery500AndANumeralEvery1000() {
        val ticks = dialTicks(DialTach, minorStep = 500, majorStep = 1_000, labelStep = 1_000)
        assertEquals(19, ticks.size)
        assertEquals(10, ticks.count { it.kind == TickKind.MAJOR })
        assertEquals((0..9).map { it * 1_000.0 }, ticks.filter { it.hasLabel }.map { it.value })
        assertEquals(145f, ticks.first().angleDeg, EPSILON)
        assertEquals(395f, ticks.last().angleDeg, EPSILON)
        assertTrue(ticks.filter { it.kind == TickKind.MINOR }.none { it.hasLabel })
    }

    @Test
    fun theSpeedometerHasMajorTicksEvery20AndNumeralsEvery40() {
        val ticks = dialTicks(DialSpeedo, minorStep = 10, majorStep = 20, labelStep = 40)
        assertEquals(27, ticks.size)
        assertEquals(14, ticks.count { it.kind == TickKind.MAJOR })
        assertEquals((0..6).map { it * 40.0 }, ticks.filter { it.hasLabel }.map { it.value })
        assertEquals(260.0, ticks.last().value, 0.0)
    }

    @Test
    fun ticksAreSpacedEvenlyAlongTheSweep() {
        val ticks = dialTicks(DialTach, 500, 1_000, 1_000)
        ticks.zipWithNext { a, b -> b.angleDeg - a.angleDeg }.forEach { assertEquals(250f / 18f, it, EPSILON) }
    }

    @Test
    fun stepsThatDoNotDivideTheRangeAreRejected() {
        try {
            dialTicks(DialTach, minorStep = 700, majorStep = 1_400, labelStep = 1_400)
            fail("expected an IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("do not divide"))
        }
    }
}

class PolarTest {
    @Test
    fun anglesRunClockwiseFromThreeOClock() {
        val right = polar(100f, 200f, 50f, 0f)
        assertEquals(150f, right.x, EPSILON)
        assertEquals(200f, right.y, EPSILON)
        val down = polar(100f, 200f, 50f, 90f)
        assertEquals(100f, down.x, EPSILON)
        assertEquals(250f, down.y, EPSILON)
        assertEquals(150f, polar(100f, 200f, 50f, 270f).y, EPSILON)
    }

    @Test
    fun theDistanceFromTheCentreIsTheRadiusAtAnyAngle() {
        for (angle in listOf(33f, 145f, 233f, 351f)) {
            val p = polar(10f, 10f, 80f, angle)
            assertEquals(80f, hypot(p.x - 10f, p.y - 10f), EPSILON)
            assertEquals(10f + 80f * cos(Math.toRadians(angle.toDouble())).toFloat(), p.x, EPSILON)
            assertEquals(10f + 80f * sin(Math.toRadians(angle.toDouble())).toFloat(), p.y, EPSILON)
        }
    }
}

class SegmentArcTest {
    @Test
    fun segmentsTileTheScaleWithTheGapSplitOverBothSides() {
        val (firstStart, firstSweep) = segmentArc(ArcTach, 0, 45, 1f)
        val (lastStart, lastSweep) = segmentArc(ArcTach, 44, 45, 1f)
        assertEquals(170.5f, firstStart, EPSILON)
        assertEquals(369.5f, lastStart + lastSweep, EPSILON)
        assertEquals(200f / 45f - 1f, firstSweep, EPSILON)
        assertEquals(firstSweep, lastSweep, EPSILON)
    }

    @Test
    fun neighbouringSegmentsAreExactlyOneGapApart() {
        val (aStart, aSweep) = segmentArc(ArcTach, 10, 45, 0.7f)
        val (bStart, _) = segmentArc(ArcTach, 11, 45, 0.7f)
        assertEquals(0.7f, bStart - (aStart + aSweep), EPSILON)
    }
}

class ShiftLightTest {
    private val shift = 7_800

    @Test
    fun noLightBurnsMoreThan1500RpmBeforeTheShiftPoint() {
        assertEquals(0, shiftLightCount(0.0, shift))
        assertEquals(0, shiftLightCount(6_299.0, shift))
    }

    @Test
    fun theFirstLightComesOn1500RpmBeforeAndAllTenAtTheShiftPoint() {
        assertEquals(1, shiftLightCount(6_300.0, shift))
        assertEquals(9, shiftLightCount(7_799.0, shift))
        assertEquals(10, shiftLightCount(7_800.0, shift))
        assertEquals(10, shiftLightCount(9_000.0, shift))
    }

    @Test
    fun theLightsComeOnInEqualSteps() {
        val counts = (6_300..7_800 step 10).map { shiftLightCount(it.toDouble(), shift) }
        assertEquals((1..10).toList(), counts.distinct())
        assertEquals(counts, counts.sorted())
    }

    @Test
    fun theLightsFollowTheShiftPointOfTheTrackPage() {
        assertEquals(1, shiftLightCount(5_000.0, 6_500))
        assertEquals(0, shiftLightCount(5_000.0, 6_501))
        assertEquals(10, shiftLightCount(6_500.0, 6_500))
    }

    @Test
    fun greenThenAmberThenRed() {
        val colours = (0 until SHIFT_LIGHT_COUNT).map { shiftLightColour(it) }
        assertEquals(4, colours.count { it == ShiftLightColour.GREEN })
        assertEquals(3, colours.count { it == ShiftLightColour.AMBER })
        assertEquals(3, colours.count { it == ShiftLightColour.RED })
        assertEquals(colours, colours.sortedBy { it.ordinal })
    }
}

class ClusterLayoutTest {
    private val areas = listOf(1280f to 544f, 1024f to 424f, 2000f to 1024f)

    @Test
    fun theTwinDialsAreAboutHalfAThousandDpOnTheDesignScreen() {
        assertEquals(495f, twinDialSize(1280f, 544f, 16f), 1f)
    }

    @Test
    fun twoDialsAndTheCoolantColumnAlwaysFitTheWidthAndTheHeight() {
        areas.forEach { (width, height) ->
            val dial = twinDialSize(width, height, 16f)
            assertTrue("$width: ${2 * dial + twinCoolantColumn(dial)} wide", 2 * dial + twinCoolantColumn(dial) <= width - 32f + 0.01f)
            assertTrue("$height: $dial tall", dial <= height)
        }
    }

    @Test
    fun theCentreDialIsAbout520DpOnTheDesignScreenAndLeavesRoomForTheFieldsBeside() {
        assertEquals(520f, centreDialSize(1280f, 544f), 5f)
        areas.forEach { (width, height) -> assertTrue(centreDialSize(width, height) <= width * 0.5f) }
    }

    @Test
    fun theArcAndItsLightsFitEveryAreaAndTheLightsStayAboveTheArc() {
        areas.forEach { (width, height) ->
            val layout = ArcLayout(width, height)
            assertTrue("lights above the arc", layout.lightsTop + layout.lightsHeight < layout.centerY - layout.radius)
            assertTrue("arc ends inside the area", layout.centerY + layout.radius * sin(Math.toRadians(10.0)).toFloat() <= height)
            assertTrue("arc inside the width", layout.centerX - layout.radius >= 0f)
            assertTrue("room for the corner fields", layout.sideRoom > 150f)
        }
    }

    @Test
    fun theRingsAreCutByTheScreenEdgesAndLeaveRoomForTheList() {
        areas.forEach { (width, height) ->
            val layout = RingLayout(width, height)
            assertTrue("left ring is cut", layout.leftCenterX - layout.radius < 0f)
            assertTrue("right ring is cut", layout.rightCenterX + layout.radius > width)
            assertTrue("ring fits the height", layout.centerY + layout.radius <= height)
            assertTrue("list is at least 300 wide: ${layout.listEnd - layout.listStart}", layout.listEnd - layout.listStart >= 300f)
        }
    }
}
