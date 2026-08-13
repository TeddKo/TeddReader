import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("teddreader.kmp.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        androidMain.dependencies {
            // Display fold / hinge reporting; Android is the only platform with a public API.
            implementation(libs.findLibrary("androidx-window").get())
        }
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:designsystem"))
            implementation(libs.findLibrary("coil-compose").get())
        }
    }
}
