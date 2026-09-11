plugins {
   kotlin("jvm")
}

group = "com.pan.svg"
version = "0.3.2"

repositories {
    maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
    mavenCentral()
}

dependencies {
    // JNA is used to call the resvg native bridge (resvg_bridge.dll / .so).
    // compileOnly: the IntelliJ Platform ships its own JNA (lib/util-8.jar) and plugins must
    // NOT bundle a copy — the platform loader then hides ours and com.sun.jna.Native fails to
    // initialize ("Unable to locate JNA native support library" at runtime). Tests still need it.
    compileOnly("net.java.dev.jna:jna:5.14.0")
    testImplementation("net.java.dev.jna:jna:5.14.0")

    // The Kotlin stdlib is NOT bundled: the IntelliJ Platform provides it at runtime (see
    // gradle.properties). `compileOnly` keeps it on the compile classpath only.
    compileOnly(kotlin("stdlib"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
  // Gradle 9 requires JUnit Platform launcher
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // core is packaged into the plugin, where it runs against the platform's bundled
        // kotlin-stdlib 1.9.0 — reject any stdlib API newer than 1.9 at compile time.
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
    }
}

tasks.test {
    useJUnitPlatform()
    // Surface test results even when run headless in CI.
    testLogging { events("passed", "skipped", "failed") }
}
