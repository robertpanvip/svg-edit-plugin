plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // The editor core (renderer interface, panel, engine, collision detection).
    implementation(project(":core"))
    // FlatLaf: the same Look & Feel IntelliJ IDEA ships with (FlatIntelliJLaf = IntelliJ Light,
    // FlatDarculaLaf = Darcula). Makes the standalone app look IDEA-consistent.
    implementation("com.formdev:flatlaf:3.7.2")
    // JNA for the native resvg bridge at runtime. The `core` module declares JNA as `compileOnly`
    // (the real plugin gets it from the IntelliJ platform); this standalone harness must supply
    // its own copy to run the same pipeline outside the IDE.
    implementation("net.java.dev.jna:jna:5.14.0")
}

kotlin {
    jvmToolchain(17)
}

application {
    // Top-level `fun main` in AppMain.kt -> class name `AppMainKt`.
    mainClass.set("com.example.svgeditor.app.AppMainKt")
}

// ---- Standalone executable packaging -------------------------------------------
// The `core` engine + this `app` entry point form a small, IntelliJ-free runtime that can be
// packaged into a double-clickable `.exe` (with a bundled JRE) via `jpackage`.

val stageDir = layout.buildDirectory.dir("stage")

// Gather every runtime jar (app, core, kotlin-stdlib, jna) into one folder for jpackage.
val stageJars by tasks.registering(Sync::class) {
    dependsOn(tasks.jar, project(":core").tasks.named("jar"))
    from(configurations.runtimeClasspath)
    from(tasks.jar)
    into(stageDir)
}

val isWindows = System.getProperty("os.name").contains("Windows", ignoreCase = true)

val jpackageBin =
    run {
        val name = if (isWindows) "jpackage.exe" else "jpackage"
        val inJdk = File(System.getProperty("java.home"), "bin/$name")
        if (inJdk.exists()) inJdk.absolutePath else name
    }

// Bundle the native resvg bridge inside app.jar (resource `native/<lib>`), mirroring the
// plugin's wiring: `loadRenderer()` extracts it to a temp file and loads by absolute path,
// which is what makes the jpackage image self-contained.
val nativeFileName = when {
    isWindows -> "resvg_bridge.dll"
    System.getProperty("os.name").lowercase().contains("mac") -> "libresvg_bridge.dylib"
    else -> "libresvg_bridge.so"
}
val nativeTarget = rootDir.resolve("native/resvg_bridge/target")

val buildNative by tasks.registering(Exec::class) {
    group = "distribution"
    description = "cargo build --release the resvg_bridge cdylib bundled into app.jar."
    workingDir = rootDir.resolve("native/resvg_bridge")
    commandLine("cargo", "build", "--release")
}

tasks.jar {
    dependsOn(buildNative)
    from(nativeTarget.resolve("release").resolve(nativeFileName)) { into("native") }
}

// `./gradlew :app:packageExe` -> build/SvgEditor/SvgEditor.exe (bundled JRE, self-contained).
val packageExe by tasks.registering(Exec::class) {
    dependsOn(stageJars)
    group = "distribution"
    description = "Package a self-contained SvgEditor.exe (jpackage, bundled JRE)."
    doFirst {
        // jpackage aborts if the target image directory already exists.
        delete(layout.buildDirectory.dir("dist/SvgEditor"))
    }
    val jpackageArgs = mutableListOf(
        jpackageBin,
        "--name", "SvgEditor",
        "--input", stageDir.get().asFile.absolutePath,
        "--main-jar", tasks.jar.get().archiveFile.get().asFile.name,
        "--main-class", "com.example.svgeditor.app.AppMainKt",
        "--type", "app-image",
        "--dest", layout.buildDirectory.dir("dist").get().asFile.absolutePath,
        "--app-version", "0.1.0",
        "--vendor", "svg-editor",
        "--add-modules", "ALL-MODULE-PATH",
    )
    // `--win-console` is a Windows-only jpackage option.
    if (isWindows) jpackageArgs += "--win-console"
    commandLine(jpackageArgs)
}

// Zip the app image for easy sharing: build/dist/SvgEditor.zip
val packageExeZip by tasks.registering(Zip::class) {
    dependsOn(packageExe)
    from(layout.buildDirectory.dir("dist/SvgEditor"))
    archiveFileName.set("SvgEditor.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
}

