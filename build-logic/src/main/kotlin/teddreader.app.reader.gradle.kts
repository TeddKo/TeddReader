import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("teddreader.kmp.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "TeddReaderApp"
            binaryOption("bundleId", "com.tedd.teddreader.app.reader")
            isStatic = true
        }
    }
    sourceSets {
        androidMain.dependencies {
            implementation(libs.findLibrary("androidx-activity-compose").get())
            implementation(libs.findLibrary("google-play-services-auth").get())
        }
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:domain"))
            implementation(project(":core:data"))
            implementation(project(":core:datastore"))
            implementation(project(":core:room"))
            implementation(project(":core:designsystem"))
            implementation(project(":core:ui"))
            implementation(project(":feature:home:api"))
            implementation(project(":feature:home:impl"))
            implementation(project(":feature:reader:api"))
            implementation(project(":feature:reader:impl"))
            implementation(project(":feature:settings:api"))
            implementation(project(":feature:settings:impl"))
            implementation(project(":feature:search:api"))
            implementation(project(":feature:search:impl"))
            implementation(project(":feature:bookmarks:api"))
            implementation(project(":feature:bookmarks:impl"))
            implementation(project(":feature:document-info:api"))
            implementation(project(":feature:document-info:impl"))
            implementation(libs.findLibrary("jetbrains-navigation3-ui").get())
            implementation(libs.findLibrary("androidx-datastore-core").get())
            implementation(libs.findLibrary("androidx-room3-runtime").get())
            implementation(libs.findLibrary("koin-core").get())
            implementation(libs.findLibrary("koin-core-viewmodel").get())
            implementation(libs.findLibrary("koin-compose").get())
            implementation(libs.findLibrary("koin-compose-viewmodel").get())
        }
    }
}
