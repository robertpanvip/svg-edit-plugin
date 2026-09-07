plugins {
   kotlin("jvm")
}

group = "com.example.svgeditor"
version = "0.3.0"

repositories {
    mavenCentral()
}

dependencies {
    // JNA is used to call the resvg native bridge (resvg_bridge.dll / .so).
    // compileOnly: the IntelliJ Platform ships its own JNA (lib/util-8.jar) and plugins must
    // NOT bundle a copy — the platform loader then hides ours and com.sun.jna.Native fails to
    // initialize ("Unable to locate JNA native support library" at runtime). Tests still need it.
    compileOnly("net.java.dev.jna:jna:5.14.0")
    testImplementation("net.java.dev.jna:jna:5.14.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
  // Gradle 9 requires JUnit Platform launcher
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    // Surface test results even when run headless in CI.
    testLogging { events("passed", "skipped", "failed") }
}
