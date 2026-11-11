plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.wire) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    // Android: on the build classpath always (so module scripts compile the same way everywhere),
    // applied only when settings.gradle.kts found an Android SDK (`gradle.extra["tt.android"]`).
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.google.services) apply false
}

subprojects {
    group = "app.transittracker"
    version = "0.1.0"
}
