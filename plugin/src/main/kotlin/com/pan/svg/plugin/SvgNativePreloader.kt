package com.pan.svg.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * Pre-heats the native renderer in the background so the first SVG you open does not block on
 * "preparing the native renderer".
 *
 * Rendering used to be lazily initialized: the very first open extracted JNA's `jnidispatch`
 * and the bundled resvg lib and loaded it through JNA, which on a cold cache froze the panel
 * behind a loading label for a while. Because [SvgBridgeLoader] caches the loaded renderer
 * process-wide, doing that one-time work here (on the application's pooled executor, never on
 * the EDT) means any later [SvgPreviewPanel] / tool window query hits the cache instantly.
 *
 * This runs after IDE startup on a background thread, so it never blocks the EDT or the IDE's
 * normal startup path. If loading fails (e.g. a platform without a bundled lib) the cached
 * value stays null and the normal `loadOrNull()` retry on open handles it — the warm-up is a
 * best-effort optimization only.
 */
class SvgNativePreloader : StartupActivity {
    override fun runActivity(project: Project) {
        val app = ApplicationManager.getApplication()
        if (app.isDisposed) return
        // Background thread: JNA bootstrap + native lib load are I/O and native-binding heavy.
        app.executeOnPooledThread {
            if (app.isDisposed) return@executeOnPooledThread
            LOG.info("preload: warming up native renderer")
            val start = System.nanoTime()
            val ok = SvgBridgeLoader.loadOrNull() != null
            val ms = (System.nanoTime() - start) / 1_000_000
            LOG.info("preload: native renderer ready=${if (ok) "yes" else "no"} in ${ms}ms")
        }
    }

    private companion object {
        private val LOG = Logger.getInstance("SvgEasy")
    }
}