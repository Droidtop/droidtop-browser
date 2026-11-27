package dev.droidtop.browser

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import dev.droidtop.pluginhost.IPluginHostBroker
import dev.droidtop.pluginhost.IPluginRuntime
import dev.droidtop.pluginhost.IPluginRuntimeCallback
import dev.droidtop.pluginhost.IWebViewSurfaceHost
import dev.droidtop.pluginhost.IWebDownloadHost
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * droidtop Browser as one of droidtop's plugins: an `app_interface` plugin that provides `web.view` (droidtop
 * docs/plugin-api.md 3 G3a). droidtop binds this with [ACTION] and hands over its broker; the pages themselves are
 * started by droidtop as [WebViewActivity], which talks back through that broker. Only droidtop is answered.
 */
class DroidtopInterfaceService : Service() {
    private val runtimes = CopyOnWriteArrayList<Runtime>()
    override fun onBind(intent: Intent): IBinder? = if (intent.action == ACTION) Runtime(applicationContext).also {
        runtimes += it
    } else null

    override fun onDestroy() {
        // Unbinding a verified host does not necessarily call unloadPlugin, and the ordinary browser can keep
        // this process alive. Remove static body endpoint references when this service's bindings are gone.
        runtimes.toList().forEach { it.dispose() }
        runtimes.clear()
        DroidtopLink.loadedPluginId()?.let(DroidtopLink::disconnected)
        super.onDestroy()
    }

    private class Runtime(private val context: Context) : IPluginRuntime.Stub() {
        private val remotes = CopyOnWriteArrayList<RemoteWebView>()
        private val bodyEndpoints = CopyOnWriteArrayList<WebDownloadEndpoint>()
        fun dispose() {
            remotes.toList().forEach { it.disconnect() }
            remotes.clear()
            bodyEndpoints.toList().forEach { it.disconnect() }
            bodyEndpoints.clear()
        }
        private fun caller(): Boolean = DroidtopLink.isDroidtop(context, Binder.getCallingUid()).also {
            if (!it) Log.w(TAG, "refused a call from uid ${Binder.getCallingUid()}: not droidtop")
        }

        override fun registerCallback(callback: IPluginRuntimeCallback?) {}
        override fun unregisterCallback(callback: IPluginRuntimeCallback?) {}
        override fun loadPlugin(pluginId: String?, installDir: String?, entryClass: String?, rootApproved: Boolean, broker: IPluginHostBroker?) = false

        override fun loadContained(
            pluginId: String?,
            manifestJson: String?,
            files: Array<out ParcelFileDescriptor>?,
            names: Array<out String>?,
            broker: IPluginHostBroker?,
        ): Boolean {
            files?.forEach { runCatching { it.close() } }
            if (!caller() || pluginId == null || broker == null) return false
            DroidtopLink.connected(pluginId, broker)
            return true
        }

        override fun reachability(): String? = null
        override fun attachScreen(
            pluginId: String?, entrypoint: String?, library: String?, surface: Surface?,
            frames: ParcelFileDescriptor?, framesCapacity: Long, width: Int, height: Int, density: Float,
        ): String? = null
        override fun resizeScreen(pluginId: String?, width: Int, height: Int) {}
        override fun inputToken(): Long = 0L
        override fun screenTouch(token: Long, pluginId: String?, event: MotionEvent?) {}
        override fun screenKey(token: Long, pluginId: String?, event: KeyEvent?) {}
        override fun detachScreen(pluginId: String?) {}
        override fun lastLoadError(pluginId: String?): String? = null

        override fun unloadPlugin(pluginId: String?) {
            if (caller() && pluginId != null) {
                dispose()
                DroidtopLink.disconnected(pluginId)
            }
        }

        override fun invoke(pluginId: String?, capability: String?, argsJson: String?): String =
            error("UNSUPPORTED", "droidtop Browser answers only through handle")

        // droidtop starts the browser's pages itself (WebViewActivity); there is no point call to answer.
        override fun handle(pluginId: String?, envelopeJson: String?): String =
            if (!caller()) error("PERMISSION_DENIED", "Only droidtop may call droidtop Browser here")
            else error("UNSUPPORTED", "droidtop Browser shows pages droidtop starts; it answers no calls")

        override fun startJob(pluginId: String?, capability: String?, argsJson: String?, jobId: String?) = false
        override fun cancelJob(pluginId: String?, jobId: String?) {}
        override fun attachRelay(pluginId: String?, name: String?, relay: IBinder?) {
            if (!caller() || pluginId == null || !DroidtopLink.hasPlugin(pluginId) || relay == null) return
            if (name == WebDownloadEndpoint.RELAY) {
                if (bodyEndpoints.size >= 16) return
                WebDownloadEndpoint(context, pluginId, IWebDownloadHost.Stub.asInterface(relay),
                    disposed = { bodyEndpoints.remove(it) }).also {
                    bodyEndpoints += it
                    runCatching { it.connect() }.onFailure { _ -> it.disconnect() }
                }
                return
            }
            if (name !in setOf("web.view.surface.v1", "web.view.surface.v2") || Build.VERSION.SDK_INT < 30) return
            if (remotes.size >= 16) return
            // Each host owns its own relay. Starting another page must not revoke a prior accepted body lease.
            RemoteWebView(context, pluginId, IWebViewSurfaceHost.Stub.asInterface(relay), name == "web.view.surface.v2",
                disposed = { remotes.remove(it) }).also {
                remotes += it
                runCatching { it.connect() }.onFailure { _ -> it.disconnect() }
            }
        }
    }

    companion object {
        const val ACTION = "dev.droidtop.plugin.APP_INTERFACE"
        internal const val TAG = "DroidtopBrowser"

        private fun error(code: String, message: String): String =
            JSONObject().put("ok", false).put("error", JSONObject().put("code", code).put("message", message)).toString()
    }
}
