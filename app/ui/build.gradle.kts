import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// Android target only when settings.gradle.kts found an Android SDK (:androidApp uses it).
val androidEnabled = gradle.extra["tt.android"] == true
if (androidEnabled) apply(plugin = "com.android.kotlin.multiplatform.library")

kotlin {
    jvmToolchain(21)
    // JVM: screenshot harness and tests. Android: the app (:androidApp). iOS targets come later;
    // keep commonMain free of java.* so they compile.
    jvm()
    if (androidEnabled) {
        (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
            namespace = "app.transittracker.ui"
            compileSdk = libs.versions.android.compileSdk.get().toInt()
            minSdk = libs.versions.android.minSdk.get().toInt()
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            // Compose resources (fonts, sprites, licences) are packaged as Android resources/assets.
            androidResources { enable = true }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:core"))
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.ui.test)
        }
    }
}

compose.resources {
    packageOfResClass = "app.transittracker.ui.res"
}

// ---- Screenshot harness: ./gradlew :app:ui:screenshots  ->  app/ui/build/screenshots/*.png
val screenshotRuntime: Configuration by configurations.creating

dependencies {
    screenshotRuntime(compose.desktop.currentOs)
}

tasks.register<JavaExec>("screenshots") {
    group = "verification"
    description = "Renders the screenshot catalogue (galleries, vehicles, strips) to build/screenshots."
    val main = kotlin.jvm().compilations.getByName("main")
    dependsOn(main.compileTaskProvider, tasks.named("jvmProcessResources"))
    classpath = files(main.output.allOutputs, main.runtimeDependencyFiles, screenshotRuntime)
    mainClass.set("app.transittracker.ui.screenshots.ScreenshotMainKt")
    val out = layout.buildDirectory.dir("screenshots")
    args(out.get().asFile.absolutePath)
    // Optional filter: -Pshots=gallery-night,vehicles
    (project.findProperty("shots") as String?)?.let { args(it) }
    jvmArgs("-Djava.awt.headless=true")
    outputs.dir(out)
    outputs.upToDateWhen { false }
}

// ---- Desktop preview: ./gradlew :app:ui:runDesktop  (the app on fake data in a window; needs a display)
tasks.register<JavaExec>("runDesktop") {
    group = "application"
    description = "Opens the app on fake data in a desktop window (for local runs; not used by the build)."
    val main = kotlin.jvm().compilations.getByName("main")
    dependsOn(main.compileTaskProvider, tasks.named("jvmProcessResources"))
    classpath = files(main.output.allOutputs, main.runtimeDependencyFiles, screenshotRuntime)
    mainClass.set("app.transittracker.ui.desktop.DesktopMainKt")
}
