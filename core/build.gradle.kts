plugins {
   kotlin("jvm") version "2.1.21"
}

group = "com.example.svgeditor"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // JNA is used to call the resvg native bridge (resvg_bridge.dll / .so).
    implementation("net.java.dev.jna:jna:5.14.0")

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
