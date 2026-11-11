plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":server:spi"))
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}

tasks.test { useJUnitPlatform() }
