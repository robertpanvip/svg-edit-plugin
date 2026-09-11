package com.pan.svg.plugin

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Locates the bundled `svg_easy_sidecar` executable, the plugin's **only** rendering engine.
 *
 * The sidecar is a separate process (JSON-RPC over stdio — see the `sidecar` section of
 * `DESIGN.md`), so this loader resolves a *command line* rather than a native handle. It is
 * packaged inside the plugin jar under `sidecar/<os>/<name>` (one subdir per OS, because the
 * Linux and macOS executables share the file name and must not collide in a cross-platform
 * jar) and extracted to a temp file — Java cannot execute a file from inside a jar, and the
 * executable bit does not survive a jar round-trip, so it is restored after extraction.
 *
 * Lookup order ([resolveOrNull] never throws; a null result makes the UI show
 * [NativeLibGuidePanel] instead of the canvas):
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

    /** Directory inside the IDE config dir where users may place a sidecar manually. */
    fun configDir(): File = File(PathManager.getConfigPath(), "svg-editor")

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

    /** Human-readable platform tag for guide text, e.g. "Windows (svg_easy_sidecar.exe)". */
    fun platformLabel(): String {
        val os = System.getProperty("os.name").lowercase()
        val name = sidecarFileName()
        return when {
            os.contains("win") -> "Windows ($name)"
            os.contains("mac") || os.contains("darwin") -> "macOS ($name)"
            else -> "Linux ($name)"
        }
    }

    private val lock = Any()
    private val attempts = mutableListOf<String>()

    /**
     * Resolved executable path, cached process-wide once found. Resolving extracts the bundled
     * binary to a temp file, which the preloader ([SvgNativePreloader]) pays for in the
     * background so the first editor open is instant. Only successes are cached: if nothing was
     * found the user may still drop a binary into the config dir, and the next open should retry.
     */
    @Volatile private var cached: String? = null

    private fun record(what: String, ok: Boolean, error: Throwable?) {
        val line = if (ok) "$what  ✓" else "$what  ✗ ${error?.message ?: "not found"}"
        synchronized(lock) { attempts += line }
        if (ok) LOG.info("sidecar: $line") else LOG.warn("sidecar: $line", error)
    }

    /** Absolute path of an executable sidecar binary, or null when none is found. */
    fun resolveOrNull(): String? {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            return resolveOnce()?.also { cached = it }
        }
    }

    private fun resolveOnce(): String? {
        attempts.clear()

        // 1) Explicit override: -Dsvg.editor.sidecar=/abs/path
        System.getProperty(SIDECAR_PATH_PROPERTY)?.takeIf { it.isNotBlank() }?.let { path ->
            val f = File(path)
            if (f.isFile && f.canExecute()) {
                record("$SIDECAR_PATH_PROPERTY=$path", true, null)
                return f.absolutePath
            }
            record("$SIDECAR_PATH_PROPERTY=$path (not an executable file)", false, null)
        }

        // 2) Bundled in the plugin jar.
        extractBundled()?.let {
            record("bundled ${resourceDir()}/${sidecarFileName()}", true, null)
            return it
        }
        record("bundled ${resourceDir()}/${sidecarFileName()}", false, null)

        // 3) IDE config dir: <config>/svg-editor/sidecar/<name> (manual installs).
        val manual = configDir().resolve("sidecar").resolve(sidecarFileName())
        if (manual.isFile && manual.canExecute()) {
            record(manual.absolutePath, true, null)
            return manual.absolutePath
        }
        record(manual.absolutePath, false, null)

        // 4) Local cargo build output (dev runs inside the repository).
        val name = sidecarFileName()
        for (c in listOf("../native/resvg_bridge/target/release/$name", "../native/resvg_bridge/target/debug/$name")) {
            val dev = File(c)
            if (dev.isFile && dev.canExecute()) {
                record(dev.absolutePath, true, null)
                return dev.absolutePath
            }
            record(dev.absolutePath, false, null)
        }
        LOG.warn("sidecar: no executable found\n${describeAttempts()}")
        return null
    }

    /** Multi-line description of the attempts made by the last [resolveOrNull] call. */
    fun describeAttempts(): String = synchronized(lock) { attempts.joinToString("\n") }

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
            record("extract $resPath", false, t)
            null
        }
    }
}
