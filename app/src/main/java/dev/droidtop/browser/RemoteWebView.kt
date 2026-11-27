package dev.droidtop.browser

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.ContextThemeWrapper
import android.view.SurfaceControlViewHost
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import dev.droidtop.pluginhost.IWebViewSurfaceHost
import dev.droidtop.pluginhost.IWebViewSurfaceProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.mozilla.fenix.ext.components

/** Real browser-owned view hierarchy; Binder transfers only its SurfacePackage and a scoped session protocol. */
@RequiresApi(30)
internal class RemoteWebView(
    private val context: Context,
    private val pluginId: String,
    private val host: IWebViewSurfaceHost,
    private val bodyEnabled: Boolean,
) : IWebViewSurfaceProvider.Stub() {
    private val main = Handler(Looper.getMainLooper())
    private val pages = mutableMapOf<String, Page>()
    private var disconnected = false
    private val downloads = RemoteWebDownloads(host)
    private val death = IBinder.DeathRecipient { main.post { disconnect() } }

    private class Page(val container: SurfaceControlViewHost, val root: FrameLayout, var densityDpi: Int) {
        var controller: WebSessionView? = null
        var live = true
    }

    fun connect() {
        host.asBinder().linkToDeath(death, 0)
        if (bodyEnabled) host.connectedCapabilities(this, arrayOf("web.download.body.v1", "web.browse.isolated.v1"))
        else host.connected(this)
    }

    private fun caller(): Boolean = DroidtopLink.isDroidtop(context, Binder.getCallingUid()) &&
        DroidtopLink.hasPlugin(pluginId)

    override fun open(token: String?, hostToken: IBinder?, displayId: Int, width: Int, height: Int, densityDpi: Int) {
        if (!caller() || token.isNullOrBlank() || token.length > 256 || hostToken == null) return
        main.post {
            if (disconnected || pages.containsKey(token)) return@post
            if (!validSize(width, height, densityDpi)) {
                unavailable(token, "invalid_size")
                return@post
            }
            // The cookie store is shared by Gecko private tabs. Do not allow a second remote session to overwrite
            // an active remote session's cookies; the host can retry or use its fallback before taking this token.
            if (pages.isNotEmpty()) {
                unavailable(token, "session_busy")
                return@post
            }
            runCatching {
                val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
                    ?: error("no_display")
                val config = configuration(width, height, densityDpi)
                val windowContext = context.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null)
                    .createConfigurationContext(config)
                val themed = ContextThemeWrapper(windowContext, android.R.style.Theme_Material_NoActionBar)
                val container = SurfaceControlViewHost(themed, display, hostToken)
                val page = Page(container, FrameLayout(themed), densityDpi)
                pages[token] = page
                container.setView(page.root, width, height)
                page.controller = WebSessionView(
                    themed, context.components.core.engine,
                    display = { view ->
                        if (page.live) {
                            page.root.removeAllViews()
                            page.root.addView(view, FrameLayout.LayoutParams(-1, -1))
                        }
                    },
                    closed = { release(token) },
                    alive = { page.live && !disconnected },
                    event = { event -> runCatching { host.event(token, event.toString()) } },
                    bodyDownloads = downloads.takeIf { bodyEnabled },
                )
                host.surfaceReady(token)
                page.controller?.open(token)
            }.onFailure {
                release(token)
                unavailable(token, "surface_open_failed")
            }
        }
    }

    /** Called off the host's UI thread. A bounded UI handoff prevents a blocked browser from stranding the host. */
    override fun surface(token: String?): SurfaceControlViewHost.SurfacePackage? {
        if (!caller() || token == null) return null
        if (Looper.myLooper() == Looper.getMainLooper()) return pages[token]?.container?.surfacePackage
        val ready = CountDownLatch(1)
        var result: SurfaceControlViewHost.SurfacePackage? = null
        val gate = Any()
        var expired = false
        main.post {
            synchronized(gate) {
                if (!expired) result = pages[token]?.container?.surfacePackage
            }
            ready.countDown()
        }
        if (!ready.await(2, TimeUnit.SECONDS)) synchronized(gate) {
            expired = true
            result?.release()
            result = null
        }
        return result
    }

    override fun resize(token: String?, width: Int, height: Int, densityDpi: Int) {
        if (!caller() || token == null || !validSize(width, height, densityDpi)) return
        main.post {
            pages[token]?.let { page ->
                // API 30–32 have no SurfacePackage.notifyConfigurationChanged. The resources belong to this
                // configuration context, not the application's resources; keep this hierarchy's density current.
                @Suppress("DEPRECATION")
                page.root.context.resources.updateConfiguration(configuration(width, height, densityDpi), null)
                page.densityDpi = densityDpi
                page.root.dispatchConfigurationChanged(configuration(width, height, densityDpi))
                page.container.relayout(width, height)
            }
        }
    }

    override fun back(token: String?) {
        if (!caller() || token == null) return
        main.post { pages[token]?.controller?.back() }
    }

    override fun close(token: String?) {
        if (!caller() || token == null) return
        main.post { pages[token]?.controller?.cancel() }
    }

    override fun download(token: String?, offerId: String?): ParcelFileDescriptor? =
        if (caller() && token != null && offerId != null) downloads.open(token, offerId) else null

    override fun cancelDownload(token: String?, offerId: String?) {
        if (caller() && token != null && offerId != null) downloads.cancel(token, offerId)
    }

    override fun offerResult(token: String?, offerId: String?, accepted: Boolean) {
        if (caller() && token != null && offerId != null) downloads.acknowledge(token, offerId, accepted)
    }

    fun disconnect() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { disconnect() }
            return
        }
        disconnected = true
        downloads.close()
        pages.keys.toList().forEach(::release)
        runCatching { host.asBinder().unlinkToDeath(death, 0) }
    }

    private fun release(token: String) {
        val page = pages.remove(token) ?: return
        page.live = false
        page.controller?.destroy()
        page.controller = null
        page.container.release()
    }

    private fun unavailable(token: String, reason: String) {
        runCatching { host.unavailable(token, reason) }
    }

    private fun validSize(width: Int, height: Int, densityDpi: Int): Boolean =
        width in 1..16384 && height in 1..16384 && densityDpi in 72..960

    private fun configuration(width: Int, height: Int, densityDpi: Int) =
        Configuration(context.resources.configuration).apply {
            this.densityDpi = densityDpi
            screenWidthDp = (width * 160 / densityDpi).coerceAtLeast(1)
            screenHeightDp = (height * 160 / densityDpi).coerceAtLeast(1)
            smallestScreenWidthDp = minOf(screenWidthDp, screenHeightDp)
            orientation = if (width > height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        }

}
