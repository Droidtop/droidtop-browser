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
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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
    private const val TAG = "DroidtopBrowser"

    @Volatile private var broker: IPluginHostBroker? = null
    @Volatile private var pluginId: String? = null
    /** Signalled when a broker arrives, so [ensureConnected] wakes at once rather than polling. */
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()

    internal fun connected(id: String, given: IPluginHostBroker) {
        Log.i(TAG, "droidtop connected this browser as $id")
        pluginId = id
        broker = given
        lock.withLock { arrived.signalAll() }
    }

    internal fun disconnected(id: String) {
        if (pluginId == id) {
            broker = null
            pluginId = null
        }
    }

    internal fun hasPlugin(id: String): Boolean = pluginId == id && broker != null

    /**
     * A broker, knocking on droidtop's door when there is none yet (droidtop binds back and hands one over). Blocks: not
     * on the main thread.
     */
    fun ensureConnected(context: Context): Boolean {
        if (broker != null) return true
        // Each step says how it went in droidtop's log: on the console the browser waited and gave up with nothing to
        // say which step failed (2026-11-22).
        if (!isInstalled(context)) {
            Log.w(TAG, "droidtop is not installed, or not signed by the key this browser trusts")
            return false
        }
        if (!knock(context)) return false
        val until = SystemClock.uptimeMillis() + CONNECT_MS
        lock.withLock {
            while (broker == null) {
                val left = until - SystemClock.uptimeMillis()
                if (left <= 0) break
                arrived.await(left, TimeUnit.MILLISECONDS)
            }
        }
        if (broker == null) Log.w(TAG, "droidtop opened its door but did not connect this browser in ${CONNECT_MS / 1000} s")
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
        // The connection's answer on a thread of its own, never the main thread, which Fenix may keep busy while it starts.
        val bound = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.bindService(intent, Context.BIND_AUTO_CREATE, Executors.newSingleThreadExecutor(), connection)
            } else {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }
        }.onFailure { Log.w(TAG, "binding droidtop's door failed", it) }.getOrDefault(false)
        if (!bound) {
            Log.w(TAG, "droidtop's door could not be bound")
            return false
        }
        return try {
            if (!connected.await(CONNECT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "droidtop's door did not answer in ${CONNECT_MS / 1000} s")
                return false
            }
            val opened = door?.let { binder ->
                runCatching { IAppInterfaceDoor.Stub.asInterface(binder).knock() }
                    .onFailure { Log.w(TAG, "knocking on droidtop's door failed", it) }.getOrDefault(false)
            } ?: false
            Log.i(TAG, if (opened) "droidtop opened its door" else "droidtop refused to connect this browser (is its plugin approved and on?)")
            opened
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
