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

/** Main-process forwarding only; the hidden engine and its profile never live here. */
internal class HiddenEngineConnection(
    private val context: Context,
    private val pluginId: String,
    private val host: IBinder,
    private val disposed: (HiddenEngineConnection) -> Unit,
) : ServiceConnection {
    private val main = Handler(Looper.getMainLooper())
    private val lease = Binder()
    private var remote: Messenger? = null
    private var bound = false
    private var closed = false

    fun connect() = main.post {
        if (closed) return@post
        bound = context.bindService(Intent().setClassName(context, "dev.droidtop.browser.HiddenEngineService"), this, Context.BIND_AUTO_CREATE)
        if (!bound) close()
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        if (closed) return
        remote = Messenger(binder)
        runCatching {
            remote?.send(Message.obtain(null, ATTACH).apply {
                data = Bundle().apply { putString("pluginId", pluginId); putBinder("host", host); putBinder("lease", lease) }
            })
        }.onFailure { close() }
    }

    override fun onServiceDisconnected(name: ComponentName) { close() }
    override fun onBindingDied(name: ComponentName) { close() }
    override fun onNullBinding(name: ComponentName) { close() }

    fun close() = main.post {
        if (closed) return@post
        closed = true
        runCatching { remote?.send(Message.obtain(null, DETACH).apply { data = Bundle().apply { putBinder("lease", lease) } }) }
        remote = null
        if (bound) { bound = false; runCatching { context.unbindService(this) } }
        disposed(this)
    }

    companion object { const val RELAY = "web.hidden.v1"; const val ATTACH = 1; const val DETACH = 2 }
}
