package dev.droidtop.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.fenix.ext.components

/**
 * A page droidtop asked the browser to show (droidtop docs/plugin-api.md 3 G3a, `web.view`): a plugin's sign-in, a
 * site's browser check or a download page, on Gecko where the device's own WebView is too old.
 *
 * droidtop starts this with a one-use token. The page runs in a private tab with droidtop's session put in for its
 * sites only; each page it finishes is reported, and droidtop says when it is done; the downloads a page starts are
 * captured, not saved. At the end the browser answers with the request's sites' cookies, its user agent and what it
 * captured, and takes the session out again. The screen is headed like droidtop's own view: the site's name and the
 * address, and buttons that say what they do.
 */
internal class WebSessionView(
    private val context: Context,
    private val engine: Engine,
    private val display: (android.view.View) -> Unit,
    private val closed: () -> Unit,
    private val alive: () -> Boolean,
    private val event: (JSONObject) -> Unit = {},
    private val bodyDownloads: WebDownloadSink? = null,
) {
    private val main = Handler(Looper.getMainLooper())
    private var token: String? = null
    private var request: JSONObject? = null
    private var session: EngineSession? = null
    private var view: EngineView? = null
    private var location: TextView? = null
    private var takenLine: TextView? = null
    private var errorLine: TextView? = null
    private var currentUrl: String? = null
    private var currentTitle: String? = null
    private var canGoBack = false
    private var answered = false
    private val sessionContext = "droidtop-${java.util.UUID.randomUUID()}"
    private val captured = mutableListOf<JSONObject>()
    private var pendingDownloads = 0
    private var acceptingDownloads = false

    private val mode: String get() = request?.optString("mode").orEmpty()
    private val endsWithDone: Boolean get() = mode == "sign_in" || mode == "check"

    fun open(given: String) {
        answered = false
        token = given
        display(TextView(context).apply {
            text = "Opening the page"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        Thread {
            val reply = if (DroidtopLink.ensureConnected(context.applicationContext)) {
                DroidtopLink.call("web.view", "take_request", JSONObject().put("token", given))
            } else {
                null
            }
            main.post {
                val data = reply?.takeIf { it.optBoolean("ok") }?.optJSONObject("data")
                if (data == null) {
                    Log.w(DroidtopInterfaceService.TAG, "droidtop did not give the page: ${reply?.optJSONObject("error")?.optString("message")}")
                    showFailure("The page request could not be opened", "request_unavailable") { open(given) }
                } else if (!alive() || answered) {
                    // The one-use request may arrive after Back/Close destroyed this activity. Return ownership
                    // immediately; setting answered without a finish would leave droidtop waiting for its timeout.
                    answered = true
                    Thread { DroidtopLink.call("web.view", "finish", JSONObject().put("token", given).put("completed", false)) }.start()
                } else {
                    start(data)
                }
            }
        }.start()
    }

    private fun start(asked: JSONObject) {
        request = asked
        if (session == null) {
            val opened = runCatching {
                engine.createSession(private = true, contextId = sessionContext).also {
                    session = it
                    it.register(observer)
                }
            }.isSuccess
            if (!opened) {
                showFailure("The browser engine could not start", "engine_unavailable") { start(asked) }
                return
            }
        }
        // Browse is an isolated throw-away context: no vault/session import, cookie bridge, or result export.
        if (mode == "browse") {
            showSafely(asked)
            return
        }
        SessionCookies.whenReady(
            engine,
            ready = {
                if (!answered && alive()) SessionCookies.put(sessionContext, asked.optJSONObject("cookies") ?: JSONObject()) { put ->
                    if (!put) {
                        Log.w(DroidtopInterfaceService.TAG, "droidtop's session could not be put into the page")
                        if (!answered && alive()) showFailure("The session could not be loaded", "session_unavailable") { start(asked) }
                    } else if (!answered && alive()) {
                        showSafely(asked)
                    } else {
                        // Cancellation can race an already-sent injection. Remove the sites it just installed.
                        SessionCookies.clear(sessionContext, cookieDomains(asked))
                    }
                }
            },
            // Without the cookie bridge the session could neither go in nor come back: droidtop is answered "nothing
            // done" at once, rather than the person waiting on a page that never opens.
            failed = { if (alive()) showFailure("The session bridge did not start", "session_unavailable") { start(asked) } },
        )
    }

    private fun showSafely(asked: JSONObject) {
        runCatching { show(asked) }.onFailure {
            showFailure("The page could not be opened", "page_open_failed") { start(asked) }
        }
    }

    private fun show(asked: JSONObject) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0x12, 0x12, 0x14))
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(context).apply {
            text = asked.optString("label")
            setTextColor(Color.WHITE)
            textSize = 18f
        })
        location = TextView(context).apply {
            setTextColor(Color.rgb(0xB0, 0xB0, 0xB8))
            textSize = 13f
        }
        titles.addView(location)
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val finishLabel = when (mode) {
            "sign_in" -> "Signed in"
            "check" -> "Continue"
            else -> null
        }
        if (finishLabel != null) bar.addView(button(finishLabel) { finishWith(completed = true) })
        val signIn = asked.optString("signInUrl").takeIf { mode != "sign_in" && it.startsWith("https://") }
        if (signIn != null) bar.addView(button("Sign in") { session?.loadUrl(signIn) })
        bar.addView(button(if (endsWithDone) "Cancel" else "Close") { finishWith(completed = !endsWithDone) })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        errorLine = TextView(context).apply {
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTextColor(Color.WHITE)
            setPadding(dp(16), 0, dp(16), dp(6))
            visibility = android.view.View.GONE
        }.also { root.addView(it) }
        val max = asked.optInt("maxDownloads", 0)
        if (mode == "open" && max > 1) {
            takenLine = TextView(context).apply {
                text = "0 of $max taken"
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(dp(16), 0, dp(16), dp(6))
            }
            root.addView(takenLine)
        }

        val engineView = engine.createView(context)
        engineView.setActivityContext(context)
        val tab = checkNotNull(session)
        view = engineView
        engineView.render(tab)
        root.addView(engineView.asView(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        display(root)
        tab.loadUrl(asked.optString("url"))
        acceptingDownloads = true
    }

    private val observer = object : EngineSession.Observer {
        override fun onLocationChange(url: String, hasUserGesture: Boolean) {
            currentUrl = url
            location?.text = Uri.parse(url).host.orEmpty()
            navigationEvent()
        }

        override fun onTitleChange(title: String) {
            currentTitle = title
            navigationEvent()
        }

        override fun onNavigationStateChange(canGoBack: Boolean?, canGoForward: Boolean?) {
            if (canGoBack != null) this@WebSessionView.canGoBack = canGoBack
            navigationEvent()
        }

        override fun onLoadingStateChange(loading: Boolean) {
            event(JSONObject().put("type", "loading").put("loading", loading))
            if (!loading) reportPage()
        }

        override fun onExternalResource(
            url: String,
            fileName: String?,
            contentLength: Long?,
            contentType: String?,
            cookie: String?,
            userAgent: String?,
            isPrivate: Boolean,
            skipConfirmation: Boolean,
            openInApp: Boolean,
            response: mozilla.components.concept.fetch.Response?,
        ) {
            main.post {
                if (mode != "open") {
                    response?.close()
                    if (!answered && alive()) downloadFailure(
                        "Downloads are not available on this page. Use Droidtop's download action.",
                        "download_unavailable_here")
                    return@post
                }
                if (response != null) captureBody(response, fileName, contentLength, contentType)
                else capture(url, fileName, contentLength, contentType, cookie, userAgent)
            }
        }
    }

    /** Tells droidtop the page finished, with the host's cookies and whether the site's sign-in cookie is set. */
    private fun reportPage() {
        if (mode == "browse" || answered) return
        val asked = request ?: return
        val url = currentUrl ?: return
        val host = Uri.parse(url).host ?: return
        val domains = asked.optJSONArray("domains").strings()
        val doneCookie = asked.optString("doneCookie").takeIf { !asked.isNull("doneCookie") && it.isNotBlank() }
        val approvedHost = domains.any { host == it || host.endsWith(".$it") }
        SessionCookies.read(sessionContext, if (approvedHost) domains + host else domains) { cookies ->
            if (cookies == null || answered || !alive()) return@read
            val args = JSONObject().put("token", token).put("url", url).put("title", currentTitle.orEmpty())
                .put("cookies", cookies.optString(host))
            if (doneCookie != null) {
                args.put("signedIn", domains.any { domain -> cookies.optString(domain).split(';').any { it.trim().startsWith("$doneCookie=") } })
            }
            Thread {
                val finish = DroidtopLink.call("web.view", "report_page", args)?.optJSONObject("data")?.optBoolean("finish") == true
                if (finish) main.post { finishWith(completed = true) }
            }.start()
        }
    }

    private fun capture(url: String, fileName: String?, size: Long?, mimeType: String?, cookie: String?, userAgent: String?) {
        if (mode != "open" || answered || !acceptingDownloads) return
        if (!url.startsWith("https://")) {
            downloadFailure("This download has no transferable body or secure web address", "download_not_replayable")
            return
        }
        if (captured.any { it.optString("url") == url }) return
        captured += JSONObject().put("url", url).apply {
            fileName?.let { put("contentDisposition", "attachment; filename=\"${it.replace("\"", "")}\"") }
            size?.takeIf { it > 0 }?.let { put("size", it) }
            mimeType?.let { put("mimeType", it) }
            cookie?.let { put("cookie", it) }
            userAgent?.let { put("userAgent", it) }
            currentUrl?.let { put("referer", it) }
        }
        val max = request?.optInt("maxDownloads", 1)?.coerceAtLeast(1) ?: 1
        if (captured.size >= max) finishWith(completed = true) else takenLine?.text = "${captured.size} of $max taken"
    }

    private fun captureBody(response: mozilla.components.concept.fetch.Response, fileName: String?, size: Long?, mimeType: String?) {
        val given = token
        val sink = bodyDownloads
        val sourceUrl = currentUrl ?: request?.optString("url")
        val max = request?.optInt("maxDownloads", 1)?.coerceIn(1, 16) ?: 1
        if (mode == "open" && !answered && alive() && (sink == null || !sink.available())) {
            response.close()
            downloadFailure("Update Droidtop to transfer this download from Browser", "download_capability_required")
            return
        }
        if (mode != "open" || answered || !acceptingDownloads || !alive() || given == null || sink == null ||
            sourceUrl == null || !sourceUrl.startsWith("https://") || sourceUrl.length > 4096 ||
            (size ?: -1) > RemoteWebDownloads.MAX_BYTES || captured.size + pendingDownloads >= max) {
            response.close()
            if (mode == "open" && !answered && alive()) downloadFailure(
                "This download needs the embedded browser's body transfer", "download_body_unavailable")
            return
        }
        // A bounded basename is metadata only. The host independently validates it and owns every destination path.
        val name = fileName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^\\p{L}\\p{N} ._()\\-]"), "_").trim(' ', '.').take(48).ifEmpty { "download" }
        // Gecko forwards the original Content-Length header while its body may be decoded.
        // That wire length is not an exact length for gzip/br/etc. The shared copier/host still
        // enforce the actual byte cap and reliable EOF; do not label valid decoded data short/excess.
        val encoding = response.headers["Content-Encoding"]?.trim().orEmpty()
        val bodySize = size?.takeIf { it >= 0 && (encoding.isEmpty() || encoding.equals("identity", ignoreCase = true)) } ?: -1L
        val metadata = JSONObject().put("sourceUrl", sourceUrl).put("fileName", name)
            .put("mimeType", mimeType.orEmpty().filterNot { it.isISOControl() }.take(256))
            .put("size", bodySize)
        pendingDownloads++
        val offered = sink.offer(given, metadata, response) { id, accepted ->
            pendingDownloads--
            if (accepted && alive() && !answered) {
                captured += JSONObject().put("streamOfferId", id)
                if (captured.size >= max) finishWith(completed = true)
                else takenLine?.text = "${captured.size} of $max taken"
            } else {
                if (accepted) sink.discard(given, id)
                if (alive() && !answered) downloadFailure("Droidtop could not accept this download", "download_refused")
            }
        }
        if (!offered) {
            pendingDownloads--
            response.close()
            downloadFailure("This download could not be offered to Droidtop", "download_refused")
        }
    }

    private fun downloadFailure(message: String, code: String) {
        errorLine?.apply { text = message; visibility = android.view.View.VISIBLE }
        event(JSONObject().put("type", "error").put("code", code).put("message", message))
    }

    /** Answers droidtop with what the page left, takes the session out of the private store and closes. */
    private fun finishWith(completed: Boolean) {
        if (answered) return
        if (!completed) return abort()
        answered = true
        val asked = request
        if (asked == null) {
            closed()
            return
        }
        if (mode == "browse") {
            Thread { DroidtopLink.call("web.view", "finish", JSONObject().put("token", token).put("completed", completed)) }.start()
            close()
            return
        }
        val domains = cookieDomains(asked)
        SessionCookies.read(sessionContext, domains) { cookies ->
            if (cookies == null) {
                answered = false
                showFailure("The session could not be read back", "session_read_failed", preserveSession = true) { finishWith(completed) }
                return@read
            }
            val args = JSONObject().put("token", token).put("completed", completed).put("cookies", cookies)
                .put("downloads", JSONArray(captured))
            engine.settings.userAgentString?.let { args.put("userAgent", it) }
            Thread {
                val reply = DroidtopLink.call("web.view", "finish", args)
                main.post {
                    if (reply?.optBoolean("ok") == true && reply.optJSONObject("data")?.optBoolean("accepted") == true) {
                        SessionCookies.clear(sessionContext, domains)
                        close()
                    } else if (alive()) {
                        answered = false
                        // finish consumes the one-use token even when validation fails. Repeating it cannot
                        // recover the request; make the terminal outcome explicit rather than offering a false retry.
                        showFailure("Droidtop could not accept this session or download. Close this page and start again.", "result_refused", retry = null)
                    }
                }
            }.start()
        }
    }

    private fun close() {
        releaseViewAndSession()
        closed()
    }

    fun back() {
        if (canGoBack) session?.goBack() else finishWith(completed = !endsWithDone)
    }

    fun cancel() {
        // The host destroys its page as soon as finish is accepted, before a DownloadJob may pull the body.
        // Detaching an answered page must preserve its accepted body leases; explicit transfer cancellation is separate.
        if (answered) close() else abort()
    }

    private fun abort() {
        answered = true
        token?.let { bodyDownloads?.cancelOffers(it) }
        if (request != null) Thread {
            DroidtopLink.call("web.view", "finish", JSONObject().put("token", token).put("completed", false))
        }.start()
        request?.let { SessionCookies.clear(sessionContext, cookieDomains(it)) }
        close()
    }

    private fun showFailure(message: String, code: String, preserveSession: Boolean = false, retry: (() -> Unit)?) {
        if (!alive()) return
        event(JSONObject().put("type", "error").put("code", code).put("message", message.take(512)))
        if (!preserveSession) releaseViewAndSession()
        display(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(0x12, 0x12, 0x14))
            addView(TextView(context).apply { text = message; setTextColor(Color.WHITE) })
            if (retry != null) addView(button("Retry") { retry() })
            addView(button("Close") { abort() })
        })
    }

    fun destroy() {
        // Gone without an answer: droidtop is told nothing was done, and the session leaves the private store.
        if (!answered && request != null) {
            answered = true
            token?.let { bodyDownloads?.cancelOffers(it) }
            Thread { DroidtopLink.call("web.view", "finish", JSONObject().put("token", token).put("completed", false)) }.start()
            request?.let { SessionCookies.clear(sessionContext, cookieDomains(it)) }
        }
        releaseViewAndSession()
    }

    private fun button(label: String, action: () -> Unit) = Button(context).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun navigationEvent() {
        val url = currentUrl ?: return
        if (url.length > 4096) return // Never turn a long address into a different truncated address.
        event(JSONObject().put("type", "navigation").put("url", url)
            .put("title", currentTitle.orEmpty().take(512)).put("canGoBack", canGoBack))
    }

    private fun clearSessionContext() {
        context.applicationContext.components.core.geckoRuntime.storageController.clearDataForSessionContext(sessionContext)
    }

    private fun releaseViewAndSession() {
        acceptingDownloads = false
        val kept = session
        session = null
        kept?.unregister(observer)
        view?.release()
        view = null
        val cleanup = {
            kept?.close()
            // A retry can have attached another session in this same scoped context meanwhile.
            if (session == null) clearSessionContext()
        }
        val given = token
        if (given != null && bodyDownloads != null) bodyDownloads.afterTransfers(given, cleanup) else cleanup()
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun cookieDomains(asked: JSONObject): List<String> =
        (asked.optJSONArray("domains").strings() + (asked.optJSONObject("cookies")?.keys()?.asSequence()?.toList() ?: emptyList())).distinct()

    private fun JSONArray?.strings(): List<String> = this?.let { list -> (0 until list.length()).map { list.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()

}
