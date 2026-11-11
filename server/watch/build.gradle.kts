plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":server:spi"))
    // OAuth2 access tokens for FCM HTTP v1 from a service-account JSON.
    implementation(libs.google.auth)
    implementation("org.slf4j:slf4j-api:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.logback)
}

tasks.test { useJUnitPlatform() }
