package dev.droidtop.browser

import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import dev.droidtop.pluginhost.IWebHiddenHost
import dev.droidtop.pluginhost.IWebHiddenProvider
import dev.droidtop.shared.net.BoundedStreamCopy
import dev.droidtop.shared.sitecheck.SiteCheck
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings

/** One isolated runtime, four immutable operations, fixed extraction only. No visible-session reuse. */
internal class HiddenDocumentEngine(private val context: Context, private val retire: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32))
    private val jobs = ConcurrentHashMap<String, Job>()
    private val owners = mutableSetOf<Owner>()
    private var ready: HiddenRuntimeBootstrap.Ready? = null
    private var port: HiddenPort? = null
    private var closed = false

    fun owner(pluginId: String, host: IWebHiddenHost, closed: () -> Unit): Owner = Owner(pluginId, host, closed).also { owners += it }

    inner class Owner(private val pluginId: String, val host: IWebHiddenHost, private val onClosed: () -> Unit) : IWebHiddenProvider.Stub() {
        private val tokens = ConcurrentHashMap<String, Job>()
        private val active = AtomicBoolean(true)
        private var connected = false
        private var started = false
        private val death = IBinder.DeathRecipient { close() }
        private fun caller() = active.get() && DroidtopLink.isDroidtop(context, Binder.getCallingUid())

        fun connect() {
            runCatching { host.asBinder().linkToDeath(death, 0) }.onFailure { close(); return }
            main.postDelayed({ if (!started) close() }, 30_000)
            HiddenRuntimeBootstrap.start(context) { result ->
                if (!active.get() || closed) return@start
                val initialized = result.getOrNull()
                if (initialized == null) { this@HiddenDocumentEngine.close(retireProcess = true); return@start }
                if (port == null) {
                    ready = initialized
                    port = HiddenPort(initialized, authorize = { id, url, kind ->
                        val job = jobs[id]
                        job != null && !job.cancelled.get() && !job.ended && job.accepted && safeUrl(url) != null &&
                            runCatching { job.owner.host.authorize(job.token, url, kind) }.getOrDefault(false)
                    }, closed = { this@HiddenDocumentEngine.close(retireProcess = true) })
                }
                connected = true
                runCatching { host.connected(this, arrayOf("web.document.get.v1", "web.runtime.isolated.v1")) }.onFailure { close() }
            }
        }

        override fun start(token: String?) {
            if (!caller() || token.isNullOrBlank() || token.length > 256) return
            main.post {
                if (!active.get() || closed || !connected || started) return@post
                if (jobs.size >= 4) { runCatching { host.unavailable(token, "Restricted renderer is busy") }; close(); return@post }
                started = true
                val job = Job(this, token)
                tokens[token] = job
                jobs[job.contextId] = job
                main.postDelayed({ if (!job.ended) fail(job, "failed", "Restricted document timed out") else remove(job) }, 30_000)
                execute(job) {
                    val raw = runCatching { host.takeRequest(token) }.getOrNull()
                    main.post {
                        // A late non-null take is already accepted and must end terminally.
                        if (raw != null) job.accepted = true
                        if (job.cancelled.get() || !active.get()) {
                            if (raw != null) runCatching { host.result(token, failure("failed", "Restricted document cancelled", job.url).toString()) }
                            remove(job)
                        } else if (raw == null) {
                            runCatching { host.unavailable(token, "Request was not available") }
                            remove(job)
                        } else runCatching { parse(job, raw); register(job) }
                            .onFailure { fail(job, "failed", "Unsupported restricted document request") }
                    }
                }
            }
        }

        override fun body(token: String?): ParcelFileDescriptor? {
            if (!caller() || token == null) return null
            val job = tokens[token] ?: return null
            val bytes = job.bytes ?: return null
            if (!job.claimed.compareAndSet(false, true) || job.cancelled.get()) return null
            val pipe = ParcelFileDescriptor.createReliablePipe()
            job.writer = pipe[1]
            // Cancellation may win between the first flag check and FD publication. Once the
            // writer is published, either remove() owns it or this recheck closes both ends;
            // a queued worker must never be the only owner of a late, uncancelled descriptor.
            if (job.cancelled.get()) {
                runCatching { pipe[0].close() }
                runCatching { pipe[1].closeWithError("Restricted document closed") }
                return null
            }
            try {
                workers.execute {
                    val output = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                    try {
                        BoundedStreamCopy.copy(ByteArrayInputStream(bytes), output, job.maxBytes.toLong(), bytes.size.toLong(),
                            beforeRead = { check(!job.cancelled.get()) }, beforeWrite = { check(!job.cancelled.get()) })
                        output.close()
                    } catch (error: Exception) { runCatching { pipe[1].closeWithError("Restricted document transfer failed") } }
                    finally { runCatching { output.close() }; main.post { remove(job) } }
                }
            } catch (busy: java.util.concurrent.RejectedExecutionException) {
                runCatching { pipe[0].close() }
                runCatching { pipe[1].closeWithError("Restricted transfer is busy") }
                main.post { remove(job) }
                return null
            }
            return pipe[0]
        }

        override fun cancel(token: String?) {
            if (!caller() || token == null) return
            main.post { tokens[token]?.let { fail(it, "failed", "Restricted document cancelled") } }
        }

        fun close() {
            if (!active.compareAndSet(true, false)) return
            main.post {
                runCatching { host.asBinder().unlinkToDeath(death, 0) }
                tokens.values.toList().forEach { fail(it, "failed", "Restricted host disconnected") }
                owners.remove(this)
                onClosed()
            }
        }

        fun forget(job: Job) {
            tokens.remove(job.token, job)
            if (started && tokens.isEmpty()) close()
        }
    }

    internal class Job(val owner: Owner, val token: String) {
        val contextId = "droidtop-hidden-${UUID.randomUUID()}"
        val cancelled = AtomicBoolean(false)
        val claimed = AtomicBoolean(false)
        val started = SystemClock.uptimeMillis()
        @Volatile var accepted = false
        @Volatile var bytes: ByteArray? = null
        @Volatile var writer: ParcelFileDescriptor? = null
        @Volatile var ended = false
        var finishing = false
        var stopped = false
        var url = ""
        var host = ""
        var cookie = ""
        var maxBytes = 131072
        var timeoutMs = 30000L
        var session: GeckoSession? = null
        var display: GeckoDisplay? = null
        var images: ImageReader? = null
    }

    private fun parse(job: Job, raw: String) {
        check(raw.length <= 32768)
        val request = JSONObject(raw)
        check(request.keys().asSequence().toSet() == setOf("op", "url", "cookies", "maxBytes", "timeoutMs", "proxy"))
        check(request.getString("op") == "document")
        job.url = request.getString("url")
        job.host = safeUrl(job.url)?.host?.lowercase() ?: error("unsafe URL")
        check(job.host.matches(Regex("[a-z0-9.-]+"))) // Cookie bridge v1 deliberately supports DNS hosts only.
        job.maxBytes = integer(request, "maxBytes", 131072).toInt()
        job.timeoutMs = integer(request, "timeoutMs", 30000)
        val cookies = request.getJSONObject("cookies")
        check(cookies.keys().asSequence().all { it == job.host })
        job.cookie = cookies.optString(job.host).also { check(it.length <= 16384 && !controls(it)) }
        jobProxy[job.contextId] = request.getJSONObject("proxy")
    }

    private val jobProxy = mutableMapOf<String, JSONObject>()
    private fun register(job: Job) {
        val remaining = job.timeoutMs - (SystemClock.uptimeMillis() - job.started)
        if (remaining <= 0) { fail(job, "failed", "Restricted document timed out"); return }
        main.postDelayed({ if (!job.ended) fail(job, "failed", "Restricted document timed out") else remove(job) }, remaining)
        port?.request(JSONObject(mapOf("op" to "register", "contextId" to job.contextId, "timeoutMs" to remaining, "proxy" to jobProxy.remove(job.contextId)))) { reply ->
            if (job.cancelled.get()) return@request
            if (reply?.optBoolean("registered") != true) { fail(job, "failed", "Restricted context was not authorized"); return@request }
            cookie(job, "set", job.cookie) { result ->
                if (result == null) fail(job, "failed", "Restricted session could not be initialized")
                else open(job)
            }
        } ?: fail(job, "failed", "Restricted runtime unavailable")
    }

    private fun cookie(job: Job, operation: String, header: String = "", done: (JSONObject?) -> Unit) {
        port?.request(JSONObject(mapOf("op" to "cookies", "contextId" to job.contextId, "cookieOp" to operation, "host" to job.host, "header" to header)), done)
            ?: done(null)
    }

    private fun open(job: Job) {
        if (job.cancelled.get()) return
        runCatching {
            val session = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(true).contextId(job.contextId).build())
            job.session = session
            session.navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest) = navigation(job, request.uri, "document")
                override fun onSubframeLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest) = navigation(job, request.uri, "frame")
            }
            session.progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) { job.stopped = false }
                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    if (job.ended || job.cancelled.get()) return
                    job.stopped = success
                    if (!success) fail(job, "failed", "Restricted document could not load")
                }
            }
            session.contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onCrash(session: GeckoSession) { if (!job.ended && !job.cancelled.get()) fail(job, "failed", "Restricted renderer stopped") }
                override fun onKill(session: GeckoSession) { if (!job.ended && !job.cancelled.get()) fail(job, "failed", "Restricted renderer stopped") }
            }
            session.open(checkNotNull(ready).runtime)
            val images = ImageReader.newInstance(1024, 768, PixelFormat.RGBA_8888, 2)
            job.images = images
            images.setOnImageAvailableListener({ reader -> runCatching { reader.acquireLatestImage()?.close() } }, main)
            val display = session.acquireDisplay()
            job.display = display
            display.surfaceChanged(GeckoDisplay.SurfaceInfo.Builder(images.surface).size(1024, 768)
                .newSurfaceProvider { if (!job.ended && !job.cancelled.get()) fail(job, "failed", "Restricted render target was lost") }.build())
            session.setActive(true)
            session.setFocused(false)
            session.loadUri(job.url)
            poll(job)
        }.onFailure { fail(job, "failed", "Restricted renderer could not start") }
    }

    private fun navigation(job: Job, url: String, kind: String): GeckoResult<AllowOrDeny> {
        val answer = GeckoResult<AllowOrDeny>()
        val answered = AtomicBoolean(false)
        if (job.ended || job.cancelled.get()) { answer.complete(AllowOrDeny.DENY); return answer }
        if (url == "about:blank") { answer.complete(AllowOrDeny.ALLOW); return answer }
        execute(job) {
            val allowed = !job.cancelled.get() && safeUrl(url) != null && runCatching { job.owner.host.authorize(job.token, url, kind) }.getOrDefault(false)
            main.post {
                if (answered.compareAndSet(false, true)) answer.complete(if (allowed && !job.cancelled.get()) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                if (!allowed) fail(job, "permission_denied", "A document dependency was not authorized")
            }
        }
        main.postDelayed({ if (answered.compareAndSet(false, true)) {
            answer.complete(AllowOrDeny.DENY)
            fail(job, "permission_denied", "Document authorization timed out")
        } }, 3_000)
        return answer
    }

    private fun poll(job: Job) {
        if (job.cancelled.get() || job.ended || job.finishing) return
        port?.request(JSONObject(mapOf("op" to "snapshot", "contextId" to job.contextId))) { result ->
            if (job.cancelled.get() || job.ended || job.finishing) return@request
            if (result == null) { fail(job, "failed", "Restricted document inspection failed"); return@request }
            if (result.optBoolean("denied")) { fail(job, "permission_denied", "A document dependency was not authorized"); return@request }
            val snapshot = result.optJSONObject("snapshot")
            val error = snapshot?.optString("error").orEmpty()
            if (error.isNotEmpty() && error != "document_not_ready") { fail(job, "failed", "Restricted document is not supported"); return@request }
            if (job.stopped && snapshot != null && error.isEmpty() && result.optInt("status") in 100..599) {
                runCatching { inspect(job, result, snapshot) }.onFailure { fail(job, "failed", "Restricted document was invalid or too large") }
            }
            if (!job.ended && !job.finishing) main.postDelayed({ poll(job) }, 250)
        } ?: fail(job, "failed", "Restricted runtime unavailable")
    }

    private fun inspect(job: Job, info: JSONObject, snapshot: JSONObject) {
        val url = info.getString("url")
        check(safeUrl(url) != null && snapshot.getString("url") == url && snapshot.getString("charset") == "UTF-8" && !snapshot.getBoolean("truncated"))
        val bytes = snapshot.getString("text").toByteArray(Charsets.UTF_8)
        check(bytes.size <= job.maxBytes && snapshot.getInt("bodyBytes") == bytes.size)
        val mime = info.getString("contentType").also { check(it.length <= 256 && !controls(it)) }
        val ua = info.getString("userAgent").also { check(it.length <= 4096 && !controls(it)) }
        val html = mime.substringBefore(';').trim().lowercase() in setOf("text/html", "application/xhtml+xml")
        val challenge = SiteCheck.isChallenge(info.getInt("status"), mapOf("cf-mitigated" to info.optString("cfMitigated")), bytes) ||
            (html && SiteCheck.isCheckTitle(snapshot.optString("title")))
        if (challenge) {
            if (SystemClock.uptimeMillis() - job.started >= minOf(25000, job.timeoutMs - 1000)) {
                job.finishing = true
                cookie(job, "get") { cookies ->
                    if (job.ended || job.cancelled.get()) return@cookie
                    val header = cookies?.optString(job.host).orEmpty()
                    if (header.length > 16384 || controls(header)) { fail(job, "failed", "Restricted session result was invalid"); return@cookie }
                    job.ended = true
                    val metadata = failure("challenged", "A visible browser check is required", url)
                        .put("effectiveUA", ua)
                    if (cookies != null) metadata.put("cookies", JSONObject(mapOf(job.host to header)))
                    runCatching { job.owner.host.result(job.token, metadata.toString()) }
                    remove(job)
                }
            }
            return
        }
        job.finishing = true
        cookie(job, "get") { cookies ->
            if (job.ended || job.cancelled.get()) return@cookie
            if (cookies == null) { fail(job, "failed", "Restricted session readback failed"); return@cookie }
            val header = cookies.optString(job.host)
            if (header.length > 16384 || controls(header)) { fail(job, "failed", "Restricted session result was invalid"); return@cookie }
            job.bytes = bytes
            job.ended = true
            cleanupPage(job)
            val metadata = JSONObject(mapOf("kind" to "document", "status" to info.getInt("status"), "finalUrl" to url,
                "contentType" to mime, "bodyBytes" to bytes.size, "truncated" to false, "effectiveUA" to ua,
                "cookies" to JSONObject(mapOf(job.host to header))))
            runCatching { job.owner.host.result(job.token, metadata.toString()) }.onFailure { remove(job) }
            main.postDelayed({ if (!job.claimed.get()) remove(job) }, 5_000)
        }
    }

    private fun fail(job: Job, kind: String, reason: String) {
        if (!job.ended) {
            job.ended = true
            if (job.accepted) runCatching { job.owner.host.result(job.token, failure(kind, reason, job.url).toString()) }
            else runCatching { job.owner.host.unavailable(job.token, reason) }
        }
        remove(job)
    }

    private fun cleanupPage(job: Job) {
        runCatching { job.display?.surfaceDestroyed() }
        job.display?.let { display -> runCatching { job.session?.releaseDisplay(display) } }
        job.display = null
        runCatching { job.images?.close() }; job.images = null
        runCatching { job.session?.close() }; job.session = null
        ready?.runtime?.storageController?.clearDataForSessionContext(job.contextId)
        port?.request(JSONObject(mapOf("op" to "unregister", "contextId" to job.contextId))) {}
    }

    private fun remove(job: Job) {
        if (!job.cancelled.compareAndSet(false, true)) return
        cleanupPage(job)
        runCatching { job.writer?.closeWithError("Restricted document closed") }
        job.bytes = null
        job.cookie = ""
        jobProxy.remove(job.contextId)
        jobs.remove(job.contextId, job)
        job.owner.forget(job)
    }

    private fun execute(job: Job, task: () -> Unit) {
        try { workers.execute(task) }
        catch (busy: java.util.concurrent.RejectedExecutionException) { main.post { fail(job, "failed", "Restricted renderer is busy") } }
    }

    fun close(retireProcess: Boolean = false) {
        if (closed) return
        closed = true
        owners.toList().forEach { it.close() }
        jobs.values.toList().forEach { fail(it, "failed", "Restricted renderer closed") }
        workers.shutdownNow()
        port?.close()
        port = null
        if (retireProcess) retire()
    }

    companion object {
        private fun integer(value: JSONObject, key: String, maximum: Long): Long {
            val number = value.get(key)
            check(number is Int || number is Long)
            return (number as Number).toLong().also { check(it in 1..maximum) }
        }
        private fun failure(kind: String, reason: String, url: String) = JSONObject(mapOf("kind" to kind, "reason" to reason)).apply {
            if (safeUrl(url) != null) put("finalUrl", url)
        }
        private fun controls(value: String) = value.any { it.code < 32 || it.code == 127 }
        private fun safeUrl(value: String): URI? = runCatching {
            if (value.length > 4096 || controls(value)) return null
            URI(value).takeIf { it.scheme == "https" && it.userInfo == null && !it.host.isNullOrBlank() && (it.port == -1 || it.port in 1..65535) }
        }.getOrNull()
    }
}
