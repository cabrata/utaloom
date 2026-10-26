package com.utaloom.platform

import java.awt.Dimension
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window

const val MONITOR_ACTIVE = "active"
const val MONITOR_PRIMARY = "primary"
const val ACTIVE_MONITOR = MONITOR_ACTIVE
const val PRIMARY_MONITOR = MONITOR_PRIMARY
const val DEFAULT_TOP_OFFSET = 8

/**
 * Pure descriptor representing an available display monitor and its usable area.
 *
 * @property id Stable identifier for monitor selection (e.g. AWT iDstring or screen index fallback).
 * @property bounds Screen bounds in virtual desktop coordinates.
 * @property insets Safe area / taskbar insets reported by the windowing system.
 * @property isPrimary Whether this screen is the primary display.
 * @property name Human-readable display label.
 */
data class MonitorDescriptor(
    val id: String,
    val bounds: Rectangle,
    val insets: Insets = Insets(0, 0, 0, 0),
    val isPrimary: Boolean = false,
    val name: String = id,
) {
    val usableBounds: Rectangle
        get() = Rectangle(
            bounds.x + insets.left,
            bounds.y + insets.top,
            (bounds.width - insets.left - insets.right).coerceAtLeast(0),
            (bounds.height - insets.top - insets.bottom).coerceAtLeast(0),
        )
}

typealias IslandMonitor = MonitorDescriptor

/**
 * Returns stable identifier for a GraphicsDevice.
 */
fun stableMonitorId(device: GraphicsDevice): String = device.getIDstring()

/**
 * Calculates top-center position for the island within given bounds and insets.
 */
fun islandTopCenter(
    bounds: Rectangle,
    insets: Insets = Insets(0, 0, 0, 0),
    size: Dimension,
    topOffset: Int = DEFAULT_TOP_OFFSET,
): Point {
    val left = bounds.x + insets.left
    val usableWidth = (bounds.width - insets.left - insets.right).coerceAtLeast(size.width)
    return Point(left + (usableWidth - size.width) / 2, bounds.y + insets.top + topOffset)
}

/**
 * Calculates top-center position for the island on the specified monitor.
 */
fun islandTopCenter(
    monitor: MonitorDescriptor,
    size: Dimension,
    topOffset: Int = DEFAULT_TOP_OFFSET,
): Point = islandTopCenter(monitor.bounds, monitor.insets, size, topOffset)

/**
 * Selects a monitor from available descriptors based on target identifier.
 */
fun selectIslandMonitor(
    monitors: List<MonitorDescriptor>,
    preference: String,
    activeMonitorId: String?,
    primaryMonitorId: String? = null,
): MonitorDescriptor? {
    if (monitors.isEmpty()) return null
    fun monitor(id: String?) = monitors.firstOrNull { it.id == id }
    return when (preference) {
        MONITOR_ACTIVE -> monitor(activeMonitorId) ?: monitor(primaryMonitorId)
        MONITOR_PRIMARY -> monitor(primaryMonitorId)
        else -> {
            // ponytail: stale selection falls back to active, primary, or first monitor; upgrade to persistent UUID mapping if multi-monitor IDs drift
            monitor(preference)
                ?: monitors.firstOrNull { it.id.equals(preference, ignoreCase = true) || it.name == preference }
                ?: monitor(activeMonitorId)
                ?: monitor(primaryMonitorId)
        }
    } ?: monitors.first()
}

/**
 * Selects a monitor using automatic active monitor, primary, or specific ID with stale fallback.
 */
fun selectMonitor(
    monitors: List<MonitorDescriptor>,
    targetId: String? = MONITOR_ACTIVE,
    activeMonitorId: String? = null,
): MonitorDescriptor? {
    val primary = monitors.firstOrNull { it.isPrimary } ?: monitors.firstOrNull()
    val target = targetId?.trim()?.ifEmpty { MONITOR_ACTIVE } ?: MONITOR_ACTIVE
    return selectIslandMonitor(
        monitors = monitors,
        preference = target,
        activeMonitorId = activeMonitorId,
        primaryMonitorId = primary?.id,
    )
}

/**
 * Enumerates all connected display devices via AWT across Windows and Linux.
 */
fun enumerateMonitors(): List<MonitorDescriptor> {
    if (GraphicsEnvironment.isHeadless()) return emptyList()
    // ponytail: AWT screen device enumeration without platform-native xrandr/Win32 APIs; upgrade if multi-DPI fractional scaling bounds skew
    val ge = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment() }.getOrNull() ?: return emptyList()
    val defaultDevice = runCatching { ge.defaultScreenDevice }.getOrNull()
    return ge.screenDevices.mapIndexed { index, device ->
        val config = device.defaultConfiguration
        val bounds = config.bounds
        val insets = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(config) }.getOrDefault(Insets(0, 0, 0, 0))
        val rawId = runCatching { device.getIDstring() }.getOrNull()?.trim().orEmpty()
        val id = if (rawId.isNotBlank()) rawId else "screen-$index"
        val isPrimary = (device == defaultDevice) || (defaultDevice == null && bounds.x == 0 && bounds.y == 0)
        val name = "Display ${index + 1} (${bounds.width}x${bounds.height})"
        MonitorDescriptor(
            id = id,
            bounds = bounds,
            insets = insets,
            isPrimary = isPrimary,
            name = name,
        )
    }
}

/**
 * Detects the currently active monitor using the focused window, cursor location, or primary display.
 */
fun detectActiveMonitor(monitors: List<MonitorDescriptor>, excludeWindow: Window? = null): MonitorDescriptor? {
    if (monitors.isEmpty()) return null

    // 1. Check active/focused window
    val activeWindow = runCatching {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
    }.getOrNull()

    if (activeWindow != null && activeWindow !== excludeWindow) {
        val windowBounds = activeWindow.bounds
        val center = Point(windowBounds.x + windowBounds.width / 2, windowBounds.y + windowBounds.height / 2)
        val matchingByCenter = monitors.firstOrNull { it.bounds.contains(center) }
        if (matchingByCenter != null) return matchingByCenter

        val matchingByDevice = runCatching { activeWindow.graphicsConfiguration?.device?.getIDstring() }.getOrNull()
        if (matchingByDevice != null) {
            val matching = monitors.firstOrNull { it.id == matchingByDevice }
            if (matching != null) return matching
        }
    }

    // 2. Check mouse cursor position
    val mouseLoc = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
    if (mouseLoc != null) {
        val matchingByMouse = monitors.firstOrNull { it.bounds.contains(mouseLoc) }
        if (matchingByMouse != null) return matchingByMouse
    }

    // 3. Fallback to primary or first
    return monitors.firstOrNull { it.isPrimary } ?: monitors.first()
}

/**
 * Returns the identifier of the currently active monitor, or null if undetected.
 */
fun detectActiveMonitorId(monitors: List<MonitorDescriptor>, excludeWindow: Window? = null): String? =
    detectActiveMonitor(monitors, excludeWindow)?.id

/**
 * Calculates the island position coordinates for a window without mutating it.
 */
fun calculateIslandPosition(
    window: Window,
    targetMonitor: String = MONITOR_ACTIVE,
    topOffset: Int = DEFAULT_TOP_OFFSET,
    monitors: List<MonitorDescriptor> = enumerateMonitors(),
): Point {
    val activeId = detectActiveMonitorId(monitors, excludeWindow = window)
    val selected = selectMonitor(monitors, targetMonitor, activeId)
    if (selected != null) {
        return islandTopCenter(selected, window.size, topOffset)
    }
    // Headless / edge-case AWT fallback
    val fallbackConfig = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    }.getOrNull()
    return if (fallbackConfig != null) {
        val insets = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(fallbackConfig) }.getOrDefault(Insets(0, 0, 0, 0))
        islandTopCenter(fallbackConfig.bounds, insets, window.size, topOffset)
    } else {
        Point(0, topOffset)
    }
}

/**
 * Positions the Dynamic Island window at top-center on the target monitor.
 * Falls back to centering on screen if windowing system APIs fail.
 */
fun positionDynamicIsland(
    window: Window,
    targetMonitor: String = MONITOR_ACTIVE,
    topOffset: Int = DEFAULT_TOP_OFFSET,
) {
    runCatching {
        window.location = calculateIslandPosition(window, targetMonitor, topOffset)
    }.onFailure {
        runCatching { window.setLocationRelativeTo(null) }
    }
}
