plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.pan.svg"
version = "0.6.17"

repositories {
    maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
    mavenCentral()
    intellijPlatform {
        // Recommended default repo set (mavenCentral + jetbrainsIdeInstallers + marketplace + ...).
        defaultRepositories()
        // JetBrains dependencies repo (asm-all etc.) required by instrumentationTools().
        intellijDependencies()
    }
}

dependencies {
    implementation(project(":core"))

    intellijPlatform {
        // The IntelliJ SDK used to build & run the plugin.
        // NOTE: in IntelliJ Platform Gradle Plugin 2.1.0 the dependency function is named
        // `intellijIdeaCommunity` (later versions renamed it to `ideaCommunity`).
        intellijIdeaCommunity("2023.2.5")
        // Required by the `instrumentCode` step (verifyPlugin / build) for bytecode instrumentation.
        instrumentationTools()
        // Plugin verifier (used by the `verifyPlugin` task to check compatibility against IDE builds).
        pluginVerifier()
    }
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        id = "com.pan.svg"
        name = "SVG Editor"
        version = project.version as String
        vendor {
            name = "example"
            email = "dev@example.com"
        }
        // Compatibility range. The plugin is compiled against IDEA 2023.2.5 (build 232) but must
        // also install on newer IDEs. IJP otherwise auto-derives `untilBuild="232.*"` from the SDK,
        // which blocks install on newer builds. `provider { null }` removes the upper bound entirely
        // so the plugin installs on any current/future IDE — at the cost of potential silent breakage
        // if a future IDE removes an API we use. Bump/`sinceBuild` guard if that happens.
        ideaVersion {
            sinceBuild = "232"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            ide("2023.2.5")
        }
    }
    // Bundle the native resvg bridges for EVERY platform we have a build for, so one plugin
    // zip installs on any OS — SvgBridgeLoader picks the right file name for the runtime OS
    // (resvg_bridge.dll / libresvg_bridge.dylib / libresvg_bridge.so). The Windows dll is
    // cross-built on Linux via `cargo build --release --target x86_64-pc-windows-gnu`
    // (RUSTFLAGS="-C target-feature=+crt-static" keeps it self-contained). macOS has no
    // cross build here; its users can drop a locally built dylib into <IDE config>/svg-editor/.
    val nativeBase = file("../native/resvg_bridge/target")
    // cargo places the cdylib under target/<triple>/release when built with --target,
    // else under target/{release,debug}[/{deps}/]. Search all layouts.
    val nativeCandidates =
        mapOf(
            "resvg_bridge.dll" to
                listOf(
                    "x86_64-pc-windows-gnu/release",
                    "x86_64-pc-windows-gnu/release/deps",
                    "release",
                    "debug",
                    "release/deps",
                    "debug/deps",
                ),
            "libresvg_bridge.dylib" to listOf("release", "debug", "release/deps", "debug/deps"),
            "libresvg_bridge.so" to listOf("release", "debug", "release/deps", "debug/deps"),
        )

    // Resolve the first existing file for each candidate name.
    val nativeLibs =
        nativeCandidates.mapNotNull { (fileName, searchPaths) ->
            val found =
                searchPaths.firstNotNullOfOrNull { sub ->
                    file("$nativeBase/$sub/$fileName").takeIf { it.exists() }
                }
            if (found != null) println("Bundled native lib: ${found.absolutePath}")
            else println("NOTE: native lib '$fileName' not found under $nativeBase (skipped)")
            found
        }
    if (nativeLibs.isEmpty()) {
        throw GradleException("No native resvg bridge found under $nativeBase — build it first (cargo build --release)")
    }
    // Copy the native libs into processResources output as a task so that `clean` + rebuild
    // reliably bundle them (a config-phase `project.copy` is lost after `clean`).
    tasks.register<Copy>("copyNativeLibs") {
        from(nativeLibs)
        into(layout.buildDirectory.dir("resources/main"))
    }
    tasks.named("processResources") { dependsOn("copyNativeLibs") }
    tasks.named("buildPlugin") { dependsOn("processResources") }
}

// ---- Sidecar executable bundling ------------------------------------------------
// The Rust `svg_easy_sidecar` binary is bundled per OS under resources/main/sidecar/<os>/
// (Linux & macOS share the file name, so they must not share a jar root). SidecarLoader
// extracts the right subdir at runtime. Optional: when no sidecar is present the plugin runs
// the legacy in-process pipeline, so a missing binary is a NOTE, never a build failure.
val sidecarBase = file("../native/resvg_bridge/target/sidecar")
val osForSidecar =
    mapOf(
        "linux" to "svg_easy_sidecar",
        "macos" to "svg_easy_sidecar",
        "windows" to "svg_easy_sidecar.exe",
    )
val currentOsName = System.getProperty("os.name").lowercase()
val currentOsKey =
    when {
        currentOsName.contains("win") -> "windows"
        currentOsName.contains("mac") || currentOsName.contains("darwin") -> "macos"
        else -> "linux"
    }
// Dev fallback: a locally `cargo build --release`d binary for the current OS lives at
// target/release/svg_easy_sidecar; CI places cross builds under target/sidecar/<os>/.
val sidecarFiles =
    osForSidecar.mapNotNull { (os, name) ->
        val dir = sidecarBase.resolve(os)
        var found = dir.resolve(name).takeIf { it.isFile }
        if (found == null && os == currentOsKey) {
            found = file("../native/resvg_bridge/target/release/$name").takeIf { it.isFile }
        }
        if (found != null) println("Bundled sidecar: ${found.absolutePath} (os=$os)")
        else println("NOTE: sidecar '$name' not found for os=$os (skipped)")
        found?.let { os to it }
    }
if (sidecarFiles.isNotEmpty()) {
    tasks.register<Copy>("copySidecarBinaries") {
        sidecarFiles.forEach { (os, f) ->
            from(f) { into("sidecar/$os") }
        }
        into(layout.buildDirectory.dir("resources/main"))
    }
    tasks.named("processResources") { dependsOn("copySidecarBinaries") }
}

// Rename the distributable zip. By default its base name is the Gradle subproject
// name ("plugin"), giving "plugin-<version>.zip". Override it to something meaningful.
tasks.named<org.gradle.api.tasks.bundling.AbstractArchiveTask>("buildPlugin") {
    archiveBaseName.set("svg-editor-plugin")
}
