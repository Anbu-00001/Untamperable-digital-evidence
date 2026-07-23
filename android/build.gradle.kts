// Root build file. Plugins are declared here (with versions from the version
// catalog) but applied `false` — each module applies the ones it needs. This
// keeps every version in gradle/libs.versions.toml, never in a module file.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
