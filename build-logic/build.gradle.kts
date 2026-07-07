plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.android.application.gradle.plugin)
    implementation(libs.android.kmp.library.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.multiplatform.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    implementation(libs.kotlin.compose.compiler.gradle.plugin)
    implementation(libs.koin.compiler.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    implementation(libs.compose.gradle.plugin)
    implementation(libs.compose.plugin.marker)
}

gradlePlugin {
    plugins {
        create("teddAndroidApp") {
            id = "teddreader.android.app"
            implementationClass = "buildlogic.TeddAndroidAppConventionPlugin"
        }
    }
}
