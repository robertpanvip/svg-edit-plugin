plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = "com.example.svgeditor"
version = "0.1.0"

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {

    implementation(project(":core"))

    intellijPlatform {
        // IntelliJ IDEA Community 2023.2.5
        intellijIdeaCommunity("2023.2.5")
    }
}

kotlin {
    jvmToolchain(17)
}


// Bundle the native resvg bridge next to the plugin classes so JNA can load it.
// Adjust the path/extension for your platform (debug vs release, .so/.dylib).
val nativeLib =
    file("../native/resvg_bridge/target/release/resvg_bridge.dll").takeIf { it.exists() }
        ?: file("../native/resvg_bridge/target/debug/resvg_bridge.dll").takeIf { it.exists() }

if (nativeLib != null) {
    tasks.register("copyNativeLib") {
        doLast {
            copy {
                from(nativeLib)
                into(layout.buildDirectory.dir("resources/main").get().asFile)
            }
        }
    }

    tasks.named("processResources") {
        dependsOn("copyNativeLib")
    }
}
