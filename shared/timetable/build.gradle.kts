import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

// Android target only when settings.gradle.kts found an Android SDK.
val androidEnabled = gradle.extra["tt.android"] == true
if (androidEnabled) apply(plugin = "com.android.kotlin.multiplatform.library")

kotlin {
    jvmToolchain(21)
    jvm()
    if (androidEnabled) {
        (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
            namespace = "app.transittracker.timetable"
            compileSdk = libs.versions.android.compileSdk.get().toInt()
            minSdk = libs.versions.android.minSdk.get().toInt()
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            // Robolectric tests of the read-only pack driver (src/androidHostTest).
            withHostTest { isIncludeAndroidResources = true }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:model"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            // api: the pipeline writes packs through JvmTimetables and opens them with the same driver.
            api(libs.sqldelight.sqlite.driver)
            api(libs.sqlite.jdbc)
        }
        if (androidEnabled) {
            getByName("androidHostTest").dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.junit4)
                implementation(libs.robolectric)
                implementation(libs.androidx.test.core)
                // The tests write packs the way the pipeline does (JDBC SQLite + PackWriter).
                implementation(libs.sqldelight.sqlite.driver)
                implementation(libs.sqlite.jdbc)
            }
        }
    }
}

sqldelight {
    databases {
        create("TimetableDb") {
            packageName.set("app.transittracker.timetable.db")
        }
    }
}
