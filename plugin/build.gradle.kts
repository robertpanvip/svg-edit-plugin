plugins {
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.example.svgeditor"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        marketplace()
        // IDE installer Ivy repo (https://download.jetbrains.com) — required to resolve
        // `idea:ideaIC:<version>` produced by `intellijIdeaCommunity(...)`. Without it Gradle
        // only looks in mavenCentral/marketplace and fails to find the SDK tarball.
        jetbrainsIdeInstallers()
        releases()
    }
}

dependencies {
    implementation(project(":core"))

    intellijPlatform {
        // The IntelliJ SDK used to build & run the plugin.
        // NOTE: in IntelliJ Platform Gradle Plugin 2.1.0 the dependency function is named
        // `intellijIdeaCommunity` (later versions renamed it to `ideaCommunity`).
        intellijIdeaCommunity("2023.2.5")
    }
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        id = "com.example.svgeditor"
        name = "SVG Editor"
        version = project.version as String
        vendor {
            name = "example"
            email = "dev@example.com"
        }
    }
    // Bundle the native resvg bridge next to the plugin classes so JNA can load it.
    // Adjust the path/extension for your platform (debug vs release, .so/.dylib).
    val nativeLib =
        file("../native/resvg_bridge/target/release/resvg_bridge.dll").takeIf { it.exists() }
            ?: file("../native/resvg_bridge/target/debug/resvg_bridge.dll").takeIf { it.exists() }
    if (nativeLib != null) {
        project.copy {
            from(nativeLib)
            into(layout.buildDirectory.dir("resources/main"))
        }
    }
}
