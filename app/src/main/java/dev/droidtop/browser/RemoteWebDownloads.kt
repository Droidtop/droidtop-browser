package dev.droidtop.browser

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import dev.droidtop.pluginhost.IWebViewSurfaceHost
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import mozilla.components.concept.fetch.Response
import org.json.JSONObject

internal interface WebDownloadSink {
    fun offer(token: String, metadata: JSONObject, response: Response, result: (String, Boolean) -> Unit): Boolean
    fun discard(token: String, offerId: String)
    fun cancelOffers(token: String)
    fun afterTransfers(token: String, cleanup: () -> Unit)
}

/** One-use, bounded body handoffs. Bodies never become paths, JSON, or replayed GET requests. */
internal class RemoteWebDownloads(private val host: IWebViewSurfaceHost, private val idle: () -> Unit) : WebDownloadSink {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32))
    private val offers = ConcurrentHashMap<String, Offer>()
    private val closed = AtomicBoolean()
    private val cleanups = mutableMapOf<String, MutableList<() -> Unit>>()

    private class Offer(val token: String, val response: Response, val result: (String, Boolean) -> Unit) {
        val acknowledged = AtomicBoolean()
        val claimed = AtomicBoolean()
        @Volatile var writer: ParcelFileDescriptor? = null
        @Volatile var progressed = SystemClock.uptimeMillis()
    }

    @Synchronized
    override fun offer(token: String, metadata: JSONObject, response: Response, result: (String, Boolean) -> Unit): Boolean {
        if (closed.get() || offers.size >= 32 || offers.values.count { it.token == token } >= 16) return false
        val id = UUID.randomUUID().toString()
        val offer = Offer(token, response, result)
        offers[id] = offer
        main.postDelayed({
            if (!offer.claimed.get() && offers[id] === offer) cancel(token, id, "Download offer expired")
        }, 60_000)
        return runCatching { host.download(token, id, metadata.toString()); true }.getOrElse {
            offers.remove(id, offer)
            response.close()
            false
        }
    }

    fun acknowledge(token: String, id: String, accepted: Boolean) {
        val offer = offers[id]?.takeIf { it.token == token } ?: return
        if (!offer.acknowledged.compareAndSet(false, true)) return
        if (!accepted) {
            offers.remove(id, offer)
            offer.response.close()
            drained(token)
        }
        main.post { offer.result(id, accepted) }
    }

    fun open(token: String, id: String): ParcelFileDescriptor? {
        val offer = offers[id]?.takeIf { it.token == token && it.acknowledged.get() } ?: return null
        if (closed.get() || !offer.claimed.compareAndSet(false, true)) return null
        val pipe = runCatching { ParcelFileDescriptor.createReliablePipe() }.getOrElse {
            cancel(token, id, "Download pipe could not open")
            return null
        }
        offer.writer = pipe[1]
        offer.progressed = SystemClock.uptimeMillis()
        watch(token, id, offer)
        main.postDelayed({ if (offers[id] === offer) cancel(token, id, "Download exceeded time limit") }, 60 * 60_000L)
        try { worker.execute {
            val output = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            try {
                offer.response.body.useStream { input ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        check(total <= MAX_BYTES) { "Download exceeded size limit" }
                        output.write(buffer, 0, count)
                        offer.progressed = SystemClock.uptimeMillis()
                    }
                }
                output.close()
            } catch (failure: Exception) {
                runCatching { pipe[1].closeWithError("Browser download failed") }
            } finally {
                runCatching { output.close() }
                offer.response.close()
                offers.remove(id, offer)
                drained(token)
            }
        } } catch (rejected: java.util.concurrent.RejectedExecutionException) {
            pipe[0].close()
            cancel(token, id, "Download worker unavailable")
            return null
        }
        return pipe[0]
    }

    private fun watch(token: String, id: String, offer: Offer) {
        main.postDelayed({
            if (offers[id] !== offer) return@postDelayed
            if (SystemClock.uptimeMillis() - offer.progressed >= 60_000) cancel(token, id, "Download stalled")
            else watch(token, id, offer)
        }, 60_000)
    }

    fun cancel(token: String, id: String, reason: String = "Download cancelled") {
        val offer = offers[id]?.takeIf { it.token == token } ?: return
        if (!offers.remove(id, offer)) return
        runCatching { offer.writer?.closeWithError(reason) }
        offer.response.close()
        drained(token)
        if (offer.acknowledged.compareAndSet(false, true)) main.post { offer.result(id, false) }
    }

    override fun discard(token: String, offerId: String) = cancel(token, offerId)

    override fun cancelOffers(token: String) {
        offers.entries.toList().filter { it.value.token == token }.forEach { (id, _) -> cancel(token, id) }
    }

    @Synchronized
    override fun afterTransfers(token: String, cleanup: () -> Unit) {
        if (offers.values.none { it.token == token }) main.post { cleanup() }
        else cleanups.getOrPut(token) { mutableListOf() }.add(cleanup)
    }

    private fun drained(token: String) {
        main.post {
            val callbacks = synchronized(this) {
                if (offers.values.any { it.token == token }) null else cleanups.remove(token)
            }
            callbacks?.forEach { it() }
            if (offers.isEmpty()) idle()
        }
    }

    fun isEmpty(): Boolean = offers.isEmpty()

    @Synchronized
    fun close() {
        closed.set(true)
        offers.entries.toList().forEach { (id, offer) -> cancel(offer.token, id) }
        worker.shutdownNow()
    }

    companion object { const val MAX_BYTES = 64L * 1024 * 1024 * 1024 }
}
