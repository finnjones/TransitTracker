rootProject.name = "transittracker"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google {
            // Only AndroidX / Android artifacts come from Google's repository (Compose needs some).
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.android\\..*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google {
            // Only AndroidX / Android artifacts come from Google's repository (Compose needs some).
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.android\\..*")
            }
        }
    }
}

// Shared (Kotlin Multiplatform; JVM target today, Android/iOS targets added with the front end)
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
}
