package com.pan.svg.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * Pre-heats the rendering engine in the background so the first SVG you open does not block on
 * "preparing the renderer".
 *
 * [SidecarLoader] extracts the bundled `svg_easy_sidecar` executable from the plugin jar to a
 * temp file and caches the result process-wide. Doing that one-time work here (on the
 * application's pooled executor, never on the EDT) means any later [SvgPreviewPanel] / tool
 * window query hits the cache instantly.
 *
 * This runs after IDE startup on a background thread, so it never blocks the EDT or the IDE's
 * normal startup path. If nothing is found (e.g. a platform without a bundled binary) the cached
 * value stays null and the normal `resolveOrNull()` retry on open handles it — the warm-up is a
 * best-effort optimization only.
 */
class SvgNativePreloader : StartupActivity {
    override fun runActivity(project: Project) {
        val app = ApplicationManager.getApplication()
        if (app.isDisposed) return
        // Background thread: extracting the bundled executable is disk I/O.
        app.executeOnPooledThread {
            if (app.isDisposed) return@executeOnPooledThread
            LOG.info("preload: warming up render engine")
            val start = System.nanoTime()
            val path = SidecarLoader.resolveOrNull()
            val ms = (System.nanoTime() - start) / 1_000_000
            LOG.info("preload: render engine ${if (path != null) "ready" else "missing"} in ${ms}ms")
        }
    }

    private companion object {
        private val LOG = Logger.getInstance("SvgEasy")
    }
}
