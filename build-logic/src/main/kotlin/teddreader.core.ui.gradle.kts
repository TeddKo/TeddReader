import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("teddreader.kmp.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:designsystem"))
            implementation(libs.findLibrary("coil-compose").get())
        }
    }
}
