import java.util.Properties

rootProject.name = "transittracker"

pluginManagement {
    repositories {
        // First, so the Android Gradle plugin and its tools come straight from Google rather than
        // through the plugin portal's redirect to Maven Central.
        google {
            // Only Google's own artifacts come from Google's repository: AndroidX, the Android
            // Gradle plugin and tools, Play services, Firebase and the Google services plugin.
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\..*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google {
            // Only Google's own artifacts come from Google's repository: AndroidX, the Android
            // Gradle plugin and tools, Play services, Firebase and the Google services plugin.
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\..*")
            }
        }
    }
}

// ---- Android: decided once, here. The Android targets of the shared modules and :androidApp are
// built only when an Android SDK is present (ANDROID_HOME / ANDROID_SDK_ROOT, or sdk.dir in
// local.properties). Without one (this repository's server image, a machine with only a JDK)
// everything stays JVM only. `-Ptt.android=false` turns Android off even with an SDK.
val androidSdkDir: String? = run {
    val fromEnv = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").mapNotNull { System.getenv(it)?.takeIf(String::isNotBlank) }
    val localProps = file("local.properties")
    val fromLocal = if (localProps.isFile) {
        Properties().apply { localProps.inputStream().use { load(it) } }.getProperty("sdk.dir")?.takeIf(String::isNotBlank)
    } else {
        null
    }
    (fromEnv + listOfNotNull(fromLocal)).firstOrNull { file(it).isDirectory }
}
val androidEnabled: Boolean =
    androidSdkDir != null && providers.gradleProperty("tt.android").orNull?.toBoolean() != false
// Read by the module build files as `gradle.extra["tt.android"]`.
gradle.extra["tt.android"] = androidEnabled

// Shared (Kotlin Multiplatform; JVM target, plus Android when an SDK is present, iOS later)
include(":shared:model")      // contracts: domain types, API payloads, TransitCore + Timetable interfaces, service-time maths
include(":shared:timetable")  // timetable pack schema (SQLDelight), reader, departure queries
include(":shared:core")       // TransitCore implementation: API client, polling, overlay, updater, fake

// Server (JVM)
include(":server:spi")        // server-internal interfaces and config shared by server modules
include(":server:pipeline")   // nightly GTFS fetch/validate -> timetable pack builder
include(":server:live")       // fetch-on-demand GTFS-R cache, decoding, matching
include(":server:watch")      // watch engine + FCM sender
include(":server:planner")    // journey planning via TfNSW's Trip Planner: /v1/places, /v1/plan
include(":server:app")        // Ktor HTTP API, SQLite state, invites, tt-admin, wiring

// Front end (Compose Multiplatform). Included only when present, so builds that copy just
// shared/ and server/ (deploy/Dockerfile) keep working.
if (file("app/ui").isDirectory) {
    include(":app:ui")        // design system, sprites, strips, screens (shared by Android and iOS)
    if (androidEnabled && file("androidApp").isDirectory) {
        include(":androidApp") // Android shell: activity, push, permissions, platform glue
    }
}
