package com.example.svgeditor.plugin

import com.example.svgeditor.core.ResvgBridge
import com.example.svgeditor.core.SvgRenderer
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
 * primary strategy extracts it to a temp file and loads it by absolute path. The fallback
 * candidates cover local development layouts where the library sits on disk.
 */
object SvgBridgeLoader {
    fun load(): SvgRenderer {
        val errors = mutableListOf<Throwable>()

        // 1) Extract the bundled native lib from the classpath (jar) to a temp file.
        extractBundled()?.let { path ->
            try {
                return ResvgBridge.load(path)
            } catch (t: Throwable) {
                errors += t
            }
        }

        // 2) Fallback: on java.library.path, or local cargo build output (dev runs).
        val candidates =
            listOf(
                "resvg_bridge",
                "../native/resvg_bridge/target/debug/resvg_bridge",
                "../native/resvg_bridge/target/release/resvg_bridge",
            )
        for (c in candidates) {
            try {
                return ResvgBridge.load(c)
            } catch (t: Throwable) {
                errors += t
            }
        }

        val last = errors.lastOrNull()
        throw IllegalStateException(
            "Could not load resvg_bridge native library (tried classpath extraction + $candidates)",
            last,
        )
    }

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

    private fun nativeLibName(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> "resvg_bridge.dll"
            os.contains("mac") || os.contains("darwin") -> "libresvg_bridge.dylib"
            else -> "libresvg_bridge.so"
        }
    }
}
