package com.utaloom.platform

import com.utaloom.data.Paths
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.friwi.jcefmaven.CefAppBuilder
import org.cef.CefApp
import org.cef.CefSettings
import org.cef.callback.CefCookieVisitor
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager

/** Cookies visible to the embedded browser, e.g. "sp_dc=...; sp_t=...". */
typealias CookieJar = Map<String, String>

object WebLogin {
    /** Human-readable setup progress (first-run download, startup), null when idle. */
    val status = MutableStateFlow<String?>(null)

    // Installers bundle Chromium as app resources; dev runs and other platforms fetch it (~150 MB) on first use.
    private val bundled = System.getProperty("compose.application.resources.dir")
        ?.let { java.io.File(it, "jcef") }?.takeIf { it.resolve("install.lock").exists() }

    /**
     * Where Chromium runs from. Windows uses the bundle in place. deb/rpm/AppImage installs are read-only and
     * jpackage drops exec bits, so the bundle is copied once (a local copy, no download) into user data.
     */
    private fun installDir(dir: java.io.File): java.io.File {
        val source = bundled ?: return dir
        if ("win" in System.getProperty("os.name").lowercase()) return source
        val meta = source.resolve("build_meta.json").readText()
        if (dir.resolve("install.lock").exists() && dir.resolve("build_meta.json").takeIf { it.exists() }?.readText() == meta) return dir
        status.value = "Preparing browser…"
        dir.resolve("install.lock").delete()
        source.walkTopDown().filter { it.name != "install.lock" }.forEach { f ->
            val target = dir.resolve(f.relativeTo(source))
            if (f.isDirectory) target.mkdirs() else f.copyTo(target, overwrite = true)
        }
        listOf("jcef_helper", "chrome-sandbox").forEach { dir.resolve(it).setExecutable(true, false) }
        // Last, so an interrupted copy is redone next time instead of looking installed.
        dir.resolve("install.lock").createNewFile()
        return dir
    }

    private val app: CefApp by lazy {
        CefAppBuilder().apply {
            // Cache and log always live in user data: the install dir may be read-only (e.g. /opt).
            val dir = Paths.dir.resolve("jcef")
            setInstallDir(installDir(dir))
            // Chromium builds otherwise open a blank "terms of service" first-run dialog over the login window.
            addJcefArgs("--no-first-run", "--no-default-browser-check")
            // No cache_path: every login starts from a clean, in-memory session.
            // Own root dir so CEF never shares or locks the default %LOCALAPPDATA%\CEF profile of other CEF apps.
            cefSettings.root_cache_path = dir.resolve("root").absolutePath
            // Packaged builds have no console, so keep Chromium's errors in a file users can send.
            cefSettings.log_file = dir.resolve("debug.log").absolutePath
            cefSettings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING
            cefSettings.windowless_rendering_enabled = false
            setProgressHandler { state, percent ->
                status.value = "Preparing browser: ${state.name.lowercase().replace('_', ' ')}" + if (percent >= 0) " ${percent.toInt()}%" else ""
            }
        }.build()
    }

    /**
     * Cookies whose domain covers [url]'s host, so another site's cookie of the same name never leaks in.
     * visitUrlCookies is not used: in this JCEF it drops httpOnly cookies such as sp_dc and SID.
     */
    private fun readCookies(url: String): CookieJar {
        val host = java.net.URI(url).host
        val jar = java.util.concurrent.ConcurrentHashMap<String, String>()
        val done = java.util.concurrent.CountDownLatch(1)
        CefCookieManager.getGlobalManager().visitAllCookies(CefCookieVisitor { c, count, total, _ ->
            if (cookieDomainMatches(c.domain, host)) jar[c.name] = c.value
            if (count + 1 >= total) done.countDown()
            true
        }).also { if (!it) return emptyMap() }
        // No callback at all when the store is empty.
        return if (done.await(3, java.util.concurrent.TimeUnit.SECONDS)) HashMap(jar) else emptyMap()
    }

    internal fun cookieDomainMatches(domain: String, host: String): Boolean {
        val d = domain.removePrefix(".").lowercase()
        return host.lowercase().let { it == d || it.endsWith(".$d") }
    }

    /**
     * Opens [url] in a browser window until [accept] returns a result from the cookies sent to [cookieUrl],
     * or the user closes it.
     */
    suspend fun <T : Any> run(title: String, url: String, cookieUrl: String, accept: (CookieJar) -> T?): T? = try {
        // createClient runs CefInitialize on first use; off the EDT a slow or stuck start can't freeze the app.
        val client = withContext(Dispatchers.IO) {
            app.also { status.value = "Starting browser…" }.createClient().also {
                // The cookie store lives for the whole process: drop the last login so "Sign in again" really asks.
                CefCookieManager.getGlobalManager().deleteCookies("", "")
            }
        }
        suspendCancellableCoroutine { cont ->
            SwingUtilities.invokeLater {
              try {
                val browser = client.createBrowser(url, false, false)
                val frame = JFrame(title)
                var timer: Timer? = null
                var closed = false
                fun finish(result: T?) {
                    if (closed) return
                    closed = true
                    timer?.stop()
                    if (cont.isActive) cont.resume(result)
                    frame.dispose()
                    browser.close(true)
                    client.dispose()
                }
                frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
                frame.addWindowListener(object : WindowAdapter() { override fun windowClosing(e: WindowEvent) = finish(null) })
                frame.add(browser.uiComponent, BorderLayout.CENTER)
                frame.preferredSize = Dimension(480, 760)
                frame.pack()
                frame.setLocationRelativeTo(null)
                frame.isVisible = true
                // Windows may open a window started from a background task behind the main window.
                frame.toFront()
                status.value = null
                val polling = java.util.concurrent.atomic.AtomicBoolean()
                timer = Timer(1500) {
                    if (polling.compareAndSet(false, true)) Thread {
                        try { accept(readCookies(cookieUrl))?.let { r -> SwingUtilities.invokeLater { finish(r) } } } finally { polling.set(false) }
                    }.start()
                }.also { it.start() }
                cont.invokeOnCancellation { SwingUtilities.invokeLater { finish(null) } }
              } catch (e: Throwable) {
                client.dispose()
                if (cont.isActive) cont.resumeWithException(e)
              }
            }
        }
    } finally { status.value = null }
}
