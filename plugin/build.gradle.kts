plugins {
   kotlin("jvm") version "2.1.21"
   id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = "com.example.svgeditor"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
}

kotlin {
    jvmToolchain(17)
}

intellij {
    // IntelliJ Platform version to build against
    version.set("2023.2.5")
    // Use Community edition (IC). Change to "IU" for Ultimate if needed.
    type.set("IC")
    plugins.set(listOf())
    downloadSources.set(true)
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
