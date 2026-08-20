import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("teddreader.kmp.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation(libs.findLibrary("compose-uiToolingPreview").get())
        }
        commonMain.dependencies {
            implementation(libs.findLibrary("compose-runtime").get())
            implementation(libs.findLibrary("compose-foundation").get())
            implementation(libs.findLibrary("compose-material3").get())
            implementation(libs.findLibrary("compose-ui").get())
            implementation(libs.findLibrary("compose-components-resources").get())
            implementation(libs.findLibrary("compose-uiToolingPreview").get())
            implementation(libs.findLibrary("androidx-lifecycle-viewmodelCompose").get())
            implementation(libs.findLibrary("androidx-lifecycle-runtimeCompose").get())
        }
    }
}

dependencies {
    "androidRuntimeClasspath"(libs.findLibrary("compose-uiTooling").get())
}

composeCompiler {
    // core:common 도메인 모델을 안정 타입으로 선언한다. 근거는 compose-stability.conf 주석 참고.
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("compose-stability.conf")
    )

    // 안정성 진단 리포트. -Pteddreader.composeReports 를 준 빌드에서만 켜지므로
    // 평소 빌드의 태스크 입력과 산출물은 그대로 유지된다.
    if (providers.gradleProperty("teddreader.composeReports").isPresent) {
        reportsDestination.set(layout.buildDirectory.dir("compose/reports"))
        metricsDestination.set(layout.buildDirectory.dir("compose/metrics"))
    }
}
