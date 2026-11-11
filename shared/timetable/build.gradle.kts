plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:model"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            // api: the pipeline writes packs through JvmTimetables and opens them with the same driver.
            api(libs.sqldelight.sqlite.driver)
            api(libs.sqlite.jdbc)
        }
    }
}

sqldelight {
    databases {
        create("TimetableDb") {
            packageName.set("app.transittracker.timetable.db")
        }
    }
}
