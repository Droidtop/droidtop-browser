package dev.droidtop.browser

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process

/** Main-process forwarding only; the hidden engine and its profile never live here. */
internal class HiddenEngineConnection(
    private val context: Context,
    val pluginId: String,
    private val disposed: (HiddenEngineConnection) -> Unit,
) : ServiceConnection {
    private val main = Handler(Looper.getMainLooper())
    private val control = Binder()
    private class Entry(val host: IBinder, var sent: Boolean = false, var expiry: Runnable? = null)
    private val leases = linkedMapOf<IBinder, Entry>()
    private var remote: Messenger? = null
    private var bound = false
    private var closed = false
    private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.sendingUid == Process.myUid() && message.what == CLOSED) {
                message.data.getBinder("lease")?.let(::forget)
            }
        }
    })

    fun attach(host: IBinder) = main.post {
        if (closed || leases.values.any { it.host == host } || leases.size >= 16) return@post
        val lease = Binder()
        val entry = Entry(host)
        leases[lease] = entry
        entry.expiry = Runnable {
            if (leases[lease] !== entry) return@Runnable
            forget(lease)
            runCatching { remote?.send(Message.obtain(null, DETACH).apply {
                data = Bundle().apply { putBinder("control", control); putBinder("lease", lease) }
            }) }
        }.also { main.postDelayed(it, 30_000) }
        if (remote != null) send(lease, entry)
        else if (!bound) {
            bound = context.bindService(Intent().setClassName(context, "dev.droidtop.browser.HiddenEngineService"), this, Context.BIND_AUTO_CREATE)
            if (!bound) close()
        }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        if (closed) return
        remote = Messenger(binder)
        leases.toList().forEach { (lease, entry) -> send(lease, entry) }
    }

    private fun send(lease: IBinder, entry: Entry) {
        if (closed || entry.sent) return
        runCatching {
            remote?.send(Message.obtain(null, ATTACH).apply {
                data = Bundle().apply {
                    putString("pluginId", pluginId); putBinder("host", entry.host)
                    putBinder("lease", lease); putBinder("control", control)
                }
                replyTo = replies
            })
            entry.sent = true
        }.onFailure { close() }
    }

    override fun onServiceDisconnected(name: ComponentName) { close() }
    override fun onBindingDied(name: ComponentName) { close() }
    override fun onNullBinding(name: ComponentName) { close() }

    private fun forget(lease: IBinder) {
        leases.remove(lease)?.expiry?.let(main::removeCallbacks)
    }

    fun close() = main.post {
        if (closed) return@post
        closed = true
        runCatching { remote?.send(Message.obtain(null, DETACH).apply { data = Bundle().apply { putBinder("control", control) } }) }
        leases.keys.toList().forEach(::forget)
        remote = null
        if (bound) { bound = false; runCatching { context.unbindService(this) } }
        disposed(this)
    }

    companion object { const val RELAY = "web.hidden.v1"; const val ATTACH = 1; const val DETACH = 2; const val CLOSED = 3 }
}
