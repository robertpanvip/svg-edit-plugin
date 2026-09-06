package com.example.svgeditor.plugin

import com.example.svgeditor.core.ResvgBridge
import com.example.svgeditor.core.SvgRenderer
import com.intellij.openapi.application.PathManager
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Loads the bundled `resvg_bridge` native library (JNA) used by
 * [com.example.svgeditor.core.SvgEditorPanel] for SVG rendering.
 * Shared by both the tool window and the editor so the lookup logic lives in one place.
 *
 * The native library is packaged **inside the plugin jar** (e.g. `resvg_bridge.dll` at the jar
 * root). JNA can only load a library from the file system, not from inside a zip/jar, so the
 * primary strategy extracts it to a temp file and loads it by absolute path. Note that the jar
 * only contains the library for the OS the plugin zip was **built on** — that is why the
 * further lookup steps exist (see below).
 *
 * Lookup order ([loadOrNull] never throws; UI callers degrade to [NativeLibGuidePanel]):
 *  1. `-Dsvg.editor.native.lib=/abs/path` — explicit override via VM option.
 *  2. The bundled library extracted from the plugin jar.
 *  3. `<IDE config dir>/svg-editor/<nativeLibName>` — users may drop a manually provided
 *     library there (e.g. a Windows-built `resvg_bridge.dll`).
 *  4. `resvg_bridge` on the regular library path.
 *  5. Local cargo build output (dev runs inside the repository).
 *
 * Every attempt is recorded; [describeAttempts] renders them for the guide panel / logs.
 */
object SvgBridgeLoader {
    /** VM option pointing at an explicit native library file, e.g. `-Dsvg.editor.native.lib=C:\libs\resvg_bridge.dll`. */
    const val LIB_PATH_PROPERTY = "svg.editor.native.lib"

    /** Directory inside the IDE config dir where users may place a native library manually. */
    fun configDir(): File = File(PathManager.getConfigPath(), "svg-editor")

    /** Platform-specific library file name, resolved for the machine running the IDE. */
    fun nativeLibName(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> "resvg_bridge.dll"
            os.contains("mac") || os.contains("darwin") -> "libresvg_bridge.dylib"
            else -> "libresvg_bridge.so"
        }
    }

    /** Human-readable platform tag for guide text, e.g. "Windows (resvg_bridge.dll)". */
    fun platformLabel(): String {
        val os = System.getProperty("os.name").lowercase()
        val name = nativeLibName()
        return when {
            os.contains("win") -> "Windows ($name)"
            os.contains("mac") || os.contains("darwin") -> "macOS ($name)"
            else -> "Linux ($name)"
        }
    }

    private val attempts = mutableListOf<String>()

    /** Records one lookup attempt as `path → result`. */
    private fun record(what: String, ok: Boolean, error: Throwable?) {
        attempts += if (ok) "$what  ✓" else "$what  ✗ ${error?.message ?: "not found"}"
    }

    /** Returns the loaded renderer, or null when every lookup fails (see [describeAttempts]). */
    fun loadOrNull(): SvgRenderer? {
        attempts.clear()

        // 0) Explicit override: -Dsvg.editor.native.lib=/abs/path
        System.getProperty(LIB_PATH_PROPERTY)?.takeIf { it.isNotBlank() }?.let { path ->
            try {
                return ResvgBridge.load(path).also { record("$LIB_PATH_PROPERTY=$path", true, null) }
            } catch (t: Throwable) {
                record("$LIB_PATH_PROPERTY=$path", false, t)
            }
        }

        // 1) Extract the bundled native lib from the classpath (jar) to a temp file.
        extractBundled()?.let { path ->
            try {
                return ResvgBridge.load(path).also { record("bundled $path", true, null) }
            } catch (t: Throwable) {
                record("bundled (jar) $path", false, t)
            }
        } ?: record("bundled (jar) ${nativeLibName()}", false, null)

        // 2) IDE config dir: <config>/svg-editor/<lib> — where users drop a manual library.
        val configCandidate = configDir().resolve(nativeLibName())
        if (configCandidate.isFile) {
            try {
                return ResvgBridge.load(configCandidate.absolutePath)
                    .also { record(configCandidate.absolutePath, true, null) }
            } catch (t: Throwable) {
                record(configCandidate.absolutePath, false, t)
            }
        } else {
            record(configCandidate.absolutePath, false, null)
        }

        // 3) On the regular library path, or local cargo build output (dev runs).
        val candidates =
            listOf(
                "resvg_bridge",
                "../native/resvg_bridge/target/debug/resvg_bridge",
                "../native/resvg_bridge/target/release/resvg_bridge",
            )
        for (c in candidates) {
            try {
                return ResvgBridge.load(c).also { record(c, true, null) }
            } catch (t: Throwable) {
                record(c, false, t)
            }
        }
        return null
    }

    /** Multi-line description of the attempts made by the last [loadOrNull] call. */
    fun describeAttempts(): String = attempts.joinToString("\n")

    /** Extract `/<platform-lib-name>` from the classpath to a temp file; returns its absolute path. */
    private fun extractBundled(): String? {
        val name = nativeLibName()
        val resource = SvgBridgeLoader::class.java.getResourceAsStream("/$name") ?: return null
        return try {
            val ext = name.substringAfterLast('.', "")
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            val tmp = Files.createTempFile("resvg_bridge-", suffix).toFile()
            tmp.deleteOnExit()
            resource.use { input ->
                Files.copy(input, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            // Make the temp dir discoverable by the OS loader / JNA in case the dll has
            // sibling dependencies.
            val parent = tmp.parent
            val existing = System.getProperty("jna.library.path", "")
            if (parent !in existing.split(File.pathSeparator)) {
                System.setProperty(
                    "jna.library.path",
                    if (existing.isEmpty()) parent else "$existing${File.pathSeparator}$parent",
                )
            }
            tmp.absolutePath
        } catch (_: Throwable) {
            null
        }
    }
}
