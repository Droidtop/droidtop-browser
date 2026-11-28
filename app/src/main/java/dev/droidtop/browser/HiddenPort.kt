package dev.droidtop.browser

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

/** Owned-runtime protocol only. No caller scripts, selectors or arbitrary broker operations. */
internal class HiddenPort(
    private val ready: HiddenRuntimeBootstrap.Ready,
    private val authorize: (contextId: String, url: String, kind: String) -> Boolean,
    private val closed: () -> Unit,
) : WebExtension.PortDelegate {
    private val main = Handler(Looper.getMainLooper())
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64))
    private val pending = mutableMapOf<String, (JSONObject?) -> Unit>()
    private var nextId = 0L
    private var alive = true

    init { check(Looper.myLooper() == Looper.getMainLooper()); ready.port.setDelegate(this) }

    /** Every request completes once, on main, including timeout or disconnect. */
    fun request(message: JSONObject, done: (JSONObject?) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!alive || pending.size >= 64 || message.optString("op") !in setOf("register", "unregister", "snapshot", "cookies")) {
            done(null)
            return
        }
        val id = (++nextId).toString()
        pending[id] = done
        main.postDelayed({ pending.remove(id)?.invoke(null) }, 3_000)
        runCatching { ready.port.postMessage(message.put("id", id)) }
            .onFailure { pending.remove(id)?.invoke(null); close() }
    }

    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        if (!alive || port !== ready.port) return
        val json = message as? JSONObject ?: return close()
        if (json.toString().length > 1_048_576) return close()
        if (json.optString("op") == "authorize") {
            val requestId = json.optString("id")
            val contextId = json.optString("contextId")
            val url = json.optString("url")
            val kind = json.optString("kind")
            if (requestId.isEmpty() || requestId.length > 32 || contextId.length > 64 || url.length > 4096 || kind !in setOf("document", "frame", "resource")) return close()
            try {
                workers.execute {
                    val allowed = runCatching { authorize(contextId, url, kind) }.getOrDefault(false)
                    main.post {
                        if (alive) runCatching { ready.port.postMessage(JSONObject(mapOf("op" to "authorize", "requestId" to requestId, "allowed" to allowed))) }
                            .onFailure { close() }
                    }
                }
            } catch (busy: java.util.concurrent.RejectedExecutionException) {
                runCatching { ready.port.postMessage(JSONObject(mapOf("op" to "authorize", "requestId" to requestId, "allowed" to false))) }
                    .onFailure { close() }
            }
            return
        }
        val callback = pending.remove(json.optString("id")) ?: return
        callback(if (json.optBoolean("ok")) json.optJSONObject("result") else null)
    }

    override fun onDisconnect(port: WebExtension.Port) { if (port === ready.port) close() }

    fun close() {
        if (!alive) return
        alive = false
        workers.shutdownNow()
        val callbacks = pending.values.toList()
        pending.clear()
        HiddenRuntimeBootstrap.invalidate(ready)
        callbacks.forEach { it(null) }
        closed()
    }
}
