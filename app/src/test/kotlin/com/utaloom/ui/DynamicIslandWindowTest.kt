package com.utaloom.ui

import androidx.compose.ui.awt.ComposeWindow
import com.utaloom.data.LibraryState
import com.utaloom.data.PersistedQueue
import com.utaloom.data.Settings
import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.platform.NativeLibs
import com.utaloom.runApp
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.event.WindowEvent
import javax.accessibility.Accessible
import javax.accessibility.AccessibleContext
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/** Actual rendered-window acceptance check. Run under Xvfb on Linux. */
class DynamicIslandWindowTest {
    @Test
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun compactIslandExpandsAndItsControlsWork() {
        assumeTrue(
            "Needs a Linux graphical display and VLC",
            System.getProperty("os.name").lowercase().contains("linux") && !GraphicsEnvironment.isHeadless() && NativeLibs.discoverVlc() == null,
        )
        val oldSettings = Stores.settings.value
        val oldQueue = Stores.queue.value
        val oldLibrary = Stores.library.value
        val song = Song("island-test", "Island acceptance", listOf("Utaloom"))
        Stores.settings.update {
            Settings(
                visitorData = "island-test",
                minimizeToTray = false,
                dynamicIslandEnabled = true,
                dynamicIslandAlwaysOnTop = true,
                lyricsDisabled = it.lyricsProviderOrder.toSet(),
            )
        }
        Stores.queue.update { PersistedQueue(listOf(song), 0, title = "Island acceptance") }
        Stores.library.update { LibraryState() }
        com.utaloom.platform.DesktopWindows.showIsland()
        val app = thread(name = "dynamic-island-test-app", isDaemon = true) { runApp(exitProcessOnExit = false) }
        var main: ComposeWindow? = null
        var island: ComposeWindow? = null
        try {
            await("main and island windows") {
                onEdt {
                    val shown = Window.getWindows().filterIsInstance<ComposeWindow>().filter { it.isShowing }
                    main = shown.firstOrNull { it.title == "Utaloom" || it.title.endsWith(" - Utaloom") }
                    island = shown.firstOrNull { it.title == "Utaloom Dynamic Island" }
                    main != null && island != null
                }
            }
            val overlay = checkNotNull(island)
            onEdt { overlay.renderImmediately() }
            await("compact 304x64 window", stableMillis = 250) { onEdt { overlay.size.width == 304 && overlay.size.height == 64 } }
            check(onEdt { overlay.isAlwaysOnTop && !overlay.isResizable && overlay.isUndecorated })
            check(onEdt { overlay.x == (overlay.graphicsConfiguration.bounds.width - overlay.width) / 2 })

            click(overlay, "Dynamic Island. Island acceptance by Utaloom, Paused")
            await("expanded 640x340 window", stableMillis = 250) { onEdt { overlay.size.width == 640 && overlay.size.height == 340 } }
            click(overlay, "Dynamic Island. Island acceptance by Utaloom, Paused")
            await("collapsed 304x64 window", stableMillis = 250) { onEdt { overlay.size.width == 304 && overlay.size.height == 64 } }
            click(overlay, "Dynamic Island. Island acceptance by Utaloom, Paused")
            await("re-expanded 640x340 window", stableMillis = 250) { onEdt { overlay.size.width == 640 && overlay.size.height == 340 } }
            listOf("Like", "Previous", "Play", "Open Utaloom", "Hide Dynamic Island").forEach { name ->
                await("expanded control $name") { onEdt { find(overlay, name)?.accessibleAction != null } }
            }
            click(overlay, "Like")
            await("like changes real library state") { Stores.library.value.liked.any { it.id == song.id } }
            click(overlay, "Open Utaloom")
            await("open main makes it visible") { onEdt { checkNotNull(main).isShowing } }
            click(overlay, "Hide Dynamic Island")
            await("hide closes overlay only") { onEdt { !overlay.isShowing && checkNotNull(main).isShowing } }
            println("PASS: rendered island is top-centered 304x64, expands to 640x340, exposes controls, likes, opens main, and closes independently")
        } finally {
            onEdt { Window.getWindows().filterIsInstance<ComposeWindow>().filter { it.isShowing }.forEach { it.dispatchEvent(WindowEvent(it, WindowEvent.WINDOW_CLOSING)) } }
            app.join(5000)
            Stores.settings.update { oldSettings }
            Stores.queue.update { oldQueue }
            Stores.library.update { oldLibrary }
            Stores.flushAll()
            com.utaloom.platform.DesktopWindows.showIsland()
        }
    }

    private fun click(window: ComposeWindow, name: String) {
        await(name) { onEdt { find(window, name)?.accessibleAction != null } }
        check(onEdt { find(window, name)?.accessibleAction?.doAccessibleAction(0) == true }) { "Click failed: $name" }
    }

    private fun find(node: Accessible, name: String): AccessibleContext? {
        val context = node.accessibleContext ?: return null
        if (context.accessibleName == name && context.accessibleAction != null) return context
        return (0 until context.accessibleChildrenCount).firstNotNullOfOrNull { i ->
            context.getAccessibleChild(i)?.let { find(it, name) }
        }
    }

    private fun await(label: String, stableMillis: Long = 0, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
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
