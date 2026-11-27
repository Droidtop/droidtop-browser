package dev.droidtop.browser

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
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
class WebViewActivity : Activity() {
    private var token: String? = null
    private var request: JSONObject? = null
    private var session: EngineSession? = null
    private var view: EngineView? = null
    private var location: TextView? = null
    private var takenLine: TextView? = null
    private var currentUrl: String? = null
    private var currentTitle: String? = null
    private var canGoBack = false
    private var answered = false
    private val captured = mutableListOf<JSONObject>()

    private val engine: Engine get() = components.core.engine
    private val mode: String get() = request?.optString("mode").orEmpty()
    private val endsWithDone: Boolean get() = mode == "sign_in" || mode == "check"

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
        val given = intent.getStringExtra(EXTRA_TOKEN)
        if (given == null) {
            finish()
            return
        }
        token = given
        setContentView(TextView(this).apply {
            text = "Opening the page"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        Thread {
            val reply = if (DroidtopLink.ensureConnected(applicationContext)) {
                DroidtopLink.call("web.view", "take_request", JSONObject().put("token", given))
            } else {
                null
            }
            runOnUiThread {
                val data = reply?.takeIf { it.optBoolean("ok") }?.optJSONObject("data")
                if (data == null) {
                    Log.w(DroidtopInterfaceService.TAG, "droidtop did not give the page: ${reply?.optJSONObject("error")?.optString("message")}")
                    answered = true
                    finish()
                } else if (isFinishing || isDestroyed || answered) {
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
        SessionCookies.whenReady(
            engine,
            ready = {
                if (!answered && !isFinishing && !isDestroyed) SessionCookies.put(asked.optJSONObject("cookies") ?: JSONObject()) { put ->
                    if (!put) {
                        Log.w(DroidtopInterfaceService.TAG, "droidtop's session could not be put into the page")
                        if (!answered && !isFinishing && !isDestroyed) finishWith(completed = false)
                    } else if (!answered && !isFinishing && !isDestroyed) {
                        show(asked)
                    } else {
                        // Cancellation can race an already-sent injection. Remove the sites it just installed.
                        SessionCookies.clear(cookieDomains(asked))
                    }
                }
            },
            // Without the cookie bridge the session could neither go in nor come back: droidtop is answered "nothing
            // done" at once, rather than the person waiting on a page that never opens.
            failed = { if (!isFinishing) finishWith(completed = false) },
        )
    }

    private fun show(asked: JSONObject) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0x12, 0x12, 0x14))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = asked.optString("label")
            setTextColor(Color.WHITE)
            textSize = 18f
        })
        location = TextView(this).apply {
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
        val max = asked.optInt("maxDownloads", 0)
        if (mode == "open" && max > 1) {
            takenLine = TextView(this).apply {
                text = "0 of $max taken"
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(dp(16), 0, dp(16), dp(6))
            }
            root.addView(takenLine)
        }

        val engineView = engine.createView(this)
        engineView.setActivityContext(this)
        val tab = engine.createSession(private = true)
        tab.register(observer)
        engineView.render(tab)
        root.addView(engineView.asView(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        view = engineView
        session = tab
        tab.loadUrl(asked.optString("url"))
    }

    private val observer = object : EngineSession.Observer {
        override fun onLocationChange(url: String, hasUserGesture: Boolean) {
            currentUrl = url
            location?.text = Uri.parse(url).host.orEmpty()
        }

        override fun onTitleChange(title: String) {
            currentTitle = title
        }

        override fun onNavigationStateChange(canGoBack: Boolean?, canGoForward: Boolean?) {
            if (canGoBack != null) this@WebViewActivity.canGoBack = canGoBack
        }

        override fun onLoadingStateChange(loading: Boolean) {
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
            runOnUiThread { capture(url, fileName, contentLength, contentType, cookie, userAgent) }
        }
    }

    /** Tells droidtop the page finished, with the host's cookies and whether the site's sign-in cookie is set. */
    private fun reportPage() {
        val asked = request ?: return
        val url = currentUrl ?: return
        val host = Uri.parse(url).host ?: return
        val domains = asked.optJSONArray("domains").strings()
        val doneCookie = asked.optString("doneCookie").takeIf { !asked.isNull("doneCookie") && it.isNotBlank() }
        SessionCookies.read(domains + host) { cookies ->
            val args = JSONObject().put("token", token).put("url", url).put("title", currentTitle.orEmpty())
                .put("cookies", cookies.optString(host))
            if (doneCookie != null) {
                args.put("signedIn", domains.any { domain -> cookies.optString(domain).split(';').any { it.trim().startsWith("$doneCookie=") } })
            }
            Thread {
                val finish = DroidtopLink.call("web.view", "report_page", args)?.optJSONObject("data")?.optBoolean("finish") == true
                if (finish) runOnUiThread { finishWith(completed = true) }
            }.start()
        }
    }

    private fun capture(url: String, fileName: String?, size: Long?, mimeType: String?, cookie: String?, userAgent: String?) {
        if (mode != "open" || answered) return
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
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

    /** Answers droidtop with what the page left, takes the session out of the private store and closes. */
    private fun finishWith(completed: Boolean) {
        if (answered) return
        answered = true
        val asked = request
        if (asked == null) {
            finish()
            return
        }
        val domains = cookieDomains(asked)
        SessionCookies.read(domains) { cookies ->
            val args = JSONObject().put("token", token).put("completed", completed).put("cookies", cookies)
                .put("downloads", JSONArray(captured))
            engine.settings.userAgentString?.let { args.put("userAgent", it) }
            Thread { DroidtopLink.call("web.view", "finish", args) }.start()
            SessionCookies.clear(domains)
            close()
        }
    }

    private fun close() {
        session?.unregister(observer)
        session?.close()
        session = null
        view?.release()
        view = null
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (canGoBack) session?.goBack() else finishWith(completed = !endsWithDone)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        // Gone without an answer: droidtop is told nothing was done, and the session leaves the private store.
        if (!answered && isFinishing && request != null) {
            answered = true
            Thread { DroidtopLink.call("web.view", "finish", JSONObject().put("token", token).put("completed", false)) }.start()
            request?.let { SessionCookies.clear(cookieDomains(it)) }
        }
        session?.close()
        view?.release()
        super.onDestroy()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun cookieDomains(asked: JSONObject): List<String> =
        (asked.optJSONArray("domains").strings() + (asked.optJSONObject("cookies")?.keys()?.asSequence()?.toList() ?: emptyList())).distinct()

    private fun JSONArray?.strings(): List<String> = this?.let { list -> (0 until list.length()).map { list.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()

    companion object {
        /** The extra droidtop's intent carries (droidtop's WebViewProviders.EXTRA_TOKEN). */
        const val EXTRA_TOKEN = "dev.droidtop.pluginhost.web.token"
    }
}
