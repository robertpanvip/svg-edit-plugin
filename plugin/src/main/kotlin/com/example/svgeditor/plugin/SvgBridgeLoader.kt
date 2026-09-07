package com.example.svgeditor.plugin

import com.example.svgeditor.core.ResvgBridge
import com.example.svgeditor.core.SvgRenderer
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * Loads the bundled `resvg_bridge` native library (JNA) used by
 * [com.example.svgeditor.core.SvgEditorPanel] for SVG rendering.
 * Shared by both the tool window and the editor so the lookup logic lives in one place.
 *
 * The native library is packaged **inside the plugin jar** (e.g. `resvg_bridge.dll` at the jar
 * root). JNA can only load a library from the file system, not from inside a zip/jar, so the
 * primary strategy extracts it to a temp file and loads it by absolute path.
 *
 * Lookup order ([loadOrNull] never throws; UI callers degrade to [NativeLibGuidePanel]):
 *  1. `-Dsvg.editor.native.lib=/abs/path` — explicit override via VM option.
 *  2. The bundled library extracted from the plugin jar.
 *  3. `<IDE config dir>/svg-editor/<nativeLibName>` — users may drop a manually provided
 *     library there (e.g. a Windows-built `resvg_bridge.dll`).
 *  4. `resvg_bridge` on the regular library path.
 *  5. Local cargo build output (dev runs inside the repository).
 *
 * Before any JNA call, [bootstrapJna] prepares the platform JNA runtime: on some IDE builds
 * (observed on 2026.2) `com.sun.jna.Native.<clinit>` fails with
 * `UnsatisfiedLinkError: Unable to locate JNA native support library` because JNA cannot find or
 * extract its own `jnidispatch` bootstrap library (natives not reachable via the plugin
 * classloader and/or a blocked temp dir). We locate that library ourselves — classpath first,
 * then every jar under `<IDE home>/lib` — extract it to the (always writable) IDE config dir and
 * point JNA at it via `jna.boot.library.path` / `jna.tmpdir` **before** the first `Native` touch.
 *
 * Every attempt is recorded; [describeAttempts] renders them for the guide panel, and each step
 * is mirrored to the `SvgEasy` logger category in idea.log.
 */
object SvgBridgeLoader {
    private val LOG = Logger.getInstance("SvgEasy")

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

    /** Records one lookup attempt as `path → result` and mirrors it to idea.log. */
    private fun record(what: String, ok: Boolean, error: Throwable?) {
        val line = if (ok) "$what  ✓" else "$what  ✗ ${error?.message ?: "not found"}"
        attempts += line
        if (ok) LOG.info("bridge: $line") else LOG.warn("bridge: $line", error)
    }

    /** Returns the loaded renderer, or null when every lookup fails (see [describeAttempts]). */
    fun loadOrNull(): SvgRenderer? {
        attempts.clear()
        LOG.info("bridge: loadOrNull start, platform=${platformLabel()}, override=${System.getProperty(LIB_PATH_PROPERTY)}")
        bootstrapJna()

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
        LOG.warn("bridge: all lookups failed\n${describeAttempts()}")
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
            LOG.info("bridge: extracted bundled $name -> ${tmp.absolutePath} (${tmp.length()} bytes)")
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
        } catch (t: Throwable) {
            record("bundled (jar) extract", false, t)
            null
        }
    }

    // ---- JNA bootstrap ----

    private data class JnaDispatchNative(val resourceDir: String, val fileName: String)

    private fun jnaDispatchCandidates(): List<JnaDispatchNative> {
        val os = System.getProperty("os.name").lowercase()
        val aarch64 =
            System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm64") }
        return when {
            os.contains("win") ->
                listOf(
                    JnaDispatchNative(
                        if (aarch64) "com/sun/jna/win32-aarch64" else "com/sun/jna/win32-x86-64",
                        "jnidispatch.dll",
                    ),
                )
            os.contains("mac") || os.contains("darwin") ->
                if (aarch64) {
                    listOf(
                        JnaDispatchNative("com/sun/jna/darwin-aarch64", "libjnidispatch.dylib"),
                        JnaDispatchNative("com/sun/jna/darwin", "libjnidispatch.dylib"),
                    )
                } else {
                    listOf(JnaDispatchNative("com/sun/jna/darwin", "libjnidispatch.dylib"))
                }
            else ->
                listOf(
                    JnaDispatchNative(
                        if (aarch64) "com/sun/jna/linux-aarch64" else "com/sun/jna/linux-amd64",
                        "libjnidispatch.so",
                    ),
                )
        }
    }

    /**
     * Makes sure `com.sun.jna.Native` can complete its static initializer: locates the
     * `jnidispatch` bootstrap library (classpath resource first, then every jar under
     * `<IDE home>/lib`), extracts it to `<config>/svg-editor/native/` and sets
     * `jna.boot.library.path` + `jna.tmpdir` so JNA finds it even when its own extraction
     * path is broken. Harmless when JNA is already initialized (properties are only read
     * during `Native.<clinit>`).
     */
    private fun bootstrapJna() {
        try {
            val bootDir = configDir().resolve("native")
            for (candidate in jnaDispatchCandidates()) {
                val target = bootDir.resolve(candidate.fileName)
                if (target.isFile && target.length() > 0L) {
                    setJnaBootProps(bootDir, "already extracted: ${target.absolutePath}")
                    return
                }
                val resource = "${candidate.resourceDir}/${candidate.fileName}"
                var bytes = SvgBridgeLoader::class.java.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
                if (bytes == null) {
                    val fromLibJar = readFromIdeLibJars(resource)
                    if (fromLibJar != null) {
                        LOG.info("bridge: found $resource in ${fromLibJar.first} (${fromLibJar.second.size} bytes)")
                        bytes = fromLibJar.second
                    }
                }
                if (bytes != null) {
                    bootDir.mkdirs()
                    target.writeBytes(bytes)
                    setJnaBootProps(bootDir, "extracted $resource -> ${target.absolutePath}")
                    return
                }
            }
            record("jna bootstrap: ${jnaDispatchCandidates().firstOrNull()?.fileName} not found in classpath/IDE lib (falling back to JNA defaults)", false, null)
        } catch (t: Throwable) {
            record("jna bootstrap failed", false, t)
        }
    }

    private fun setJnaBootProps(bootDir: File, detail: String) {
        System.setProperty("jna.boot.library.path", bootDir.absolutePath)
        System.setProperty("jna.tmpdir", bootDir.absolutePath)
        record("jna bootstrap: $detail", true, null)
    }

    private fun readFromIdeLibJars(resource: String): Pair<String, ByteArray>? {
        val libDir = File(PathManager.getHomePath(), "lib")
        val jars =
            libDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }
                ?.sortedBy { it.name }
                ?: return null.also { LOG.warn("bridge: IDE lib dir not found: ${libDir.absolutePath}") }
        for (jar in jars) {
            val bytes =
                runCatching {
                    ZipFile(jar).use { zip ->
                        val entry = zip.getEntry(resource) ?: return@use null
                        zip.getInputStream(entry).use { it.readBytes() }
                    }
                }.getOrNull()
            if (bytes != null) return jar.name to bytes
        }
        return null
    }
}
