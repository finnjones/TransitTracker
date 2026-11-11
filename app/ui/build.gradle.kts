plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    // JVM: screenshot harness and tests today. androidTarget() and the iOS targets are added
    // with the Android shell; keep commonMain free of java.* so they compile.
    jvm()

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
