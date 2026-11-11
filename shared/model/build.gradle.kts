plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    jvm()
    // androidTarget() and iosArm64()/iosSimulatorArm64() are added when the front end is set up.

    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
            api(libs.serialization.json)
            api(libs.serialization.protobuf)
            api(libs.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
