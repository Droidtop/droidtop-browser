package dev.droidtop.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject
import org.mozilla.gecko.process.GeckoProcessManager
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension

/** Only the dedicated process may create this runtime. No ordinary Fenix components/default runtime access. */
internal object HiddenRuntimeBootstrap {
    private const val ID = "droidtop-hidden@droidtop.dev"
    private const val URL = "resource://android/assets/extensions/droidtop-hidden/"
    private val main = Handler(Looper.getMainLooper())
    private val files = Executors.newSingleThreadExecutor()
    private var cached: Ready? = null
    private var failure: Throwable? = null
    private var starting = false
    private val waiting = mutableListOf<(Result<Ready>) -> Unit>()

    data class Ready(val runtime: GeckoRuntime, val extension: WebExtension, val profile: File, val port: WebExtension.Port)

    /** Completion is bounded and occurs on main. No session or network document may start before Ready. */
    fun start(context: Context, done: (Result<Ready>) -> Unit) {
        check(HiddenProcess.owns(context)) { "Restricted runtime requested outside its owned process" }
        main.post {
            cached?.let { done(Result.success(it)); return@post }
            failure?.let { done(Result.failure(it)); return@post }
            if (waiting.size >= 8) { done(Result.failure(IllegalStateException("Restricted startup is busy"))); return@post }
            waiting += done
            if (starting) return@post
            starting = true
            files.execute {
                runCatching { prepare(context) }.fold(
                    onSuccess = { plan -> main.post { launch(context, plan, ::complete) } },
                    onFailure = { error -> main.post { complete(Result.failure(error)) } },
                )
            }
        }
    }

    private fun complete(result: Result<Ready>) {
        result.fold(onSuccess = { cached = it }, onFailure = { failure = it })
        starting = false
        val callbacks = waiting.toList()
        waiting.clear()
        callbacks.forEach { it(result) }
    }

    private data class Plan(val profile: File, val config: File, val prefs: JSONObject)

    private fun prepare(context: Context): Plan {
        val root = File(context.noBackupFilesDir, "droidtop-web-runtime").canonicalFile
        check(root.mkdirs() || root.isDirectory)
        val profile = File(root, "profile").canonicalFile
        check(profile.parentFile == root && (profile.mkdirs() || profile.isDirectory))
        val prefs = context.assets.open("extensions/droidtop-hidden/required-prefs.json").bufferedReader().use { JSONObject(it.readText()) }
        val config = File(root, "restricted.yaml")
        val text = buildString {
            append("env:\n  MOZ_ANDROID_CONTENT_SERVICE_ISOLATED_PROCESS: '1'\nprefs:\n")
            prefs.keys().asSequence().sorted().forEach { key ->
                val value = prefs.get(key)
                check(key.matches(Regex("[a-zA-Z0-9._-]+")) && (value is Boolean || value is Int))
                append("  ").append(key).append(": ").append(value).append('\n')
            }
        }
        val atomic = AtomicFile(config)
        val output = atomic.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
        return Plan(profile, config, prefs)
    }

    private fun launch(context: Context, plan: Plan, done: (Result<Ready>) -> Unit) {
        var finished = false
        var runtime: GeckoRuntime? = null
        fun fail(failure: Throwable) {
            if (finished) return
            finished = true
            runCatching { runtime?.shutdown() }
            done(Result.failure(failure))
        }
        main.postDelayed({ fail(IllegalStateException("Restricted browser startup timed out")) }, 15_000)
        runCatching {
            GeckoRuntime.create(context, GeckoRuntimeSettings.Builder()
                .arguments(arrayOf("-profile", plan.profile.absolutePath))
                .configFilePath(plan.config.absolutePath)
                .isolatedProcessEnabled(true)
                .extensionsProcessEnabled(false)
                .crashHandler(null)
                .build())
        }.fold(onFailure = ::fail, onSuccess = { created ->
            runtime = created
            // The config file loader can silently ignore a missing/invalid file. A settings object is not proof
            // that native prefs/profile took effect; verify them through the installed privileged extension.
            created.webExtensionController.ensureBuiltIn(URL, ID).accept({ extension ->
                if (extension == null) { fail(IllegalStateException("Restricted bootstrap extension unavailable")); return@accept }
                extension.setMessageDelegate(object : WebExtension.MessageDelegate {
                    override fun onConnect(port: WebExtension.Port) {
                        if (finished) { port.disconnect(); return }
                        port.setDelegate(object : WebExtension.PortDelegate {
                            override fun onPortMessage(message: Any, port: WebExtension.Port) {
                                if (finished) return
                                val reply = message as? JSONObject ?: return
                                val actual = reply.optJSONObject("prefs")
                                val matched = reply.optBoolean("ok") && reply.optInt("engineMajor") == 160 && reply.optString("profile") == plan.profile.canonicalPath &&
                                    actual != null && plan.prefs.keys().asSequence().all { actual.opt(it) == plan.prefs.get(it) } &&
                                    GeckoProcessManager.getInstance().isIsolatedProcessEnabled
                                if (!matched) { fail(IllegalStateException("Restricted runtime attestation failed")); return }
                                finished = true
                                done(Result.success(Ready(created, extension, plan.profile, port)))
                            }
                            override fun onDisconnect(port: WebExtension.Port) { fail(IllegalStateException("Restricted bootstrap extension disconnected")) }
                        })
                        port.postMessage(JSONObject(mapOf("op" to "attest", "profile" to plan.profile.canonicalPath, "prefs" to plan.prefs)))
                    }
                }, "droidtop-hidden")
            }, { failure -> fail(failure ?: IllegalStateException("Restricted extension install failed")) })
        })
    }
}
