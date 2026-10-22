package nl.stijnvdpol.klinedash.service

import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TripFilesTest {
    private val startMs = 1_792_677_787_000L // 2026-10-22T14:03:07Z

    @Test
    fun fileNameUsesTheGivenZone() {
        assertEquals("trip-20261022-140307.csv", tripFileName(startMs, ZoneOffset.UTC))
        assertEquals("trip-20261022-160307.csv", tripFileName(startMs, ZoneId.of("Europe/Amsterdam")))
    }

    @Test
    fun secondTripInTheSameSecondGetsAnIndex() {
        assertEquals("trip-20261022-140307-2.csv", tripFileName(startMs, ZoneOffset.UTC, index = 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun indexBelowOneIsRejected() {
        tripFileName(startMs, ZoneOffset.UTC, index = 0)
    }

    @Test
    fun plainNamesResolveInsideTheTripsFolder() {
        val dir = File("trips")
        assertEquals(File(dir, "trip-1.csv"), resolveTripFile(dir, "trip-1.csv"))
    }

    @Test
    fun namesThatLeaveTheFolderAreRejected() {
        val dir = File("trips")
        assertNull(resolveTripFile(dir, "../secret.csv"))
        assertNull(resolveTripFile(dir, "sub/trip.csv"))
        assertNull(resolveTripFile(dir, ""))
        assertNotNull(resolveTripFile(dir, "a.csv"))
    }

    @Test
    fun theFolderItselfAndItsParentAreNotTripFiles() {
        val dir = File("trips")
        assertNull("the parent folder", resolveTripFile(dir, ".."))
        assertNull("the trips folder itself", resolveTripFile(dir, "."))
        assertNotNull("a name that merely starts with dots", resolveTripFile(dir, "..trip.csv"))
    }
}
