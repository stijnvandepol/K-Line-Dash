package nl.stijnvdpol.klinedash.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.data.DashboardLayout
import nl.stijnvdpol.klinedash.ui.pages.Peaks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    private val readings = MutableStateFlow<Map<String, Reading>>(emptyMap())
    private lateinit var vm: DashboardViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = DashboardViewModel(flowOf(DashboardLayout.Default), readings)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun publish(rpm: Double, speed: Double, atMs: Long) {
        readings.value = mapOf("rpm" to Reading("rpm", rpm, atMs), "speed" to Reading("speed", speed, atMs))
    }

    @Test
    fun `the layout arrives from the repository flow`() {
        assertEquals(DashboardLayout.Default, vm.layout.value)
    }

    @Test
    fun `peaks follow the readings and reset starts over`() {
        publish(rpm = 7_900.0, speed = 110.0, atMs = 100)
        publish(rpm = 3_000.0, speed = 40.0, atMs = 200)
        assertEquals(Peaks(7_900.0, 110.0), vm.peaks.value)

        vm.resetPeaks()
        assertEquals(Peaks(), vm.peaks.value)

        // Another signal updates; the poller still holds the old rpm and speed, which must not raise the peak again.
        readings.value = readings.value + ("load" to Reading("load", 30.0, 250))
        assertEquals(Peaks(), vm.peaks.value)

        publish(rpm = 2_500.0, speed = 20.0, atMs = 300)
        assertEquals(Peaks(2_500.0, 20.0), vm.peaks.value)
    }

    @Test
    fun `graph signals are recorded and each new point bumps the revision`() {
        val before = vm.graphRevision.value
        publish(rpm = 900.0, speed = 0.0, atMs = 100)
        assertEquals(before + 1, vm.graphRevision.value)
        assertEquals(1, vm.history.of("rpm")?.size)
        assertEquals(1, vm.history.of("speed")?.size)
        assertNull("intake has no graph", vm.history.of("intake_temp"))

        readings.value = readings.value + ("load" to Reading("load", 30.0, 150))
        assertEquals("the same readings again add nothing", before + 1, vm.graphRevision.value)

        publish(rpm = 950.0, speed = 0.0, atMs = 200)
        assertEquals(before + 2, vm.graphRevision.value)
    }
}
