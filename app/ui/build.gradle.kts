plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
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
