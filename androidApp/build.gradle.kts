import java.util.Properties

// :androidApp is included only when settings.gradle.kts found an Android SDK.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Push is optional: with google-services.json next to this file the Google services plugin
// configures Firebase; without it the app builds and runs with push off.
val pushConfigured = file("google-services.json").isFile
if (pushConfigured) apply(plugin = "com.google.gms.google-services")

// The live flavour's server address, read at build time: Gradle property `tt.baseUrl`, then the
// TT_BASE_URL environment variable, then `tt.baseUrl` in local.properties. Blank means the app
// asks for it on first start. It is never committed to the repository.
val baseUrl: String = providers.gradleProperty("tt.baseUrl").orNull?.takeIf { it.isNotBlank() }
    ?: providers.environmentVariable("TT_BASE_URL").orNull?.takeIf { it.isNotBlank() }
    ?: rootProject.file("local.properties").takeIf { it.isFile }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }.getProperty("tt.baseUrl")
    }.orEmpty().trim()

fun String.asJavaString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "app.transittracker.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.transittracker"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("boolean", "PUSH_CONFIGURED", pushConfigured.toString())
    }

    flavorDimensions += "data"
    productFlavors {
        // The real core against your tt-server.
        create("live") {
            dimension = "data"
            buildConfigField("String", "BASE_URL", baseUrl.asJavaString())
        }
        // Fake data on the real clock (like the desktop window): the whole UI with no server.
        create("demo") {
            dimension = "data"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            buildConfigField("String", "BASE_URL", "".asJavaString())
        }
    }

    buildTypes {
        release {
            // R8 is off for now (no keep rules written yet); see androidApp/README.md.
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric (src/test) needs the merged manifest and resources.
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            // Duplicate licence/notice files from the Kotlin and Ktor jars.
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

// Without google-services.json for the demo id, the plugin would fail the demo build: the demo
// flavour has no push (fake core), so its Google services processing is skipped.
if (pushConfigured) {
    tasks.matching { it.name.startsWith("process") && it.name.contains("Demo") && it.name.endsWith("GoogleServices") }
        .configureEach { enabled = false }
}

dependencies {
    implementation(project(":app:ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.ktor.client.okhttp)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(kotlin("test-junit", libs.versions.kotlin.get()))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
