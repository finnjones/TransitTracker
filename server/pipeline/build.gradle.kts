plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":server:spi"))
    api(project(":shared:timetable"))
    implementation(libs.zstd)
    implementation(libs.serialization.json)
    implementation("org.slf4j:slf4j-api:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.logback)
}

tasks.test {
    useJUnitPlatform()
    // The synthetic GTFS fixture lives at the repository root.
    systemProperty("tt.testdata", rootProject.layout.projectDirectory.dir("testdata").asFile.absolutePath)
    inputs.dir(rootProject.layout.projectDirectory.dir("testdata"))
}
