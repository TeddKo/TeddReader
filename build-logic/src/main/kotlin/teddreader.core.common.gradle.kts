import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("teddreader.kmp.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.findLibrary("kotlinx-serialization-core").get())
            api(libs.findLibrary("kotlinx-serialization-json").get())
            api(libs.findLibrary("kotlinx-datetime").get())
            api(libs.findLibrary("kotlinx-collections-immutable").get())
        }
    }
}
