package dev.droidtop.browser

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.compose.runtime.Composable
import androidx.work.Configuration
import mozilla.components.compose.base.theme.Theme
import org.mozilla.fenix.FenixApplication
import org.mozilla.fenix.components.Components

/** The restricted renderer process must never initialize Fenix's ordinary runtime/profile as a side effect. */
class DroidtopBrowserApplication : FenixApplication() {
    override fun initializeFenixProcess() {
        if (!HiddenProcess.owns(this)) super.initializeFenixProcess()
    }

    override val components: Components
        get() {
            check(!HiddenProcess.owns(this)) { "The restricted web process cannot access ordinary Browser components" }
            return super.components
        }

    override val workManagerConfiguration: Configuration
        get() {
            check(!HiddenProcess.owns(this)) { "The restricted web process cannot initialize Browser workers" }
            return super.workManagerConfiguration
        }

    @Composable
    override fun provideTheme(): Theme {
        check(!HiddenProcess.owns(this)) { "The restricted web process cannot initialize Browser UI" }
        return super.provideTheme()
    }
}

internal object HiddenProcess {
    const val SUFFIX = ":droidtop_web"

    fun owns(context: Context): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        return name == context.packageName + SUFFIX
    }
}
