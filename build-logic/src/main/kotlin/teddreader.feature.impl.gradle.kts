import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("teddreader.koin")
    id("teddreader.kmp.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val featurePath = project.path.removeSuffix(":impl")
val featureName = featurePath.substringAfterLast(":")
val featureFrameworkName = "TeddReader" + featureName.replaceFirstChar { it.titlecase() }

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = featureFrameworkName
            binaryOption("bundleId", "com.tedd.teddreader.feature.$featureName")
            isStatic = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project("$featurePath:api"))
            implementation(project(":core:common"))
            implementation(project(":core:domain"))
            implementation(project(":core:designsystem"))
            implementation(project(":core:ui"))
            implementation(libs.findLibrary("jetbrains-navigation3-ui").get())
            implementation(libs.findLibrary("kotlinx-serialization-core").get())
            implementation(libs.findLibrary("koin-core").get())
            implementation(libs.findLibrary("koin-compose").get())
            implementation(libs.findLibrary("koin-compose-viewmodel").get())
            implementation(libs.findLibrary("koin-compose-navigation3").get())
        }
    }
}
