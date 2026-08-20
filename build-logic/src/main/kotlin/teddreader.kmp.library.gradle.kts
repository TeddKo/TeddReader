import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val androidCompileSdk = libs.findVersion("android-compileSdk").get().requiredVersion.toInt()
val androidMinSdk = libs.findVersion("android-minSdk").get().requiredVersion.toInt()
val moduleNamespace = "com.tedd.teddreader" + project.path
    .split(":")
    .filter { it.isNotBlank() }
    .joinToString(separator = "") { ".${it.replace('-', '.')}" }

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = moduleNamespace
        compileSdk = androidCompileSdk
        minSdk = androidMinSdk
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.findLibrary("kotlinx-coroutines-core").get())
            // Every module logs, and each platform wants its own sink: Kermit writes to Logcat on
            // Android and os_log on iOS without any wiring at the call site.
            implementation(libs.findLibrary("kermit").get())
        }
        commonTest.dependencies {
            implementation(libs.findLibrary("kotlin-test").get())
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}
