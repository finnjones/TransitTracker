plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }

application {
    mainClass.set("app.transittracker.server.app.MainKt")
}

dependencies {
    implementation(project(":server:spi"))
    implementation(project(":server:pipeline"))
    implementation(project(":server:live"))
    implementation(project(":server:watch"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.conditional.headers)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.serialization.protobuf)
    implementation(libs.sqlite.jdbc)
    implementation(libs.logback)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.coroutines.test)
}

tasks.test { useJUnitPlatform() }
