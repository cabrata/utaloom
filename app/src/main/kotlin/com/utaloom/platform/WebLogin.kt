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
            setInstallDir(Paths.dir.resolve("jcef"))
            // No cache_path: every login starts from a clean, in-memory session.
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
    suspend fun <T : Any> run(title: String, url: String, accept: (CookieJar) -> T?): T? {
        val cef = try { withContext(Dispatchers.IO) { app } } finally { status.value = null }
        return suspendCancellableCoroutine { cont ->
            SwingUtilities.invokeLater {
              try {
                val client = cef.createClient()
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
                timer = Timer(1500) {
                    Thread { accept(readCookies())?.let { r -> SwingUtilities.invokeLater { finish(r) } } }.start()
                }.also { it.start() }
                cont.invokeOnCancellation { SwingUtilities.invokeLater { finish(null) } }
              } catch (e: Throwable) {
                if (cont.isActive) cont.resumeWithException(e)
              }
            }
        }
    }
}
