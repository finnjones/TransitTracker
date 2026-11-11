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
    // INTEGRATION: the real TimetableRepository / LiveData / WatchService / PushSender live in these
    // modules; Wiring.kt switches from its stubs to them after the branches are merged.
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

tasks.test { useJUnitPlatform() }
