package com.utaloom.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import java.awt.DisplayMode
import java.awt.GraphicsConfiguration
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JWindow

class IslandPlacementTest {
    private val left = MonitorDescriptor("left", Rectangle(-1920, 0, 1920, 1080), isPrimary = false, name = "Display Left")
    private val primary = MonitorDescriptor("primary-id", Rectangle(0, 0, 2560, 1440), isPrimary = true, name = "Display Primary")
    private val right = MonitorDescriptor("right", Rectangle(2560, 0, 1920, 1080), isPrimary = false, name = "Display Right")
    private val monitors = listOf(left, primary, right)

    @Test
    fun activeModeUsesActiveThenPrimary() {
        assertEquals(right, selectIslandMonitor(monitors, ACTIVE_MONITOR, "right", "primary-id"))
        assertEquals(primary, selectIslandMonitor(monitors, ACTIVE_MONITOR, "disconnected", "primary-id"))
        assertEquals(right, selectMonitor(monitors, MONITOR_ACTIVE, "right"))
        assertEquals(primary, selectMonitor(monitors, MONITOR_ACTIVE, "disconnected"))
        assertEquals(primary, selectMonitor(monitors, null, null))
    }

    @Test
    fun primaryModeUsesPrimaryRegardlessOfActiveMonitor() {
        assertEquals(primary, selectIslandMonitor(monitors, PRIMARY_MONITOR, "right", "primary-id"))
        assertEquals(primary, selectMonitor(monitors, MONITOR_PRIMARY, "right"))
    }

    @Test
    fun stableIdPreferenceSurvivesOrderingAndFallsBackWhenStale() {
        assertEquals(right, selectIslandMonitor(monitors.reversed(), "right", "left", "primary-id"))
        assertEquals(left, selectIslandMonitor(monitors, "removed", "left", "primary-id"))
        assertEquals(primary, selectIslandMonitor(monitors, "removed", "also-removed", "primary-id"))
        assertEquals(left, selectIslandMonitor(monitors, "removed", null, "also-removed"))
        assertNull(selectIslandMonitor(emptyList(), "removed", null, null))

        // selectMonitor with stale fallback
        assertEquals(right, selectMonitor(monitors, "right", "left"))
        assertEquals(left, selectMonitor(monitors, "stale-id", "left"))
        assertEquals(primary, selectMonitor(monitors, "stale-id", null))
        assertNull(selectMonitor(emptyList(), "stale-id", null))
    }

    @Test
    fun topCenterUsesWorkAreaAndConfiguredOffset() {
        val monitor = MonitorDescriptor(
            id = "screen",
            bounds = Rectangle(100, 20, 1920, 1080),
            insets = Insets(20, 10, 40, 30),
        )

        assertEquals(Point(800, 65), islandTopCenter(monitor, Dimension(500, 200), 25))
        assertEquals(Point(110, 35), islandTopCenter(monitor, Dimension(2_000, 200), -5))
        // Default top offset (8) matches baseline
        assertEquals(Point(800, 48), islandTopCenter(monitor, Dimension(500, 200)))
        assertEquals(
            Point(800, 48),
            islandTopCenter(Rectangle(100, 20, 1920, 1080), Insets(20, 10, 40, 30), Dimension(500, 200)),
        )
    }

    @Test
    fun monitorDescriptorUsableBoundsCalculatesCorrectly() {
        val monitor = MonitorDescriptor(
            id = "screen",
            bounds = Rectangle(100, 50, 1920, 1080),
            insets = Insets(30, 10, 40, 20),
            isPrimary = true,
        )
        assertEquals(Rectangle(110, 80, 1890, 1010), monitor.usableBounds)
    }

    @Test
    fun detectActiveMonitorFallsBackGracefully() {
        assertEquals(primary, detectActiveMonitor(monitors, null))
        assertNull(detectActiveMonitor(emptyList(), null))
    }

    @Test
    fun graphicsDeviceIdIsUsedWithoutPlatformParsing() {
        assertEquals("platform-specific-id", stableMonitorId(FakeGraphicsDevice("platform-specific-id")))
    }

    @Test
    fun runtimeEnumerationDoesNotCrash() {
        val list = enumerateMonitors()
        assertNotNull(list)
        if (!GraphicsEnvironment.isHeadless() && list.isNotEmpty()) {
            assertTrue(list.any { it.isPrimary } || list.size == 1)
        }
    }

    @Test
    fun calculateAndPositionDynamicIslandFallsBackSafely() {
        if (!GraphicsEnvironment.isHeadless()) {
            val window = JWindow()
            window.size = Dimension(300, 60)
            val pos = calculateIslandPosition(window, MONITOR_PRIMARY, 10, monitors)
            // Primary monitor is at (0, 0, 2560, 1440), insets 0, size 300x60
            // x = (2560 - 300) / 2 = 1130, y = 0 + 10 = 10
            assertEquals(Point(1130, 10), pos)
            positionDynamicIsland(window, MONITOR_PRIMARY, 10)
            assertEquals(Point(1130, 10), window.location)
            window.dispose()
        }
    }

    private class FakeGraphicsDevice(private val id: String) : GraphicsDevice() {
        override fun getType() = TYPE_RASTER_SCREEN
        override fun getIDstring() = id
        override fun getConfigurations(): Array<GraphicsConfiguration> = emptyArray()
        override fun getDefaultConfiguration(): GraphicsConfiguration = error("Not needed")
        override fun getDisplayMode(): DisplayMode = error("Not needed")
    }
}
