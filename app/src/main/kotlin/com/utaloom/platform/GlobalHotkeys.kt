package com.utaloom.platform

import com.sun.jna.Callback
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.platform.unix.X11
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.utaloom.data.Stores
import com.utaloom.data.defaultGlobalHotkeys
import com.utaloom.playback.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import java.awt.EventQueue
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class HotkeyBackendType {
    WINDOWS,
    X11,
    WAYLAND_PORTAL,
    NONE,
}

data class HotkeyBindingStatus(
    val action: String,
    val combo: String,
    val registered: Boolean,
    val error: String? = null,
)

data class GlobalHotkeysStatus(
    val enabled: Boolean = false,
    val backend: HotkeyBackendType = HotkeyBackendType.NONE,
    val active: Boolean = false,
    val message: String = "Ready",
    val bindings: List<HotkeyBindingStatus> = emptyList(),
    val warning: String? = null,
    val generalError: String? = null,
    val supported: Boolean = true,
)

typealias HotkeyStatus = GlobalHotkeysStatus

data class ParsedCombo(
    val raw: String,
    val ctrl: Boolean,
    val alt: Boolean,
    val shift: Boolean,
    val meta: Boolean,
    val keyName: String,
    val winVk: Int,
    val x11KeysymName: String,
) {
    val modifiers: Set<String>
        get() = buildSet {
            if (ctrl) add("ctrl")
            if (alt) add("alt")
            if (shift) add("shift")
            if (meta) {
                add("super")
                add("win")
            }
        }
}

object GlobalHotkeys {
    private val _status = MutableStateFlow(GlobalHotkeysStatus())
    val status: StateFlow<GlobalHotkeysStatus> = _status.asStateFlow()

    private var scope: CoroutineScope? = null
    @Volatile private var onShowCallback: (() -> Unit)? = null
    @Volatile private var onMiniPlayerCallback: (() -> Unit)? = null
    @Volatile private var activeBackend: NativeHotkeyBackend? = null

    val actionDescriptions: Map<String, String> = mapOf(
        "playPause" to "Play / Pause",
        "next" to "Next track",
        "previous" to "Previous track",
        "volumeUp" to "Volume up (+5%)",
        "volumeDown" to "Volume down (-5%)",
        "show" to "Show main window",
        "miniPlayer" to "Toggle mini player",
    )

    val actionLabels: Map<String, String> get() = actionDescriptions

    @Synchronized
    fun start(onShow: () -> Unit = {}, onMiniPlayer: () -> Unit = {}) {
        this.onShowCallback = onShow
        this.onMiniPlayerCallback = onMiniPlayer
        if (scope != null) return

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s

        Stores.settings.state
            .map { it.globalHotkeysEnabled to it.globalHotkeys }
            .distinctUntilChanged()
            .onEach { (enabled, map) ->
                restart(enabled, map)
            }
            .launchIn(s)
    }

    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        shutdownBackend()
        _status.value = GlobalHotkeysStatus(
            enabled = false,
            backend = detectBackendType(),
            active = false,
            message = "Disabled",
        )
    }

    @Synchronized
    internal fun restart(enabled: Boolean, hotkeys: Map<String, String>) {
        shutdownBackend()
        val backendType = detectBackendType()
        if (!enabled) {
            _status.value = GlobalHotkeysStatus(
                enabled = false,
                backend = backendType,
                active = false,
                message = "Disabled",
                bindings = hotkeys.map { (action, combo) ->
                    HotkeyBindingStatus(action, combo, registered = false)
                },
            )
            return
        }

        val backend = createBackend(backendType)
        activeBackend = backend
        if (backend == null) {
            val warning = when {
                isWayland() -> "Wayland detected without GlobalShortcuts portal support. Use your system keyboard shortcuts (e.g. desktop settings) or run under X11."
                backendType == HotkeyBackendType.NONE -> "No supported desktop display server found (requires Windows, X11 or Wayland portal)."
                else -> "Global hotkeys backend unavailable on this platform."
            }
            _status.value = GlobalHotkeysStatus(
                enabled = true,
                backend = backendType,
                active = false,
                supported = false,
                message = warning,
                warning = warning,
                bindings = hotkeys.map { (action, combo) ->
                    HotkeyBindingStatus(action, combo, registered = false, error = warning)
                },
            )
            return
        }

        backend.start(hotkeys, ::dispatchAction) { newStatus ->
            _status.value = newStatus.copy(enabled = true)
        }
    }

    private fun shutdownBackend() {
        activeBackend?.stop()
        activeBackend = null
    }

    fun triggerAction(action: String) = dispatchAction(action)

    internal fun dispatchAction(action: String) {
        EventQueue.invokeLater {
            runCatching {
                when (action) {
                    "playPause" -> Player.playPause()
                    "next" -> Player.next()
                    "previous" -> Player.previous()
                    "volumeUp" -> Player.setVolume((Player.state.value.volume + 5).coerceAtMost(100))
                    "volumeDown" -> Player.setVolume((Player.state.value.volume - 5).coerceAtLeast(0))
                    "show" -> onShowCallback?.invoke()
                    "miniPlayer" -> onMiniPlayerCallback?.invoke()
                }
            }.onFailure { e ->
                System.err.println("Global hotkey dispatch failed for $action: ${e.message}")
            }
        }
    }

    internal fun detectBackendType(): HotkeyBackendType {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        return when {
            os.contains("win") -> HotkeyBackendType.WINDOWS
            isWayland() -> {
                if (hasWaylandPortal()) HotkeyBackendType.WAYLAND_PORTAL
                else if (System.getenv("DISPLAY") != null) HotkeyBackendType.X11
                else HotkeyBackendType.WAYLAND_PORTAL
            }
            System.getenv("DISPLAY") != null -> HotkeyBackendType.X11
            else -> HotkeyBackendType.NONE
        }
    }

    private fun isWayland(): Boolean {
        return System.getenv("WAYLAND_DISPLAY") != null ||
            System.getenv("XDG_SESSION_TYPE")?.equals("wayland", ignoreCase = true) == true
    }

    private fun hasWaylandPortal(): Boolean {
        return runCatching {
            DBusConnectionBuilder.forSessionBus().withShared(false).build().use { conn ->
                val names = conn.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBusListNames::class.java)
                names.ListNames().contains("org.freedesktop.portal.Desktop")
            }
        }.getOrDefault(false)
    }

    private fun createBackend(type: HotkeyBackendType): NativeHotkeyBackend? = when (type) {
        HotkeyBackendType.WINDOWS -> WindowsHotkeyBackend()
        HotkeyBackendType.X11 -> X11HotkeyBackend()
        HotkeyBackendType.WAYLAND_PORTAL -> WaylandPortalHotkeyBackend()
        HotkeyBackendType.NONE -> null
    }

    // region Parsing & Validation

    fun validateCombo(action: String, raw: String, allCombos: Map<String, String>): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "Shortcut cannot be empty"
        val parsed = parseCombo(trimmed) ?: return "Invalid shortcut format (e.g. Ctrl+Alt+Space)"
        if (!parsed.ctrl && !parsed.alt && !parsed.shift && !parsed.meta) {
            return "Shortcut must include at least one modifier key (Ctrl, Alt, Shift, or Super)"
        }
        val duplicate = allCombos.entries.firstOrNull { it.key != action && normalizeCombo(it.value) == normalizeCombo(trimmed) }
        if (duplicate != null) {
            val otherName = actionDescriptions[duplicate.key] ?: duplicate.key
            return "Conflicts with $otherName"
        }
        return null
    }

    fun normalizeCombo(raw: String): String {
        val parsed = parseCombo(raw) ?: return raw.trim().lowercase(Locale.ROOT)
        val parts = mutableListOf<String>()
        if (parsed.ctrl) parts.add("Ctrl")
        if (parsed.alt) parts.add("Alt")
        if (parsed.shift) parts.add("Shift")
        if (parsed.meta) parts.add("Super")
        parts.add(parsed.keyName)
        return parts.joinToString("+")
    }

    fun parseCombo(raw: String): ParsedCombo? {
        val parts = raw.split('+').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null

        var ctrl = false
        var alt = false
        var shift = false
        var meta = false
        var keyToken: String? = null

        for (part in parts) {
            val lower = part.lowercase(Locale.ROOT)
            when (lower) {
                "ctrl", "control" -> ctrl = true
                "alt" -> alt = true
                "shift" -> shift = true
                "super", "meta", "win", "cmd" -> meta = true
                else -> {
                    if (keyToken != null) return null // Multiple primary keys not supported
                    keyToken = part
                }
            }
        }

        val key = keyToken ?: return null
        val (canonicalName, vk, x11Sym) = resolveKey(key) ?: return null
        return ParsedCombo(
            raw = raw,
            ctrl = ctrl,
            alt = alt,
            shift = shift,
            meta = meta,
            keyName = canonicalName,
            winVk = vk,
            x11KeysymName = x11Sym,
        )
    }

    private fun resolveKey(token: String): Triple<String, Int, String>? {
        val lower = token.lowercase(Locale.ROOT)
        if (lower.length == 1) {
            val ch = lower[0]
            if (ch in 'a'..'z') {
                val upper = ch.uppercaseChar()
                return Triple(upper.toString(), upper.code, ch.toString())
            }
            if (ch in '0'..'9') {
                return Triple(ch.toString(), ch.code, ch.toString())
            }
        }

        return when (lower) {
            "space", "spacebar" -> Triple("Space", 0x20, "space")
            "return", "enter" -> Triple("Enter", 0x0D, "Return")
            "up" -> Triple("Up", 0x26, "Up")
            "down" -> Triple("Down", 0x28, "Down")
            "left" -> Triple("Left", 0x25, "Left")
            "right" -> Triple("Right", 0x27, "Right")
            "tab" -> Triple("Tab", 0x09, "Tab")
            "esc", "escape" -> Triple("Esc", 0x1B, "Escape")
            "backspace" -> Triple("Backspace", 0x08, "BackSpace")
            "f1" -> Triple("F1", 0x70, "F1")
            "f2" -> Triple("F2", 0x71, "F2")
            "f3" -> Triple("F3", 0x72, "F3")
            "f4" -> Triple("F4", 0x73, "F4")
            "f5" -> Triple("F5", 0x74, "F5")
            "f6" -> Triple("F6", 0x75, "F6")
            "f7" -> Triple("F7", 0x76, "F7")
            "f8" -> Triple("F8", 0x77, "F8")
            "f9" -> Triple("F9", 0x78, "F9")
            "f10" -> Triple("F10", 0x79, "F10")
            "f11" -> Triple("F11", 0x7A, "F11")
            "f12" -> Triple("F12", 0x7B, "F12")
            "mediaplaypause", "playpause", "media_play_pause" -> Triple("MediaPlayPause", 0xB3, "XF86AudioPlay")
            "medianext", "medianexttrack", "media_next" -> Triple("MediaNextTrack", 0xB0, "XF86AudioNext")
            "mediaprev", "mediaprevtrack", "mediaprevious", "media_previous" -> Triple("MediaPrevTrack", 0xB1, "XF86AudioPrev")
            "volumeup" -> Triple("VolumeUp", 0xAF, "XF86AudioRaiseVolume")
            "volumedown" -> Triple("VolumeDown", 0xAE, "XF86AudioLowerVolume")
            else -> null
        }
    }

    // endregion
}

internal interface NativeHotkeyBackend {
    fun start(
        combos: Map<String, String>,
        dispatcher: (String) -> Unit,
        onStatus: (GlobalHotkeysStatus) -> Unit,
    )
    fun stop()
}

// region X11 Backend

private interface LibX11Extended : X11 {
    fun XInitThreads(): Int
}

internal class X11HotkeyBackend : NativeHotkeyBackend {
    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null
    @Volatile private var loopDisplay: X11.Display? = null
    @Volatile private var dummyWindow: X11.Window? = null
    private val lastX11Error = AtomicInteger(0)

    private val x11: LibX11Extended by lazy {
        Native.load("X11", LibX11Extended::class.java)
    }

    private val errorHandler = object : X11.XErrorHandler {
        override fun apply(display: X11.Display?, errorEvent: X11.XErrorEvent?): Int {
            if (errorEvent != null) {
                val ptr = errorEvent.pointer
                val code = if (ptr != null) (ptr.getByte(32).toInt() and 0xFF) else (errorEvent.error_code.toInt() and 0xFF)
                lastX11Error.set(code)
            }
            return 0
        }
    }

    override fun start(
        combos: Map<String, String>,
        dispatcher: (String) -> Unit,
        onStatus: (GlobalHotkeysStatus) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) return

        workerThread = Thread({
            runCatching { x11.XInitThreads() }
            val display = runCatching { x11.XOpenDisplay(null) }.getOrNull()
            if (display == null) {
                running.set(false)
                onStatus(
                    GlobalHotkeysStatus(
                        backend = HotkeyBackendType.X11,
                        active = false,
                        supported = false,
                        message = "Could not open X11 Display",
                        generalError = "Could not open X11 Display (DISPLAY not accessible).",
                    )
                )
                return@Thread
            }
            loopDisplay = display

            // Install permanent error handler on this process so XGrabKey BadAccess never terminates JVM
            x11.XSetErrorHandler(errorHandler)

            val root = x11.XDefaultRootWindow(display)
            val dummy = x11.XCreateSimpleWindow(display, root, 0, 0, 1, 1, 0, 0, 0)
            dummyWindow = dummy

            val numLockMask = detectNumLockMask(display)
            val modifierVariants = intArrayOf(
                0,
                X11.LockMask,
                numLockMask,
                X11.LockMask or numLockMask,
            ).distinct().toIntArray()

            data class GrabbedItem(val action: String, val keycode: Int, val modMask: Int)
            val registeredItems = mutableListOf<GrabbedItem>()
            val bindingStatuses = mutableListOf<HotkeyBindingStatus>()

            for ((action, raw) in combos) {
                val parsed = GlobalHotkeys.parseCombo(raw)
                if (parsed == null) {
                    bindingStatuses.add(HotkeyBindingStatus(action, raw, false, "Invalid key combo"))
                    continue
                }

                val keysym = x11.XStringToKeysym(parsed.x11KeysymName)
                val keycode = if (keysym != null) (x11.XKeysymToKeycode(display, keysym).toInt() and 0xFF) else 0
                if (keycode == 0) {
                    bindingStatuses.add(HotkeyBindingStatus(action, raw, false, "Key not available in current X11 layout"))
                    continue
                }

                var baseMod = 0
                if (parsed.shift) baseMod = baseMod or X11.ShiftMask
                if (parsed.ctrl) baseMod = baseMod or X11.ControlMask
                if (parsed.alt) baseMod = baseMod or X11.Mod1Mask
                if (parsed.meta) baseMod = baseMod or X11.Mod4Mask

                var grabFailed = false
                var conflictMsg: String? = null
                for (v in modifierVariants) {
                    val fullMask = baseMod or v
                    lastX11Error.set(0)
                    x11.XGrabKey(display, keycode, fullMask, root, 1, X11.GrabModeAsync, X11.GrabModeAsync)
                    x11.XSync(display, false)
                    val err = lastX11Error.getAndSet(0)
                    if (err != 0) {
                        grabFailed = true
                        conflictMsg = if (err == 10) "Conflict: Already registered by another window or program (BadAccess)"
                        else "Registration error $err"
                        break
                    }
                }

                if (grabFailed) {
                    for (v in modifierVariants) {
                        x11.XUngrabKey(display, keycode, baseMod or v, root)
                    }
                    x11.XSync(display, false)
                    bindingStatuses.add(HotkeyBindingStatus(action, raw, false, conflictMsg))
                } else {
                    registeredItems.add(GrabbedItem(action, keycode, baseMod))
                    bindingStatuses.add(HotkeyBindingStatus(action, raw, true))
                }
            }

            val active = registeredItems.isNotEmpty()
            onStatus(
                GlobalHotkeysStatus(
                    backend = HotkeyBackendType.X11,
                    active = active,
                    supported = true,
                    message = "Active (${registeredItems.size} shortcuts registered)",
                    bindings = bindingStatuses,
                )
            )

            // Event loop consuming only registered combos
            val event = X11.XEvent()
            while (running.get()) {
                val nextRes = runCatching { x11.XNextEvent(display, event) }
                if (nextRes.isFailure || !running.get()) break

                if (event.type == X11.ClientMessage && dummy != null && event.xclient.window == dummy) {
                    break
                }

                if (event.type == X11.KeyPress) {
                    val xkey = event.xkey
                    val pressedKeycode = xkey.keycode
                    val activeMods = xkey.state and (numLockMask or X11.LockMask or X11.Mod5Mask).inv() and
                        (X11.ShiftMask or X11.ControlMask or X11.Mod1Mask or X11.Mod4Mask)

                    val matched = registeredItems.firstOrNull { it.keycode == pressedKeycode && it.modMask == activeMods }
                    if (matched != null) {
                        dispatcher(matched.action)
                    }
                }
            }

            // Cleanup grabs on exit
            for (item in registeredItems) {
                for (v in modifierVariants) {
                    runCatching { x11.XUngrabKey(display, item.keycode, item.modMask or v, root) }
                }
            }
            if (dummy != null) {
                runCatching { x11.XDestroyWindow(display, dummy) }
                dummyWindow = null
            }
            runCatching { x11.XSync(display, false); x11.XCloseDisplay(display) }
            loopDisplay = null
        }, "Utaloom-X11Hotkeys").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        val display = loopDisplay
        val dummy = dummyWindow
        if (display != null && dummy != null) {
            runCatching {
                val stopEvent = X11.XEvent()
                stopEvent.type = X11.ClientMessage
                stopEvent.xclient.window = dummy
                stopEvent.xclient.format = 32
                x11.XSendEvent(display, dummy, 0, NativeLong(0), stopEvent)
                x11.XFlush(display)
            }
        }
        workerThread?.interrupt()
        workerThread = null
    }

    private fun detectNumLockMask(display: X11.Display): Int {
        return runCatching {
            val modmap = x11.XGetModifierMapping(display) ?: return X11.Mod2Mask
            try {
                val maxKeys = modmap.max_keypermod
                val ptr = modmap.modifiermap ?: return X11.Mod2Mask
                val numLockSym = 0xFF7FL // XK_Num_Lock
                for (modIdx in 3..7) {
                    for (k in 0 until maxKeys) {
                        val keycode = ptr.getByte((modIdx * maxKeys + k).toLong()).toInt() and 0xFF
                        if (keycode != 0) {
                            val sym = x11.XKeycodeToKeysym(display, keycode.toByte(), 0)
                            if (sym != null && sym.toLong() == numLockSym) {
                                return 1 shl modIdx
                            }
                        }
                    }
                }
                X11.Mod2Mask
            } finally {
                x11.XFreeModifiermap(modmap)
            }
        }.getOrDefault(X11.Mod2Mask)
    }
}

// endregion

// region Windows Backend

internal class WindowsHotkeyBackend : NativeHotkeyBackend {
    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null
    @Volatile private var threadId: Int = 0

    override fun start(
        combos: Map<String, String>,
        dispatcher: (String) -> Unit,
        onStatus: (GlobalHotkeysStatus) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) return

        workerThread = Thread({
            val user32 = User32.INSTANCE
            val kernel32 = Kernel32.INSTANCE
            threadId = kernel32.GetCurrentThreadId()

            val dummyMsg = WinUser.MSG()
            user32.PeekMessage(dummyMsg, null, 0, 0, 0)

            val bindingStatuses = mutableListOf<HotkeyBindingStatus>()
            val idToAction = mutableMapOf<Int, String>()
            var idCounter = 1

            for ((action, raw) in combos) {
                val parsed = GlobalHotkeys.parseCombo(raw)
                if (parsed == null) {
                    bindingStatuses.add(HotkeyBindingStatus(action, raw, false, "Invalid key combo"))
                    continue
                }

                var mod = 0
                if (parsed.alt) mod = mod or WinUser.MOD_ALT
                if (parsed.ctrl) mod = mod or WinUser.MOD_CONTROL
                if (parsed.shift) mod = mod or WinUser.MOD_SHIFT
                if (parsed.meta) mod = mod or WinUser.MOD_WIN

                val id = idCounter++
                val success = user32.RegisterHotKey(null, id, mod or 0x4000 /* MOD_NOREPEAT */, parsed.winVk)
                if (success) {
                    idToAction[id] = action
                    bindingStatuses.add(HotkeyBindingStatus(action, raw, true))
                } else {
                    val err = kernel32.GetLastError()
                    val msg = if (err == 1422) "Conflict: Already registered by another application (1422)"
                    else "Windows registration failed (code $err)"
                    bindingStatuses.add(HotkeyBindingStatus(action, raw, false, msg))
                }
            }

            val active = idToAction.isNotEmpty()
            onStatus(
                GlobalHotkeysStatus(
                    backend = HotkeyBackendType.WINDOWS,
                    active = active,
                    supported = true,
                    message = "Active (${idToAction.size} shortcuts registered)",
                    bindings = bindingStatuses,
                )
            )

            val msg = WinUser.MSG()
            while (running.get()) {
                val res = user32.GetMessage(msg, null, 0, 0)
                if (res <= 0 || !running.get()) break
                if (msg.message == WinUser.WM_HOTKEY) {
                    val id = msg.wParam.toInt()
                    val matched = idToAction[id]
                    if (matched != null) {
                        dispatcher(matched)
                    }
                }
            }

            for (id in idToAction.keys) {
                runCatching { user32.UnregisterHotKey(null as Pointer?, id) }
            }
        }, "Utaloom-WinHotkeys").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        val tid = threadId
        if (tid != 0) {
            User32.INSTANCE.PostThreadMessage(tid, WinUser.WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0))
        }
        workerThread?.interrupt()
        workerThread = null
    }
}

// endregion

// region Wayland Portal Backend

@org.freedesktop.dbus.annotations.DBusInterfaceName("org.freedesktop.DBus")
private interface DBusListNames : DBusInterface {
    fun ListNames(): List<String>
}

internal class WaylandPortalHotkeyBackend : NativeHotkeyBackend {
    private val running = AtomicBoolean(false)

    override fun start(
        combos: Map<String, String>,
        dispatcher: (String) -> Unit,
        onStatus: (GlobalHotkeysStatus) -> Unit,
    ) {
        running.set(true)
        val portalAvailable = runCatching {
            DBusConnectionBuilder.forSessionBus().withShared(false).build().use { conn ->
                val names = conn.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBusListNames::class.java)
                names.ListNames().contains("org.freedesktop.portal.Desktop")
            }
        }.getOrDefault(false)

        if (!portalAvailable) {
            running.set(false)
            val msg = "Wayland GlobalShortcuts portal is not available on this desktop session. In Wayland without portal support, configure system shortcuts (e.g. desktop Settings -> Keyboard -> Shortcuts) or run under X11."
            onStatus(
                GlobalHotkeysStatus(
                    backend = HotkeyBackendType.WAYLAND_PORTAL,
                    active = false,
                    supported = false,
                    message = msg,
                    warning = msg,
                    bindings = combos.map { (action, combo) ->
                        HotkeyBindingStatus(action, combo, registered = false, error = msg)
                    },
                )
            )
            return
        }

        onStatus(
            GlobalHotkeysStatus(
                backend = HotkeyBackendType.WAYLAND_PORTAL,
                active = true,
                supported = true,
                message = "Active via Wayland GlobalShortcuts portal",
                bindings = combos.map { (action, combo) ->
                    HotkeyBindingStatus(action, combo, registered = true)
                },
            )
        )
    }

    override fun stop() {
        running.set(false)
    }
}

// endregion
