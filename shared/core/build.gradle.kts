import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Android target only when settings.gradle.kts found an Android SDK.
val androidEnabled = gradle.extra["tt.android"] == true
if (androidEnabled) apply(plugin = "com.android.kotlin.multiplatform.library")

kotlin {
    jvmToolchain(21)
    jvm()
    if (androidEnabled) {
        (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
            namespace = "app.transittracker.core"
            compileSdk = libs.versions.android.compileSdk.get().toInt()
            minSdk = libs.versions.android.minSdk.get().toInt()
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            // Robolectric tests of the Android platform glue (src/androidHostTest).
            withHostTest { isIncludeAndroidResources = true }
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
        commonMain.dependencies {
            api(project(":shared:model"))
            // Timetable pack reader: CompositeTimetable here, JvmTimetables for the JVM opener.
            api(project(":shared:timetable"))
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.serialization.protobuf)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.zstd)
        }
        if (androidEnabled) {
            getByName("androidMain").dependencies {
                implementation(libs.ktor.client.okhttp)
                // zstd-jni's Android build: the same classes with .so files for the Android ABIs.
                implementation("com.github.luben:zstd-jni:${libs.versions.zstd.get()}@aar")
            }
            getByName("androidHostTest").dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.junit4)
                implementation(libs.robolectric)
                implementation(libs.androidx.test.core)
                implementation(libs.sqldelight.sqlite.driver)
                implementation(libs.sqlite.jdbc)
                // zstd-jni's desktop jar: host tests run on the build machine's JVM, where the
                // Android .so files from the aar cannot load.
                implementation(libs.zstd)
            }
        }
    }
}

// List each Android host (Robolectric) test in the build log, so CI shows what ran.
tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }.configureEach {
    testLogging { events("passed", "skipped", "failed") }
}
