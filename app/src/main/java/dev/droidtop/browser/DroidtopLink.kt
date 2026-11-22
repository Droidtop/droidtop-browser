package dev.droidtop.browser

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import dev.droidtop.pluginhost.IAppInterfaceDoor
import dev.droidtop.pluginhost.IPluginHostBroker
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * The browser's one way to droidtop: the plugin broker droidtop hands over when it binds [DroidtopInterfaceService]
 * (droidtop docs/plugin-api.md 1.3, the application-interface plugin). Everything goes through the broker; there is no
 * other channel. Only droidtop's package, signed with a pinned certificate, is ever answered or knocked on.
 */
object DroidtopLink {
    const val DROIDTOP_PACKAGE = "dev.droidtop.app"

    /** SHA-256 of droidtop's release signing certificate, the only droidtop answered (the same pin as Enginehost's). */
    private val DROIDTOP_CERTS = setOf("38b1ef4bc47fa8cd62ffcbb43fa85d7f450e9c2d4a8b690776d2218b23f0749f")

    private const val DOOR_ACTION = "dev.droidtop.plugin.APP_INTERFACE_DOOR"
    private const val CONNECT_MS = 5_000L

    @Volatile private var broker: IPluginHostBroker? = null
    @Volatile private var pluginId: String? = null
    private val arrived = Object()

    internal fun connected(id: String, given: IPluginHostBroker) {
        pluginId = id
        broker = given
        synchronized(arrived) { arrived.notifyAll() }
    }

    internal fun disconnected(id: String) {
        if (pluginId == id) {
            broker = null
            pluginId = null
        }
    }

    /**
     * A broker, knocking on droidtop's door when there is none yet (droidtop binds back and hands one over). Blocks: not
     * on the main thread.
     */
    fun ensureConnected(context: Context): Boolean {
        if (broker != null) return true
        if (!isInstalled(context) || !knock(context)) return false
        val until = SystemClock.uptimeMillis() + CONNECT_MS
        synchronized(arrived) {
            while (broker == null) {
                val left = until - SystemClock.uptimeMillis()
                if (left <= 0) break
                arrived.wait(left)
            }
        }
        return broker != null
    }

    /** One broker call; the parsed `{ok, data|error}` reply, or null with no broker or no answer. Blocks. */
    fun call(api: String, op: String, args: JSONObject): JSONObject? {
        val current = broker ?: return null
        val request = JSONObject().put("api", api).put("version", 1).put("op", op).put("args", args)
        return runCatching { JSONObject(current.call(request.toString())) }.getOrElse {
            broker = null
            null
        }
    }

    private fun knock(context: Context): Boolean {
        val connected = CountDownLatch(1)
        var door: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                door = service
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val intent = Intent(DOOR_ACTION).setPackage(DROIDTOP_PACKAGE)
        if (!runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)) return false
        return try {
            connected.await(CONNECT_MS, TimeUnit.MILLISECONDS)
            door?.let { runCatching { IAppInterfaceDoor.Stub.asInterface(it).knock() }.getOrDefault(false) } ?: false
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    /** Whether [uid] is droidtop: its package, signed with the pinned certificate. */
    fun isDroidtop(context: Context, uid: Int): Boolean {
        val packages = context.packageManager.getPackagesForUid(uid) ?: return false
        return DROIDTOP_PACKAGE in packages && isInstalled(context)
    }

    private fun isInstalled(context: Context): Boolean {
        val signers = signerDigests(context.packageManager)
        return signers.isNotEmpty() && signers.all { it in DROIDTOP_CERTS }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(pm: PackageManager): Set<String> = runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(DROIDTOP_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?: return@runCatching emptySet<String>()
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory.takeLast(1).toTypedArray()
        } else {
            pm.getPackageInfo(DROIDTOP_PACKAGE, PackageManager.GET_SIGNATURES).signatures
        }
        signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }.getOrDefault(emptySet())
}
