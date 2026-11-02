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

    // The Chromium runtime (~100 MB) is fetched on first use, so it never bloats the installer.
    private val app: CefApp by lazy {
        CefAppBuilder().apply {
            val dir = Paths.dir.resolve("jcef")
            setInstallDir(dir)
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

    private fun readCookies(): CookieJar {
        val jar = HashMap<String, String>()
        val done = java.util.concurrent.CountDownLatch(1)
        CefCookieManager.getGlobalManager().visitAllCookies(CefCookieVisitor { c, count, total, _ ->
            jar[c.name] = c.value
            if (count + 1 >= total) done.countDown()
            true
        }).also { if (!it) return jar }
        done.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return jar
    }

    /** Opens [url] in a browser window until [accept] returns a result from the cookies, or the user closes it. */
    suspend fun <T : Any> run(title: String, url: String, accept: (CookieJar) -> T?): T? = try {
        // createClient runs CefInitialize on first use; off the EDT a slow or stuck start can't freeze the app.
        val client = withContext(Dispatchers.IO) { app.also { status.value = "Starting browser…" }.createClient() }
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
                timer = Timer(1500) {
                    Thread { accept(readCookies())?.let { r -> SwingUtilities.invokeLater { finish(r) } } }.start()
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
