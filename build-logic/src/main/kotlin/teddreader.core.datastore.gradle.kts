import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("teddreader.koin")
    id("teddreader.kmp.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(libs.findLibrary("androidx-datastore-core").get())
            implementation(libs.findLibrary("androidx-datastore-core-okio").get())
        }
    }
}
