package dev.droidtop.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import org.json.JSONArray
import org.json.JSONObject

/**
 * droidtop's session in the private cookie store, through the built-in extension under assets/extensions/droidtop
 * (GeckoView has no cookie API): put in before a page opens, read back and taken out when it ends. Call on the main
 * thread; each answer comes back on the main thread.
 */
object SessionCookies {
    private const val ID = "droidtop-session@droidtop.dev"
    private const val URL = "resource://android/assets/extensions/droidtop/"
    private const val PORT = "droidtop"

    /** Gecko's private-browsing cookie store, where droidtop's pages run. */
    const val PRIVATE_STORE = "firefox-private"

    private var installing = false
    private var port: Port? = null
    private val waiting = mutableListOf<() -> Unit>()
    private val answers = ConcurrentHashMap<Int, (JSONObject?) -> Unit>()
    private val ids = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())

    /** How long the extension has to install and connect before the page is given back (it hung on the console). */
    private const val READY_MS = 10_000L

    /**
     * Runs [ready] once the extension is installed, allowed in private tabs and connected, or [failed] when that has
     * not happened in [READY_MS]. Each step says how it went in the log: on the console (2026-11-22) the extension
     * installed and the handler registered, but no port ever connected and the page waited forever with nothing said.
     */
    fun whenReady(engine: Engine, ready: () -> Unit, failed: () -> Unit) {
        if (port != null) return ready()
        waiting += ready
        main.postDelayed({
            if (waiting.remove(ready)) {
                Log.w(DroidtopInterfaceService.TAG, "the session extension did not connect in ${READY_MS / 1000} s")
                failed()
            }
        }, READY_MS)
        if (installing) return
        installing = true
        engine.installBuiltInWebExtension(
            id = ID,
            url = URL,
            onSuccess = { extension ->
                Log.i(DroidtopInterfaceService.TAG, "the session extension is installed")
                engine.setAllowedInPrivateBrowsing(
                    extension,
                    true,
                    onSuccess = { allowed ->
                        Log.i(DroidtopInterfaceService.TAG, "the session extension may run in private tabs; waiting for its port")
                        allowed.registerBackgroundMessageHandler(
                            PORT,
                            object : MessageHandler {
                                override fun onPortConnected(port: Port) {
                                    Log.i(DroidtopInterfaceService.TAG, "the session extension connected")
                                    main.post { connected(port) }
                                }

                                override fun onPortDisconnected(port: Port) {
                                    main.post { if (this@SessionCookies.port === port) this@SessionCookies.port = null }
                                }

                                override fun onPortMessage(message: Any, port: Port) {
                                    val reply = message as? JSONObject ?: runCatching { JSONObject(message.toString()) }.getOrNull() ?: return
                                    val answer = answers.remove(reply.optInt("id", -1)) ?: return
                                    main.post { answer(if (reply.optBoolean("ok")) reply.optJSONObject("result") ?: JSONObject() else null) }
                                }
                            },
                        )
                    },
                    onError = {
                        Log.w(DroidtopInterfaceService.TAG, "the session extension could not run in private tabs", it)
                        installing = false
                    },
                )
            },
            onError = {
                Log.w(DroidtopInterfaceService.TAG, "the session extension could not be installed", it)
                installing = false
            },
        )
    }

    private fun connected(given: Port) {
        port = given
        installing = false
        val ready = waiting.toList()
        waiting.clear()
        ready.forEach { it() }
    }

    /** Puts [cookies] (`{domain: "a=1; b=2"}`) into the private store; [done] is told whether it worked. */
    fun put(cookies: JSONObject, done: (Boolean) -> Unit) =
        send(JSONObject().put("op", "set").put("cookies", cookies)) { done(it != null) }

    /** The private store's cookies for [domains], as `{domain: header}`; empty when they could not be read. */
    fun read(domains: Collection<String>, done: (JSONObject) -> Unit) =
        send(JSONObject().put("op", "get").put("domains", JSONArray(domains.toList()))) { done(it ?: JSONObject()) }

    /** Takes [domains]' cookies out of the private store. */
    fun clear(domains: Collection<String>, done: () -> Unit = {}) =
        send(JSONObject().put("op", "clear").put("domains", JSONArray(domains.toList()))) { done() }

    private fun send(message: JSONObject, answer: (JSONObject?) -> Unit) {
        val current = port ?: return answer(null)
        val id = ids.incrementAndGet()
        answers[id] = answer
        runCatching { current.postMessage(message.put("id", id).put("storeId", PRIVATE_STORE)) }.onFailure {
            answers.remove(id)
            answer(null)
        }
    }
}
