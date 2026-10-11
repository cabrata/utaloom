package com.utaloom.platform

import com.sun.jna.NativeLong
import com.sun.jna.platform.unix.X11
import com.utaloom.data.Stores
import com.utaloom.data.defaultGlobalHotkeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GlobalHotkeysTest {

    @Test
    fun testComboParser() {
        val p1 = GlobalHotkeys.parseCombo("Ctrl+Alt+Space")
        assertNotNull(p1)
        assertTrue(p1!!.ctrl)
        assertTrue(p1.alt)
        assertFalse(p1.shift)
        assertFalse(p1.meta)
        assertEquals("Space", p1.keyName)
        assertEquals(0x20, p1.winVk)
        assertEquals("space", p1.x11KeysymName)

        val p2 = GlobalHotkeys.parseCombo("Ctrl+Shift+Right")
        assertNotNull(p2)
        assertTrue(p2!!.ctrl)
        assertTrue(p2.shift)
        assertFalse(p2.alt)
        assertEquals("Right", p2.keyName)
        assertEquals(0x27, p2.winVk)
        assertEquals("Right", p2.x11KeysymName)

        val p3 = GlobalHotkeys.parseCombo("Super+Alt+U")
        assertNotNull(p3)
        assertTrue(p3!!.meta)
        assertTrue(p3.alt)
        assertEquals("U", p3.keyName)
        assertEquals(85, p3.winVk)
        assertEquals("u", p3.x11KeysymName)

        val pMedia = GlobalHotkeys.parseCombo("MediaPlayPause")
        assertNotNull(pMedia)
        assertEquals("MediaPlayPause", pMedia!!.keyName)
        assertEquals(0xB3, pMedia.winVk)
        assertEquals("XF86AudioPlay", pMedia.x11KeysymName)
    }

    @Test
    fun testComboNormalization() {
        assertEquals("Ctrl+Alt+Space", GlobalHotkeys.normalizeCombo("ctrl + alt + space"))
        assertEquals("Ctrl+Shift+Left", GlobalHotkeys.normalizeCombo(" shift + ctrl + left "))
        assertEquals("Ctrl+Alt+Super+M", GlobalHotkeys.normalizeCombo("Win+Ctrl+Alt+m"))
    }

    @Test
    fun testComboValidation() {
        val map = mapOf(
            "playPause" to "Ctrl+Alt+Space",
            "next" to "Ctrl+Alt+Right",
        )

        // Empty
        assertNotNull(GlobalHotkeys.validateCombo("playPause", "", map))
        assertNotNull(GlobalHotkeys.validateCombo("playPause", "   ", map))

        // Missing modifier
        assertNotNull(GlobalHotkeys.validateCombo("playPause", "Space", map))

        // Missing key
        assertNotNull(GlobalHotkeys.validateCombo("playPause", "Ctrl+Alt", map))

        // Invalid key
        assertNotNull(GlobalHotkeys.validateCombo("playPause", "Ctrl+Alt+InvalidKeyToken", map))

        // Valid edit of self
        assertNull(GlobalHotkeys.validateCombo("playPause", "Ctrl+Alt+Space", map))
        assertNull(GlobalHotkeys.validateCombo("playPause", "Ctrl+Alt+Down", map))

        // Conflict with another action
        val conflict = GlobalHotkeys.validateCombo("previous", "Ctrl+Alt+Space", map)
        assertNotNull(conflict)
        assertTrue("Expected conflict message", conflict!!.contains("Conflicts with Play / Pause"))
    }

    @Test
    fun testLifecycleAndStatusTransitions() {
        Stores.settings.update { it.copy(globalHotkeysEnabled = false) }
        var showCount = 0
        var miniCount = 0

        GlobalHotkeys.start(
            onShow = { showCount++ },
            onMiniPlayer = { miniCount++ },
        )

        assertFalse(GlobalHotkeys.status.value.enabled)
        assertFalse(GlobalHotkeys.status.value.active)

        // Verify dispatchAction executes without crashing
        GlobalHotkeys.dispatchAction("show")
        GlobalHotkeys.dispatchAction("miniPlayer")
        GlobalHotkeys.dispatchAction("playPause")

        GlobalHotkeys.stop()
        assertFalse(GlobalHotkeys.status.value.enabled)
    }

    @Test
    fun testNativeX11GrabAndConflictSafety() {
        val displayEnv = System.getenv("DISPLAY")
        assumeTrue("X11 test requires DISPLAY", displayEnv != null && displayEnv.isNotBlank())

        val x11 = X11.INSTANCE
        val display = x11.XOpenDisplay(null)
        assumeTrue("Could not open X11 display for testing", display != null)

        val lastError = AtomicInteger(0)
        val testHandler = object : X11.XErrorHandler {
            override fun apply(d: X11.Display?, err: X11.XErrorEvent?): Int {
                if (err != null) {
                    val ptr = err.pointer
                    val code = if (ptr != null) (ptr.getByte(32).toInt() and 0xFF) else (err.error_code.toInt() and 0xFF)
                    lastError.set(code)
                }
                return 0
            }
        }
        x11.XSetErrorHandler(testHandler)

        val root = x11.XDefaultRootWindow(display)
        val sym = x11.XStringToKeysym("space")
        val keycode = x11.XKeysymToKeycode(display, sym).toInt() and 0xFF
        val mod = X11.ControlMask or X11.Mod1Mask

        // Initial grab
        lastError.set(0)
        x11.XGrabKey(display, keycode, mod, root, 1, X11.GrabModeAsync, X11.GrabModeAsync)
        x11.XSync(display, false)
        assertEquals("Initial grab should succeed without X11 error", 0, lastError.get())

        // Conflicting grab on second connection
        val display2 = x11.XOpenDisplay(null)
        assertNotNull(display2)
        lastError.set(0)
        x11.XGrabKey(display2, keycode, mod, root, 1, X11.GrabModeAsync, X11.GrabModeAsync)
        x11.XSync(display2, false)
        assertEquals("Simulated grab conflict must catch BadAccess (10) without crashing", 10, lastError.get())

        // Ungrab and clean up
        x11.XUngrabKey(display, keycode, mod, root)
        x11.XSync(display, false)
        x11.XCloseDisplay(display2)
        x11.XCloseDisplay(display)
    }
}
