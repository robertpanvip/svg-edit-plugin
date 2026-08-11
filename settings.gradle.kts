rootProject.name = "svg-editor-plugin"

include("core")
include("app")
include("plugin")
// NOTE: `plugin` is intentionally excluded here while developing/testing the standalone `app`
// runtime, because its IntelliJ Platform Gradle Plugin requires downloading the IDEA SDK.
// Re-add `include("plugin")` for a full plugin build (network required).

