package dev.droidtop.browser

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Bundle
import android.os.Messenger
import android.os.Process
import dev.droidtop.pluginhost.IWebHiddenHost

/** Only Browser's verified main-process adapter can bind/forward an approved host to this process. */
class HiddenEngineService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val leases = mutableMapOf<IBinder, Lease>()
    private lateinit var engine: HiddenDocumentEngine
    private class Lease(val owner: HiddenDocumentEngine.Owner, val death: IBinder.DeathRecipient, val reply: Messenger)
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.sendingUid != Process.myUid() || !HiddenProcess.owns(this@HiddenEngineService)) return
            val lease = message.data.getBinder("lease") ?: return
            if (message.what == HiddenEngineConnection.DETACH) { release(lease); return }
            if (message.what != HiddenEngineConnection.ATTACH || leases.containsKey(lease) || leases.size >= 16) return
            val pluginId = message.data.getString("pluginId")?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return
            val host = message.data.getBinder("host") ?: return
            val reply = message.replyTo ?: return
            val owner = engine.owner(pluginId, IWebHiddenHost.Stub.asInterface(host), closed = { release(lease) })
            val death = IBinder.DeathRecipient { main.post { release(lease) } }
            runCatching { lease.linkToDeath(death, 0) }.onFailure { owner.close(); return }
            leases[lease] = Lease(owner, death, reply)
            owner.connect()
        }
    })

    override fun onCreate() {
        super.onCreate()
        check(HiddenProcess.owns(this))
        engine = HiddenDocumentEngine(this)
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun release(lease: IBinder) {
        val entry = leases.remove(lease) ?: return
        runCatching { lease.unlinkToDeath(entry.death, 0) }
        entry.owner.close()
        runCatching { entry.reply.send(Message.obtain(null, HiddenEngineConnection.CLOSED).apply { data = Bundle().apply { putBinder("lease", lease) } }) }
    }

    override fun onDestroy() {
        leases.keys.toList().forEach(::release)
        if (::engine.isInitialized) engine.close()
        super.onDestroy()
        // Gecko supports one native runtime initialization per process. Terminate only this
        // owned worker after its last service binding is gone, including stuck Binder workers.
        if (HiddenProcess.owns(this)) Process.killProcess(Process.myPid())
    }
}
