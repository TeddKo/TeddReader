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
            // 모든 모듈이 로그를 기록하고 각 플랫폼에는 자체 sink가 필요하다. Kermit은 호출 지점의 별도
            // 연결 없이 Android에서는 Logcat에, iOS에서는 os_log에 기록한다.
            implementation(libs.findLibrary("kermit").get())
        }
        commonTest.dependencies {
            implementation(libs.findLibrary("kotlin-test").get())
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}
