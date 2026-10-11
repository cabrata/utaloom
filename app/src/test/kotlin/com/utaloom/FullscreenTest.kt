package com.utaloom

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import com.utaloom.data.PersistedQueue
import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.platform.NativeLibs
import com.utaloom.platform.DesktopWindows
import com.utaloom.playback.Player
import com.utaloom.ui.Nav
import com.utaloom.ui.Screen
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.InputEvent
import java.awt.event.WindowEvent
import javax.accessibility.Accessible
import javax.accessibility.AccessibleContext
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/** Run under an isolated display and D-Bus session: dbus-run-session -- xvfb-run -a ./gradlew --no-daemon :app:test --tests com.utaloom.FullscreenTest. */
class FullscreenTest {
    @Test
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun playerControlsAndKeyboardRestoreWindow() {
        assumeTrue("Needs a graphical display and VLC", !GraphicsEnvironment.isHeadless() && NativeLibs.discoverVlc() == null)
        // ponytail: Windows uses Compose's own fullscreen (as in 1.2.0), which keeps AWT's title bar there.
        val linux = System.getProperty("os.name").lowercase().contains("linux")
        val settings = Stores.settings.value
        val queue = Stores.queue.value
        Stores.settings.update { it.copy(minimizeToTray = false, visitorData = "fullscreen-test", lyricsDisabled = it.lyricsProviderOrder.toSet()) }
        Stores.queue.update { PersistedQueue(listOf(Song("screen00001", "Fullscreen test", listOf("Utaloom"))), title = "Fullscreen test") }
        Nav.root(Screen.Settings)
        val app = thread(name = "fullscreen-test-app", isDaemon = true) { runApp(exitProcessOnExit = false) }
        var window: ComposeWindow? = null
        try {
            await("Desktop window") { onEdt { Window.getWindows().filterIsInstance<ComposeWindow>().firstOrNull { it.isShowing }?.also { window = it } != null } }
            val w = checkNotNull(window)
            onEdt { w.renderImmediately() }
            Thread.sleep(500)
            val original = onEdt { w.bounds }
            val originalInsets = onEdt { w.insets }
            val originalDecoration = onEdt { w.isUndecorated }
            val paused = Player.state.value
            val artifacts = System.getenv("UTALOOM_WM_ARTIFACTS")?.let { java.io.File(it).also { dir -> dir.mkdirs() } }
            fun capture(name: String) {
                artifacts?.let { dir ->
                    val shot = Robot().createScreenCapture(onEdt { w.graphicsConfiguration.bounds })
                    javax.imageio.ImageIO.write(shot, "png", java.io.File(dir, "$name.png"))
                }
            }
            fun fillsDisplay() = onEdt {
                (if (linux) w.bounds == w.graphicsConfiguration.bounds && w.insets.top == 0 && w.insets.bottom == 0 else w.placement == WindowPlacement.Fullscreen) &&
                    find(w, "Exit full screen (Esc)") != null && find(w, "Settings") == null
            }
            capture("windowed")
            click(w, "Full screen (F11)")
            await("Player fills the display without titlebar", condition = ::fillsDisplay)
            onEdt { w.renderImmediately() }
            Thread.sleep(2000)
            check(Player.state.value == paused) { "Fullscreen must preserve paused playback and queue" }
            capture("fullscreen")
            println(onEdt { find(w, "Exit full screen (Esc)")?.accessibleComponent?.let { "Fullscreen exit control: ${it.locationOnScreen}, ${it.size}, visible=${it.isVisible}, showing=${it.isShowing}" } })
            println("PASS: native player covers display, window titlebar insets removed, paused playback unchanged")
            click(w, "Exit full screen (Esc)")
            await("Button restores floating window and decorations", stableMillis = 300) { onEdt { w.placement == WindowPlacement.Floating && w.bounds == original && w.insets == originalInsets && w.isUndecorated == originalDecoration && w.graphicsConfiguration.device.fullScreenWindow != w } }
            check(Player.state.value == paused) { "Exiting fullscreen must preserve paused playback and queue" }
            capture("restored")
            println("PASS: fullscreen button restores original window bounds and decorations")
            val robot = Robot()
            fun key(code: Int) {
                onEdt { w.toFront(); w.focusTraversalPolicy.getDefaultComponent(w)?.requestFocusInWindow() }
                val point = onEdt { w.locationOnScreen }
                robot.mouseMove(point.x + 100, point.y + 100)
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                robot.waitForIdle()
                await("Keyboard focus") { onEdt { w.isFocused && w.focusOwner is java.awt.Canvas } }
                robot.keyPress(code)
                robot.keyRelease(code)
                robot.waitForIdle()
            }
            key(KeyEvent.VK_F11)
            await("F11 enters fullscreen", condition = ::fillsDisplay)
            key(KeyEvent.VK_F11)
            await("F11 exits fullscreen", stableMillis = 300) { onEdt { w.placement == WindowPlacement.Floating && w.bounds == original && w.insets == originalInsets } }
            key(KeyEvent.VK_F11)
            await("Re-enter with F11", condition = ::fillsDisplay)
            key(KeyEvent.VK_ESCAPE)
            await("Escape restores floating window", stableMillis = 300) { onEdt { w.placement == WindowPlacement.Floating && w.bounds == original && w.insets == originalInsets } }
            println("PASS: repeated F11 toggles and Escape restore window")
            key(KeyEvent.VK_F11)
            await("Enter for rapid toggle", condition = ::fillsDisplay)
            key(KeyEvent.VK_ESCAPE)
            await("Exit for rapid toggle") { onEdt { w.placement == WindowPlacement.Floating } }
            key(KeyEvent.VK_F11)
            await("Re-enter before restore finishes", condition = ::fillsDisplay)
            key(KeyEvent.VK_ESCAPE)
            await("Rapid toggle restores original bounds", stableMillis = 300) { onEdt { w.placement == WindowPlacement.Floating && w.bounds == original && w.insets == originalInsets } }
            println("PASS: rapid re-entry cancels old restore without saving transient bounds")
            click(w, "Full screen (F11)")
            await("Re-enter fullscreen", condition = ::fillsDisplay)
            click(w, "Close player")
            await("Close player exits fullscreen", stableMillis = 300) { onEdt { w.placement == WindowPlacement.Floating && w.bounds == original && w.insets == originalInsets } }
            await("Player overlay is closed") { onEdt { find(w, "Close player") == null } }
            println("PASS: Close player removes overlay and leaves fullscreen")
            if (java.awt.Toolkit.getDefaultToolkit().isFrameStateSupported(java.awt.Frame.MAXIMIZED_BOTH)) {
                onEdt { w.placement = WindowPlacement.Maximized }
                await("Maximized window", stableMillis = 300) { onEdt { w.placement == WindowPlacement.Maximized && w.insets == originalInsets } }
                val maximized = onEdt { w.bounds }
                key(KeyEvent.VK_F11)
                await("Fullscreen from maximized", condition = ::fillsDisplay)
                key(KeyEvent.VK_ESCAPE)
                await("Restore maximized placement", stableMillis = 300) { onEdt { w.placement == WindowPlacement.Maximized && w.insets == originalInsets && w.graphicsConfiguration.device.fullScreenWindow != w } }
                key(KeyEvent.VK_F11)
                await("Enter for maximized rapid toggle", condition = ::fillsDisplay)
                key(KeyEvent.VK_ESCAPE)
                await("Exit for maximized rapid toggle") { onEdt { w.placement == WindowPlacement.Maximized } }
                key(KeyEvent.VK_F11)
                await("Re-enter before maximized restore finishes", condition = ::fillsDisplay)
                key(KeyEvent.VK_ESCAPE)
                await("Rapid toggle restores maximized window", stableMillis = 300) { onEdt { w.placement == WindowPlacement.Maximized && w.insets == originalInsets } }
                onEdt { w.placement = WindowPlacement.Floating }
                await("Return to floating after maximized", stableMillis = 300) { onEdt { w.placement == WindowPlacement.Floating && w.insets == originalInsets } }
                println("PASS: fullscreen and rapid re-entry restore maximized placement and decorations")
            }
            onEdt { w.setSize(900, 600) }
            await("Minimum window bounds settle", stableMillis = 300) { onEdt { w.width == 900 && w.height == 600 && w.insets == originalInsets } }
            await("Fullscreen button at minimum window size") {
                onEdt {
                    find(w, "Full screen (F11)")?.accessibleComponent?.let {
                        it.size.width > 0 && it.size.height > 0 && w.bounds.contains(java.awt.Rectangle(it.locationOnScreen, it.size))
                    } == true
                }
            }
            val small = onEdt { w.bounds }
            val target = onEdt { checkNotNull(find(w, "Full screen (F11)")?.accessibleComponent).let { java.awt.Rectangle(it.locationOnScreen, it.size) } }
            robot.mouseMove(target.x + target.width / 2, target.y + target.height / 2)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            robot.waitForIdle()
            await("Enter before stopping", condition = ::fillsDisplay)
            println("PASS: fully onscreen fullscreen control accepts real mouse click at 900x600")
            Player.stop()
            await("Stopping restores window", stableMillis = 300) { onEdt { w.placement == WindowPlacement.Floating && w.bounds == small && w.insets == originalInsets } }
            key(KeyEvent.VK_F11)
            check(onEdt { w.placement == WindowPlacement.Floating }) { "No current song must not enter fullscreen" }
            println("PASS: stopping exits fullscreen; no current song cannot enter it")
            val stopped = Player.state.value
            DesktopWindows.showMini()
            var mini: ComposeWindow? = null
            await("Mini player window") { onEdt {
                Window.getWindows().filterIsInstance<ComposeWindow>().firstOrNull { it.isShowing && it.title == "Utaloom Mini player" }?.also { mini = it } != null
            } }
            val smallPlayer = checkNotNull(mini)
            onEdt { smallPlayer.renderImmediately() }
            await("Mini player is always on top") { onEdt { smallPlayer.isAlwaysOnTop } }
            click(smallPlayer, "Always on top")
            await("Pin toggles native always-on-top") { onEdt { !smallPlayer.isAlwaysOnTop } }
            check(!Stores.settings.value.miniPlayerAlwaysOnTop)
            click(smallPlayer, "Always on top")
            await("Pin restores native always-on-top") { onEdt { smallPlayer.isAlwaysOnTop } }
            click(smallPlayer, "Show Utaloom")
            check(Player.state.value == stopped) { "Mini player visibility/pin changes must not change playback or the queue" }
            onEdt { smallPlayer.dispatchEvent(WindowEvent(smallPlayer, WindowEvent.WINDOW_CLOSING)) }
            await("Closing mini leaves main window alive") { onEdt { !smallPlayer.isShowing && w.isShowing } }
            check(Player.state.value == stopped) { "Closing the mini player must not stop or reset playback" }
            println("PASS: mini window opens, pin changes native placement, show-main works and close preserves shared player")
        } catch (e: Exception) {
            System.getenv("UTALOOM_WM_ARTIFACTS")?.let { dir -> runCatching { javax.imageio.ImageIO.write(Robot().createScreenCapture(java.awt.Rectangle(java.awt.Toolkit.getDefaultToolkit().screenSize)), "png", java.io.File(dir, "failure.png")) } }
            println(onEdt { window?.let { "placement=${it.placement}, bounds=${it.bounds}, focused=${it.isFocused}, focusOwner=${it.focusOwner}, insets=${it.insets}, client=${clientSize(it)}" } })
            throw e
        } finally {
            window?.let { w -> onEdt { w.dispatchEvent(WindowEvent(w, WindowEvent.WINDOW_CLOSING)) } }
            app.join(5000)
            Stores.settings.update { settings }
            Stores.queue.update { queue }
            Stores.flushAll()
        }
    }

    private fun click(window: ComposeWindow, name: String) {
        await(name) { onEdt { find(window, name)?.accessibleAction != null } }
        check(onEdt { find(window, name)?.accessibleAction?.doAccessibleAction(0) == true }) { "Click failed: $name" }
    }

    private fun clientSize(w: ComposeWindow) = java.awt.Dimension(
        w.bounds.width - w.insets.left - w.insets.right,
        w.bounds.height - w.insets.top - w.insets.bottom,
    )

    private fun find(node: Accessible, name: String): AccessibleContext? {
        val ctx = node.accessibleContext ?: return null
        if (ctx.accessibleName == name && ctx.accessibleAction != null) return ctx
        return (0 until ctx.accessibleChildrenCount).firstNotNullOfOrNull { i -> ctx.getAccessibleChild(i)?.let { find(it, name) } }
    }

    private fun await(label: String, stableMillis: Long = 0, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        var since = 0L
        while (true) {
            if (condition()) {
                if (since == 0L) since = System.nanoTime()
                if (System.nanoTime() - since >= stableMillis * 1_000_000) return
            } else since = 0L
            check(System.nanoTime() < deadline) { "Timed out: $label" }
            Thread.sleep(50)
        }
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
