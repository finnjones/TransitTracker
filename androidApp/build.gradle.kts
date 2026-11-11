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

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

/** A build setting: Gradle property [property], then environment variable [env], then [property] in local.properties. */
fun setting(property: String, env: String): String =
    providers.gradleProperty(property).orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(property).orEmpty().trim()

// The live flavour's server address, read at build time (tt.baseUrl / TT_BASE_URL). Blank means
// the app asks for it on first start. It is never committed to the repository.
val baseUrl: String = setting("tt.baseUrl", "TT_BASE_URL")

// Version: CI passes its run number (TT_VERSION_CODE), so every build it publishes is newer than
// the last. Local builds are 1 / 0.1.0 unless tt.versionCode says otherwise.
val buildNumber: Int? = setting("tt.versionCode", "TT_VERSION_CODE").toIntOrNull()?.takeIf { it > 0 }

// In-app updates: the GitHub repository ("owner/name") whose releases the app checks
// (tt.updateRepo / TT_UPDATE_REPO; in GitHub Actions, the repository being built). Blank turns
// the CHECK FOR UPDATES button off. See androidApp/README.md, "Updates".
val updateRepo: String = setting("tt.updateRepo", "TT_UPDATE_REPO")
    .ifEmpty { providers.environmentVariable("GITHUB_REPOSITORY").orNull.orEmpty().trim() }

// Release signing. Updates only install over an APK signed with the same key, so releases are
// signed with one long-lived key (never committed): tt.signing.storeFile / TT_SIGNING_STORE_FILE
// and its passwords and alias. Without it, release builds are unsigned.
val signingStore: String = setting("tt.signing.storeFile", "TT_SIGNING_STORE_FILE")

fun String.asJavaString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "app.transittracker.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.transittracker"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = buildNumber ?: 1
        versionName = if (buildNumber != null) "0.1.$buildNumber" else "0.1.0"
        buildConfigField("boolean", "PUSH_CONFIGURED", pushConfigured.toString())
    }

    flavorDimensions += "data"
    productFlavors {
        // The real core against your tt-server.
        create("live") {
            dimension = "data"
            buildConfigField("String", "BASE_URL", baseUrl.asJavaString())
            buildConfigField("String", "UPDATE_REPO", updateRepo.asJavaString())
        }
        // Fake data on the real clock (like the desktop window): the whole UI with no server.
        create("demo") {
            dimension = "data"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            buildConfigField("String", "BASE_URL", "".asJavaString())
            // The demo is not published as a release; it does not update itself.
            buildConfigField("String", "UPDATE_REPO", "".asJavaString())
        }
    }

    if (signingStore.isNotEmpty()) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(signingStore)
                storePassword = setting("tt.signing.storePassword", "TT_SIGNING_STORE_PASSWORD")
                keyAlias = setting("tt.signing.keyAlias", "TT_SIGNING_KEY_ALIAS")
                keyPassword = setting("tt.signing.keyPassword", "TT_SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 is off for now (no keep rules written yet); see androidApp/README.md.
            isMinifyEnabled = false
            if (signingStore.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
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
    // Play services (via Firebase) bring an old androidx.fragment; the ActivityResult API used
    // for permission prompts needs 1.3+ (lint InvalidFragmentVersionForActivityResult).
    implementation(libs.androidx.fragment)
    implementation(libs.ktor.client.okhttp)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(kotlin("test-junit", libs.versions.kotlin.get()))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

// List each unit test in the build log, so CI shows what ran.
tasks.withType<Test>().configureEach {
    testLogging { events("passed", "skipped", "failed") }
}
