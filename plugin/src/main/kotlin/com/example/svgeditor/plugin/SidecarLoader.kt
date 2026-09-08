package com.example.svgeditor.plugin

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Locates the bundled `svg_easy_sidecar` executable used by
 * [com.example.svgeditor.core.SvgEditorPanel] for the exact-hit-test / render / commit
 * JSON-RPC pipeline (see the `sidecar` section of `DESIGN.md`).
 *
 * Unlike the resvg cdylib (loaded by JNA, [SvgBridgeLoader]) the sidecar is a **separate
 * process**, so this loader resolves a *command line* instead of a native handle. It is
 * packaged inside the plugin jar under `sidecar/<os>/<name>` (one subdir per OS, because the
 * Linux and macOS executables share the file name and must not collide in a cross-platform
 * jar) and extracted to a temp file — like the cdylib, Java cannot execute a file from
 * inside a jar, and the executable bit does not survive a jar round-trip, so it is restored
 * after extraction.
 *
 * Lookup order ([resolveOrNull] never throws; a null result simply means the panel runs the
 * legacy in-process pipeline with the cdylib renderer):
 *  1. `-Dsvg.editor.sidecar=/abs/path` — explicit override via VM option.
 *  2. The bundled executable extracted from the plugin jar (`/sidecar/<os>/<name>`).
 *  3. `<IDE config dir>/svg-editor/sidecar/<name>` — users may drop a manually built
 *     executable there (e.g. a macOS build produced outside CI).
 *  4. Local cargo build output (dev runs inside the repository).
 */
object SidecarLoader {
    private val LOG = Logger.getInstance("SvgEasy")

    /** VM option pointing at an explicit sidecar executable, e.g. `-Dsvg.editor.sidecar=C:\bin\svg_easy_sidecar.exe`. */
    const val SIDECAR_PATH_PROPERTY = "svg.editor.sidecar"

    /** Platform executable file name (`svg_easy_sidecar` / `svg_easy_sidecar.exe`). */
    fun sidecarFileName(): String =
        if (System.getProperty("os.name").lowercase().contains("win")) "svg_easy_sidecar.exe"
        else "svg_easy_sidecar"

    /** `sidecar/<os>` jar resource directory for the machine running the IDE. */
    fun resourceDir(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> "sidecar/windows"
            os.contains("mac") || os.contains("darwin") -> "sidecar/macos"
            else -> "sidecar/linux"
        }
    }

    /** Absolute path of an executable sidecar binary, or null when none is found. */
    fun resolveOrNull(): String? {
        // 1) Explicit override: -Dsvg.editor.sidecar=/abs/path
        System.getProperty(SIDECAR_PATH_PROPERTY)?.takeIf { it.isNotBlank() }?.let { path ->
            val f = File(path)
            if (f.isFile && f.canExecute()) {
                LOG.info("sidecar: override $path")
                return f.absolutePath
            }
            LOG.warn("sidecar: override $path is not an executable file")
        }

        // 2) Bundled in the plugin jar.
        extractBundled()?.let { return it }

        // 3) IDE config dir: <config>/svg-editor/sidecar/<name> (manual installs).
        val manual = SvgBridgeLoader.configDir().resolve("sidecar").resolve(sidecarFileName())
        if (manual.isFile && manual.canExecute()) {
            LOG.info("sidecar: config dir ${manual.absolutePath}")
            return manual.absolutePath
        }

        // 4) Local cargo build output (dev runs inside the repository).
        val name = sidecarFileName()
        for (c in listOf("../native/resvg_bridge/target/release/$name", "../native/resvg_bridge/target/debug/$name")) {
            val dev = File(c)
            if (dev.isFile && dev.canExecute()) {
                LOG.info("sidecar: dev build ${dev.absolutePath}")
                return dev.absolutePath
            }
        }
        LOG.warn("sidecar: no executable found (legacy in-process pipeline will be used)")
        return null
    }

    /** Extract `/<os>/<name>` from the classpath to a temp file (with exec bit); returns its path. */
    private fun extractBundled(): String? {
        val name = sidecarFileName()
        val resPath = "${resourceDir()}/$name"
        val resource = SidecarLoader::class.java.getResourceAsStream("/$resPath") ?: return null
        return try {
            val ext = name.substringAfterLast('.', "")
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            val tmp = Files.createTempFile("svg-easy-sidecar-", suffix).toFile()
            resource.use { input ->
                Files.copy(input, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            // The executable bit does not survive a jar round-trip; restore it (no-op on Windows).
            tmp.setExecutable(true, false)
            tmp.deleteOnExit()
            LOG.info("sidecar: extracted bundled $resPath -> ${tmp.absolutePath} (${tmp.length()} bytes)")
            tmp.absolutePath
        } catch (t: Throwable) {
            LOG.warn("sidecar: extract failed for $resPath", t)
            null
        }
    }
}
