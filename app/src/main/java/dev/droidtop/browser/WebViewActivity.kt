package dev.droidtop.browser

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import org.mozilla.fenix.ext.components

/** Activity fallback; embedded and activity pages use the same browser-owned session controller. */
class WebViewActivity : Activity() {
    private var page: WebSessionView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return finish()
        page = WebSessionView(this, components.core.engine, ::setContentView, ::finish,
            alive = { !isFinishing && !isDestroyed },
            bodyDownloads = WebDownloadEndpoint.activitySink()).also { it.open(token) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            page?.back()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        page?.destroy()
        page = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TOKEN = "dev.droidtop.pluginhost.web.token"
    }
}
