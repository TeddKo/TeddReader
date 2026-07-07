import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("teddreader.koin")
    id("teddreader.kmp.library")
    id("com.google.devtools.ksp")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val roomCompiler = libs.findLibrary("androidx-room3-compiler").get()

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(libs.findLibrary("androidx-room3-runtime").get())
            implementation(libs.findLibrary("androidx-sqlite").get())
            implementation(libs.findLibrary("androidx-sqlite-bundled").get())
        }
    }
}

dependencies {
    add("kspAndroid", roomCompiler)
    add("kspIosArm64", roomCompiler)
    add("kspIosSimulatorArm64", roomCompiler)
}
