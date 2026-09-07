plugins {
    // Pin the Kotlin Gradle Plugin version ONCE for all subprojects. Declaring (possibly
    // diverging) versions per subproject loads KGP multiple times and previously let CI
    // compile :plugin with an older Kotlin than :core, failing with
    // "compiled with an incompatible version of Kotlin (metadata 2.1.0, expected 1.9.0)".
    kotlin("jvm") version "2.1.21" apply false
}
