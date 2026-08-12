plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.example.svgeditor"
version = "0.1.0"

repositories {
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
        id = "com.svgeditor"
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
    // Bundle the native resvg bridge next to the plugin classes so JNA can load it.
    // The cargo cdylib filename differs per OS, so pick the right one for the build machine.
    val osName = System.getProperty("os.name").lowercase()
    val nativeFileName = when {
        osName.contains("win") -> "resvg_bridge.dll"
        osName.contains("mac") || osName.contains("darwin") -> "libresvg_bridge.dylib"
        else -> "libresvg_bridge.so"
    }
    // cargo on Windows places the cdylib under target/{release,debug}/deps/; on other
    // platforms it may sit directly under target/{release,debug}/. Search both layouts.
    val nativeBase = file("../native/resvg_bridge/target")
    val nativeLib = listOf("release", "debug").firstNotNullOfOrNull { cfg ->
        listOf("", "deps/").firstNotNullOfOrNull { sub ->
            file("$nativeBase/$cfg/$sub$nativeFileName").takeIf { it.exists() }
        }
    }
    if (nativeLib != null) {
        project.copy {
            from(nativeLib)
            into(layout.buildDirectory.dir("resources/main"))
        }
        println("Bundled native lib: ${nativeLib.absolutePath}")
    } else {
        println("WARNING: native lib '$nativeFileName' not found under $nativeBase; the plugin will fail to load resvg at runtime")
    }
}

// Rename the distributable zip. By default its base name is the Gradle subproject
// name ("plugin"), giving "plugin-<version>.zip". Override it to something meaningful.
tasks.named<org.gradle.api.tasks.bundling.AbstractArchiveTask>("buildPlugin") {
    archiveBaseName.set("svg-editor-plugin")
}
