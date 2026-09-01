import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("teddreader.kmp.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        androidMain.dependencies {
            // 디스플레이 fold / hinge 보고용이며, public API가 있는 플랫폼은 Android뿐이다.
            implementation(libs.findLibrary("androidx-window").get())
        }
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:designsystem"))
            implementation(libs.findLibrary("coil-compose").get())
        }
    }
}
