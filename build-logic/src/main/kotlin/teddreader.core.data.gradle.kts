import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("teddreader.koin")
    id("teddreader.kmp.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            api(project(":core:domain"))
            implementation(project(":core:datastore"))
            implementation(project(":core:room"))
            implementation(libs.findLibrary("okio").get())
        }
    }
}
