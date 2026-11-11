plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }

application {
    // Start script is bin/tt-server; tt-admin gets its own script below.
    applicationName = "tt-server"
    mainClass.set("app.transittracker.server.app.MainKt")
}

dependencies {
    implementation(project(":server:spi"))
    // The real TimetableRepository / LiveData / WatchService / PushSender, wired in Wiring.kt.
    implementation(project(":server:pipeline"))
    implementation(project(":server:live"))
    implementation(project(":server:watch"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.compression)
    implementation(libs.sqlite.jdbc)
    implementation(libs.logback)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.coroutines.test)
    // End-to-end tests: the phone engine against the real server, and synthetic GTFS-Realtime
    // built with the Wire classes generated in :server:live.
    testImplementation(project(":shared:core"))
    testImplementation(project(":shared:timetable"))
    testImplementation(libs.wire.runtime)
}

/** Second start script so the image has both `bin/tt-server` and `bin/tt-admin`. */
val ttAdminStartScripts by tasks.registering(CreateStartScripts::class) {
    description = "Creates the tt-admin start scripts."
    mainClass.set("app.transittracker.server.app.AdminKt")
    applicationName = "tt-admin"
    outputDir = layout.buildDirectory.dir("tt-admin-scripts").get().asFile
    classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
}

distributions {
    main {
        contents {
            from(ttAdminStartScripts) { into("bin") }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // The synthetic GTFS fixture lives at the repository root.
    systemProperty("tt.testdata", rootProject.layout.projectDirectory.dir("testdata").asFile.absolutePath)
    inputs.dir(rootProject.layout.projectDirectory.dir("testdata"))
}
