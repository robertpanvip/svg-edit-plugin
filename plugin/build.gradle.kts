plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.pan.svg"
version = "0.6.23"

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

    // The IntelliJ Platform provides kotlin-stdlib at runtime (see gradle.properties), so it is
    // compile-only here — bundling it added ~1.6 MB to every plugin zip.
    compileOnly(kotlin("stdlib"))

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
    compilerOptions {
        // Runs on the platform's bundled kotlin-stdlib 1.9.0 (IDEA 2023.2) — reject any stdlib API
        // newer than 1.9 at compile time, so a missing symbol can never surface at runtime.
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.pan.svg"
        name = "SVG Editor"
        version = project.version as String
        vendor {
            name = "PAN"
            email = "robertpanvip@163.com"
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
    // NOTE: no native cdylib is bundled. The plugin ships a single engine — the
    // `svg_easy_sidecar` executable (see the SidecarLoader section below) — which serves render,
    // layout and hit testing. Packing a JNA cdylib as well would add a second full copy of
    // resvg+usvg per platform for an engine nothing calls.
}

// ---- Sidecar executable bundling ------------------------------------------------
// The Rust `svg_easy_sidecar` binary is the plugin's only rendering engine, bundled per OS
// under resources/main/sidecar/<os>/ (Linux & macOS share the file name, so they must not share
// a jar root). SidecarLoader extracts the right subdir at runtime. At least one binary is
// required — without it the plugin can only show the "engine not available" guide — so an empty
// result fails the build instead of silently producing a useless zip.
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
if (sidecarFiles.isEmpty()) {
    throw GradleException(
        "No svg_easy_sidecar found under $sidecarBase — build it first (cargo build --release --bin svg_easy_sidecar)",
    )
}
tasks.register<Copy>("copySidecarBinaries") {
    sidecarFiles.forEach { (os, f) ->
        from(f) { into("sidecar/$os") }
    }
    into(layout.buildDirectory.dir("resources/main"))
}
tasks.named("processResources") { dependsOn("copySidecarBinaries") }
tasks.named("buildPlugin") { dependsOn("processResources") }

// Rename the distributable zip. By default its base name is the Gradle subproject
// name ("plugin"), giving "plugin-<version>.zip". Override it to something meaningful.
tasks.named<org.gradle.api.tasks.bundling.AbstractArchiveTask>("buildPlugin") {
    archiveBaseName.set("svg-editor-plugin")
}
