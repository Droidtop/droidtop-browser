package dev.droidtop.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.UUID
import org.json.JSONObject
import org.mozilla.gecko.process.GeckoProcessManager
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.DroidtopRestrictedSettings
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

    fun invalidate(ready: Ready) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (cached !== ready) return
        cached = null
        failure = IllegalStateException("Restricted runtime guard disconnected")
        runCatching { ready.runtime.shutdown() }
    }

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

    private data class Plan(val profile: File, val prefs: JSONObject)

    private fun prepare(context: Context): Plan {
        val root = File(context.noBackupFilesDir, "droidtop-web-runtime").canonicalFile
        check(root.mkdirs() || root.isDirectory)
        // Only this process owns these generated profiles, and prepare runs before its sole runtime
        // exists. Remove prior-process profiles without following symbolic links or touching Browser.
        root.listFiles()?.filter { it.name.matches(Regex("profile-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) }?.forEach { old ->
            check(old.canonicalFile.parentFile == root)
            Files.walkFileTree(old.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                    Files.delete(path)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(path: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(path)
                    return FileVisitResult.CONTINUE
                }
            })
        }
        // Never load prefs.js from an earlier process: user prefs could override startup defaults
        // before native attestation. This profile belongs to this runtime only, not Browser users.
        val profile = File(root, "profile-${UUID.randomUUID()}").canonicalFile
        check(profile.parentFile == root && profile.mkdir())
        val prefs = context.assets.open("extensions/droidtop-hidden/required-prefs.json").bufferedReader().use { JSONObject(it.readText()) }
        prefs.keys().asSequence().forEach { key ->
            val value = prefs.get(key)
            check(key.matches(Regex("[a-zA-Z0-9._-]+")) && (value is Boolean || value is Int || value is String))
        }
        return Plan(profile, prefs)
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
            val settings = GeckoRuntimeSettings.Builder()
                .arguments(arrayOf("-profile", plan.profile.absolutePath))
                // Explicitly disable the optional debug-config parser: it catches/ignores errors.
                .configFilePath("")
                .isolatedProcessEnabled(true)
                .extensionsProcessEnabled(false)
                .crashHandler(null)
                .build()
            val required = plan.prefs.keys().asSequence().associateWith { plan.prefs.get(it) }
            GeckoRuntime.create(context, DroidtopRestrictedSettings.apply(settings, required))
        }.fold(onFailure = ::fail, onSuccess = { created ->
            runtime = created
            // The source-pinned adapter supplied restrictions before native startup. Independently verify
            // the resulting native profile/preferences through the installed privileged extension.
            created.webExtensionController.ensureBuiltIn(URL, ID).accept({ extension ->
                if (extension == null) { fail(IllegalStateException("Restricted bootstrap extension unavailable")); return@accept }
                created.webExtensionController.setAllowedInPrivateBrowsing(extension, true).accept({ allowed ->
                if (allowed == null) { fail(IllegalStateException("Restricted private extension unavailable")); return@accept }
                allowed.setMessageDelegate(object : WebExtension.MessageDelegate {
                    override fun onConnect(port: WebExtension.Port) {
                        if (finished) { port.disconnect(); return }
                        port.setDelegate(object : WebExtension.PortDelegate {
                            override fun onPortMessage(message: Any, port: WebExtension.Port) {
                                if (finished) return
                                val reply = message as? JSONObject ?: return
                                val actual = reply.optJSONObject("prefs")
                                val matched = reply.optBoolean("ok") && reply.optInt("engineMajor") == 160 && reply.optString("profile") == plan.profile.absolutePath &&
                                    actual != null && plan.prefs.keys().asSequence().all { actual.opt(it) == plan.prefs.get(it) } &&
                                    GeckoProcessManager.getInstance().isIsolatedProcessEnabled
                                if (!matched) { fail(IllegalStateException("Restricted runtime attestation failed")); return }
                                finished = true
                                done(Result.success(Ready(created, allowed, plan.profile, port)))
                            }
                            override fun onDisconnect(port: WebExtension.Port) {
                                val error = IllegalStateException("Restricted bootstrap extension disconnected")
                                if (finished) {
                                    // A formerly attested runtime must never be handed out after its guard port dies.
                                    cached = null
                                    failure = error
                                    runCatching { created.shutdown() }
                                } else fail(error)
                            }
                        })
                        port.postMessage(JSONObject(mapOf("op" to "attest", "profile" to plan.profile.absolutePath, "prefs" to plan.prefs)))
                    }
                }, "droidtop-hidden")
                }, { error -> fail(error ?: IllegalStateException("Restricted private extension setup failed")) })
            }, { failure -> fail(failure ?: IllegalStateException("Restricted extension install failed")) })
        })
    }
}
