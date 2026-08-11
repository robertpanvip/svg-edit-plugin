rootProject.name = "svg-editor-plugin"

include("core")
include("app")

// The `plugin` module (IntelliJ Platform plugin) is excluded by default so the standalone `app`
// runtime can be built/tested in the sandbox without downloading the IDEA SDK. Enable it by ANY of
// these, then re-sync Gradle in IDEA:
//   1) env var:        SVG_DEV_PLUGIN=true
//   2) gradle property: add `includePlugin=true` to a LOCAL gradle.properties (do not commit it)
//   3) marker file:     create an empty `.include-plugin` in the project root (do not commit it)
//   4) CI:              GitHub Actions sets CI=true / GITHUB_ACTIONS=true, so the plugin is built
//                       automatically in CI (which has network for the IDEA SDK).
// The sandbox sets none of the local switches (1-3) and is not CI, so it keeps building `app` only.
val includePlugin = listOf(
    providers.environmentVariable("SVG_DEV_PLUGIN").getOrElse("false"),
    providers.gradleProperty("includePlugin").getOrElse("false"),
    if (file(".include-plugin").exists()) "true" else "false",
    providers.environmentVariable("CI").getOrElse("false"),
    providers.environmentVariable("GITHUB_ACTIONS").getOrElse("false"),
).any { it.equals("true", ignoreCase = true) }

if (includePlugin) {
    include("plugin")
}
