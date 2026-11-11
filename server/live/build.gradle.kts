plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.wire)
}

kotlin { jvmToolchain(21) }

wire {
    // Pinned .proto files live at the repository root so other modules (and tools) can share them.
    sourcePath {
        srcDir("../../proto")
    }
    kotlin {}
}

dependencies {
    api(project(":server:spi"))
    implementation(libs.wire.runtime)
    implementation(libs.coroutines.core)
    implementation("org.slf4j:slf4j-api:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.logback)
}

tasks.test { useJUnitPlatform() }
