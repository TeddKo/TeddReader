import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("teddreader.kmp.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(libs.findLibrary("kotlinx-serialization-core").get())
        }
    }
}
