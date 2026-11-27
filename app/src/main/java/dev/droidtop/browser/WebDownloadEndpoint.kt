package dev.droidtop.browser

import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import dev.droidtop.pluginhost.IWebDownloadHost
import dev.droidtop.pluginhost.IWebDownloadProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import mozilla.components.concept.fetch.Response
import org.json.JSONObject

/** API26 body-only transport. Deliberately no dependency on the embedded-view interfaces or view parcelables. */
internal class WebDownloadEndpoint(
    private val context: Context,
    private val pluginId: String,
    private val host: IWebDownloadHost,
    private val disposed: (WebDownloadEndpoint) -> Unit,
) : IWebDownloadProvider.Stub() {
    private val main = Handler(Looper.getMainLooper())
    private val disconnected = AtomicBoolean()
    private val downloads = RemoteWebDownloads(
        emit = { token, id, metadata -> host.download(token, id, metadata) },
        idle = {}, // The verified runtime owns this reusable endpoint, including between OPEN activities.
    )
    private val death = IBinder.DeathRecipient { disconnect() }

    fun connect() {
        host.asBinder().linkToDeath(death, 0)
        current[pluginId] = this
        host.connected(this, arrayOf(RELAY))
    }

    private fun caller(): Boolean = !disconnected.get() && DroidtopLink.hasPlugin(pluginId) &&
        DroidtopLink.isDroidtop(context, Binder.getCallingUid())

    override fun download(token: String?, offerId: String?): ParcelFileDescriptor? =
        if (caller() && token != null && offerId != null) downloads.open(token, offerId) else null

    override fun cancelDownload(token: String?, offerId: String?) {
        if (caller() && token != null && offerId != null) downloads.cancel(token, offerId)
    }

    override fun offerResult(token: String?, offerId: String?, accepted: Boolean) {
        if (caller() && token != null && offerId != null) downloads.acknowledge(token, offerId, accepted)
    }

    fun disconnect() {
        if (!disconnected.compareAndSet(false, true)) return
        current.remove(pluginId, this)
        main.post { downloads.close() }
        runCatching { host.asBinder().unlinkToDeath(death, 0) }
        disposed(this)
    }

    companion object {
        const val RELAY = "web.download.body.v1"
        private val current = ConcurrentHashMap<String, WebDownloadEndpoint>()

        /** Resolve after the activity's broker handshake; retain the endpoint that owns any accepted leases. */
        fun activitySink(): WebDownloadSink = object : WebDownloadSink {
            private var selected: WebDownloadSink? = null
            private fun endpoint(): WebDownloadSink? = selected ?: DroidtopLink.loadedPluginId()?.let { id ->
                current[id]?.takeUnless { it.disconnected.get() }?.downloads
            }
            override fun available(): Boolean = endpoint()?.available() == true
            override fun offer(token: String, metadata: JSONObject, response: Response, result: (String, Boolean) -> Unit): Boolean {
                val target = endpoint() ?: return false
                selected = target
                return target.offer(token, metadata, response, result)
            }
            override fun discard(token: String, offerId: String) { selected?.discard(token, offerId) }
            override fun cancelOffers(token: String) { selected?.cancelOffers(token) }
            override fun afterTransfers(token: String, cleanup: () -> Unit) {
                val target = selected
                if (target == null) cleanup() else target.afterTransfers(token, cleanup)
            }
        }
    }
}
