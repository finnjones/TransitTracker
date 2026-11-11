plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    sourceSets.test { languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi") }
}

dependencies {
    api(project(":server:spi"))
    // The TfNSW quota (daily budget + per-second limiter) is shared with the live feeds, and the
    // planner backs off on the same throttle statuses: both live in :server:live's CallGuards.kt.
    implementation(project(":server:live"))
    implementation(libs.coroutines.core)
    implementation("org.slf4j:slf4j-api:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.logback)
}

tasks.test {
    useJUnitPlatform()
    // Hand-made TfNSW Trip Planner responses live at the repository root (testdata/planner).
    systemProperty("tt.testdata", rootProject.layout.projectDirectory.dir("testdata").asFile.absolutePath)
    inputs.dir(rootProject.layout.projectDirectory.dir("testdata"))
}
