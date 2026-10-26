package com.utaloom

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.utaloom.data.PlaylistImports
import com.utaloom.data.Stores
import com.utaloom.platform.Mpris
import com.utaloom.platform.NativeLibs
import com.utaloom.platform.DesktopWindows
import com.utaloom.platform.DiscordPresence
import com.utaloom.platform.GlobalHotkeys
import com.utaloom.platform.Scrobbling
import com.utaloom.data.ListeningStats
import com.utaloom.playback.Player
import com.utaloom.together.ListenTogether
import com.utaloom.ui.Nav
import com.utaloom.ui.UtaloomApp
import com.utaloom.ui.applySession
import com.utaloom.ui.MiniPlayer
import com.utaloom.ui.DynamicIsland
import com.utaloom.ui.DefaultSeed
import com.utaloom.ui.UtaloomTheme
import com.utaloom.platform.positionDynamicIsland

fun main() = runApp(exitProcessOnExit = true)

internal fun runApp(exitProcessOnExit: Boolean) {
    applySession()
    val vlcError = NativeLibs.discoverVlc()
    if (vlcError == null) Player.init() else System.err.println(vlcError)
    Scrobbling.start()
    DiscordPresence.start()
    ListeningStats.start()
    Runtime.getRuntime().addShutdownHook(Thread {
        PlaylistImports.cancel(); GlobalHotkeys.stop(); Scrobbling.stop(); DiscordPresence.stop(); ListeningStats.stop()
        Mpris.stop(); Player.release(); Stores.flushAll()
    })
    application(exitProcessOnExit = exitProcessOnExit) {
        var visible by remember { mutableStateOf(true) }
        val settings by Stores.settings.state.collectAsState()
        val miniVisible by DesktopWindows.miniVisible.collectAsState()
        val islandVisible by DesktopWindows.islandVisible.collectAsState()
        val miniState = rememberWindowState(size = DpSize(380.dp, 320.dp))
        val islandState = rememberWindowState(size = com.utaloom.ui.islandSize(false))
        val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp))
        var previousPlacement by remember { mutableStateOf(WindowPlacement.Floating) }
        // Restoring bounds needs the outer rectangle: a request made while the window is still undecorated
        // is treated as the client area and the window manager then adds its title bar height on top.
        var previousBounds by remember { mutableStateOf<java.awt.Rectangle?>(null) }
        var previousInsets by remember { mutableStateOf<java.awt.Insets?>(null) }
        var awtWindow by remember { mutableStateOf<androidx.compose.ui.awt.ComposeWindow?>(null) }
        // Keyed separately from `fullscreen`: the resize Compose fires while leaving fullscreen would otherwise
        // cancel the restore loop that is still waiting for the window manager to decorate the window again.
        var restoreTo by remember { mutableStateOf<Pair<WindowPlacement, java.awt.Rectangle?>?>(null) }
        var restoreRequest by remember { mutableStateOf(0) }
        // Native resize feedback is not a user request and must not change the player or keyboard toggle intent.
        var fullscreen by remember { mutableStateOf(false) }
        fun setFullscreen(enabled: Boolean) {
            if (enabled == fullscreen) return
            if (!isLinux) {
                // Same as 1.2.0, which users found stable on Windows: let Compose drive the placement.
                fullscreen = enabled
                if (enabled && windowState.placement != WindowPlacement.Fullscreen) previousPlacement = windowState.placement
                windowState.placement = if (enabled) WindowPlacement.Fullscreen else previousPlacement
                return
            }
            // ponytail: the native placement juggling below works around X11 window managers. On Windows AWT's
            // exclusive fullscreen is synchronous and re-entering it crashes (realFSWindow null), so only Linux uses it.
            val w = awtWindow
            fullscreen = enabled
            restoreRequest++
            if (enabled) {
                val pending = restoreTo
                if (pending != null) {
                    previousPlacement = pending.first
                    previousBounds = pending.second
                } else {
                    previousPlacement = windowState.placement
                    previousBounds = w?.bounds?.takeIf { previousPlacement == WindowPlacement.Floating }
                    val ins = w?.insets
                    if (ins != null && ins.top > 0) previousInsets = ins
                }
                restoreTo = null
                // Set native placement before state so a queued resize cannot mirror the old placement back.
                if (w != null) {
                    if (w.placement == WindowPlacement.Maximized) w.placement = WindowPlacement.Floating
                    w.placement = WindowPlacement.Fullscreen
                }
                windowState.placement = WindowPlacement.Fullscreen
            } else {
                // Leave native fullscreen first: Compose mirrors native resizes back into the state, so a pending
                // event would otherwise set the placement back to Fullscreen right after we asked it to leave.
                if (w != null) {
                    val dev = w.graphicsConfiguration.device
                    if (dev.fullScreenWindow === w) dev.fullScreenWindow = null
                    if (w.placement == WindowPlacement.Fullscreen) w.placement = WindowPlacement.Floating
                    w.placement = previousPlacement
                }
                windowState.placement = previousPlacement
                if (w != null) restoreTo = previousPlacement to previousBounds
                previousBounds = null
            }
        }
        val player by Player.state.collectAsState()
        val icon = painterResource("icon.png")
        val trayState = rememberTrayState()
        val importing by PlaylistImports.progress.collectAsState()
        // Background imports report through the system tray, since the user may have hidden the window.
        LaunchedEffect(Unit) {
            PlaylistImports.events.collect { e ->
                if (isTraySupported && (e.background || !visible)) trayState.sendNotification(
                    Notification(if (e is PlaylistImports.Done) "Playlist imported" else "Playlist import failed", PlaylistImports.summary(e),
                        if (e is PlaylistImports.Done) Notification.Type.Info else Notification.Type.Error))
            }
        }
        fun showMain() {
            visible = true
            windowState.isMinimized = false
            awtWindow?.toFront()
            awtWindow?.requestFocus()
        }
        fun quit() {
            PlaylistImports.cancel(); ListenTogether.leaveRoom(); GlobalHotkeys.stop(); Scrobbling.stop()
            DiscordPresence.stop(); ListeningStats.stop(); DesktopWindows.closeMini()
            DesktopWindows.closeIsland()
            Mpris.stop(); Player.release(); Stores.flushAll(); exitApplication()
        }
        DisposableEffect(Unit) {
            Mpris.start(
                onRaise = { javax.swing.SwingUtilities.invokeLater { showMain() } },
                onQuit = { javax.swing.SwingUtilities.invokeLater { quit() } },
            )
            GlobalHotkeys.start(
                onShow = { javax.swing.SwingUtilities.invokeLater { showMain() } },
                onMiniPlayer = DesktopWindows::showMini,
            )
            onDispose { GlobalHotkeys.stop(); Mpris.stop() }
        }

        if (isTraySupported) Tray(icon, state = trayState, tooltip = importing?.let { p -> if (p.total == 0) "Utaloom: importing playlist…" else "Utaloom: importing ${p.done} / ${p.total}" }
            ?: player.current?.let { "${it.title} • ${it.artistText}" } ?: "Utaloom", onAction = { visible = true }, menu = {
            Item(if (player.isPlaying) "Pause" else "Play", onClick = Player::playPause)
            Item("Next", onClick = { Player.next() })
            Item("Previous", onClick = Player::previous)
            if (importing != null) { Separator(); Item("Cancel playlist import", onClick = PlaylistImports::cancel) }
            Separator()
            Item("Show Utaloom", onClick = { visible = true })
            Item("Mini player", onClick = DesktopWindows::showMini)
            Item(if (settings.dynamicIslandEnabled && islandVisible) "Hide Dynamic Island" else "Show Dynamic Island", onClick = {
                if (settings.dynamicIslandEnabled && islandVisible) DesktopWindows.closeIsland()
                else {
                    if (!settings.dynamicIslandEnabled) Stores.settings.update { it.copy(dynamicIslandEnabled = true) }
                    DesktopWindows.showIsland()
                }
            })
            Item("Quit", onClick = ::quit)
        })

        Window(
            onCloseRequest = { if (isTraySupported && settings.minimizeToTray && (player.current != null || importing != null)) visible = false else quit() },
            visible = visible,
            state = windowState,
            title = player.current?.let { "${it.title} • ${it.artistText} - Utaloom" } ?: "Utaloom",
            icon = icon,
            onPreviewKeyEvent = { e ->
                val isFullscreen = fullscreen
                when {
                    e.type == KeyEventType.KeyDown && e.key == Key.F11 && (Player.state.value.current != null || isFullscreen) -> {
                        setFullscreen(!isFullscreen)
                        true
                    }
                    e.type == KeyEventType.KeyDown && e.key == Key.Escape && isFullscreen -> {
                        setFullscreen(false)
                        true
                    }
                    else -> shortcut(e)
                }
            },
        ) {
            window.minimumSize = java.awt.Dimension(900, 600)
            // ponytail: Compose 1.12.1 restores before the WM re-adds decorations; remove this bounded settling loop
            // when its native fullscreen transition preserves decorated bounds reliably.
            LaunchedEffect(restoreRequest) {
                val placement = restoreTo?.first ?: return@LaunchedEffect
                val target = restoreTo?.second
                val request = restoreRequest
                var stable = 0
                repeat(60) {
                    // Check the live token too: re-entry can precede the recomposition cancelling this effect.
                    if (request != restoreRequest) return@LaunchedEffect
                    if (window.placement != placement) {
                        if (window.placement == WindowPlacement.Fullscreen) window.placement = WindowPlacement.Floating
                        window.placement = placement
                    }
                    windowState.placement = placement
                    // Resize only once the WM has decorated the window again: while undecorated the requested size
                    // means the client area and the title bar height still gets added on top.
                    val i = window.insets
                    val decorated = i.top > 0 || (previousInsets != null && i == previousInsets)
                    if (decorated) {
                        if (target == null || window.bounds == target) {
                            if (++stable >= 4) {
                                restoreTo = null
                                return@LaunchedEffect
                            }
                        } else {
                            stable = 0
                            window.bounds = target
                        }
                    } else stable = 0
                    kotlinx.coroutines.delay(50)
                }
                if (request == restoreRequest) restoreTo = null
            }
            // ponytail: on Windows a native resize can be mirrored back into the state and undo an exit (or entry),
            // so keep re-applying the requested placement until it holds. Runs between frames, never during layout.
            if (!isLinux) LaunchedEffect(fullscreen) {
                var stable = 0
                repeat(60) {
                    val want = if (fullscreen) WindowPlacement.Fullscreen else previousPlacement
                    if (window.placement == want && windowState.placement == want) {
                        if (++stable >= 4) return@LaunchedEffect
                    } else {
                        stable = 0
                        // Compose's own setter leaves exclusive mode once; clearing the device too crashes AWT
                        // (realFSWindow null), so never touch fullScreenWindow directly here.
                        if (window.placement != want) window.placement = want
                        windowState.placement = want
                    }
                    kotlinx.coroutines.delay(50)
                }
            }
            SideEffect {
                awtWindow = window
                // ponytail: Compose 1.12.1 doesn't clear fullscreen on maximize; remove when its native transition is fixed.
                if (windowState.placement == WindowPlacement.Maximized && window.placement == WindowPlacement.Fullscreen) {
                    window.placement = WindowPlacement.Floating
                    window.placement = WindowPlacement.Maximized
                }
            }
            UtaloomApp(vlcError, fullscreen, ::setFullscreen)
        }
        if (miniVisible) Window(
            onCloseRequest = DesktopWindows::closeMini,
            state = miniState,
            title = "Utaloom Mini player",
            icon = icon,
            alwaysOnTop = settings.miniPlayerAlwaysOnTop,
            onKeyEvent = ::shortcut,
        ) {
            window.minimumSize = java.awt.Dimension(340, 310)
            MiniPlayer(::showMain)
        }
        if (settings.dynamicIslandEnabled && islandVisible) Window(
            onCloseRequest = DesktopWindows::closeIsland,
            state = islandState,
            title = "Utaloom Dynamic Island",
            icon = icon,
            undecorated = true,
            transparent = true,
            resizable = false,
            alwaysOnTop = settings.dynamicIslandAlwaysOnTop,
        ) {
            LaunchedEffect(window) {
                if (window.background != java.awt.Color(0, 0, 0, 0)) {
                    window.background = java.awt.Color(0, 0, 0, 0)
                }
            }
            LaunchedEffect(window, islandState.size, settings.dynamicIslandMonitor, settings.dynamicIslandTopOffset) {
                javax.swing.SwingUtilities.invokeLater {
                    positionDynamicIsland(window, settings.dynamicIslandMonitor, settings.dynamicIslandTopOffset)
                }
            }
            UtaloomTheme(DefaultSeed, dark = true) {
                DynamicIsland(
                    onShowMain = ::showMain,
                    onClose = DesktopWindows::closeIsland,
                    onExpandedChange = { islandState.size = com.utaloom.ui.islandSize(it) },
                )
            }
        }
    }
}

private val isLinux = System.getProperty("os.name").orEmpty().lowercase().contains("linux")

/** Focused-window shortcuts. System-wide shortcuts are registered separately by GlobalHotkeys. */
private fun shortcut(e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val ctrl = e.isCtrlPressed || e.isMetaPressed
    when {
        e.key == Key.MediaPlayPause -> Player.playPause()
        e.key == Key.MediaNext -> Player.next()
        e.key == Key.MediaPrevious -> Player.previous()
        ctrl && e.key == Key.Spacebar -> Player.playPause()
        ctrl && e.key == Key.DirectionRight -> Player.next()
        ctrl && e.key == Key.DirectionLeft -> Player.previous()
        ctrl && e.key == Key.DirectionUp -> Player.setVolume(Player.state.value.volume + 5)
        ctrl && e.key == Key.DirectionDown -> Player.setVolume(Player.state.value.volume - 5)
        ctrl && e.key == Key.L -> Player.state.value.current?.let(com.utaloom.data.Library::toggleLike)
        e.isAltPressed && e.key == Key.DirectionLeft -> Nav.back()
        else -> return false
    }
    return true
}
