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
        // also install on newer IDEs such as WebStorm 2026.2 (build WS-262.*). IJP otherwise
        // auto-derives `untilBuild="232.*"` from the SDK, which blocks install on 262. Override it
        // here. Bump `untilBuild` (or set it to `provider { null }` for no upper bound) when
        // targeting a newer IDE major.
        ideaVersion {
            sinceBuild = "232"
            untilBuild = "262.*"
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
    val nativeLib =
        file("../native/resvg_bridge/target/release/$nativeFileName").takeIf { it.exists() }
            ?: file("../native/resvg_bridge/target/debug/$nativeFileName").takeIf { it.exists() }
    if (nativeLib != null) {
        project.copy {
            from(nativeLib)
            into(layout.buildDirectory.dir("resources/main"))
        }
    }
}

// Rename the distributable zip. By default its base name is the Gradle subproject
// name ("plugin"), giving "plugin-<version>.zip". Override it to something meaningful.
tasks.named<org.gradle.api.tasks.bundling.AbstractArchiveTask>("buildPlugin") {
    archiveBaseName.set("svg-editor-plugin")
}
