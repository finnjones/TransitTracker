rootProject.name = "transittracker"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
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
include(":server:app")        // Ktor HTTP API, SQLite state, invites, tt-admin, wiring
