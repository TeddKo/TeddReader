import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("teddreader.kmp.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("teddreader.material3.gate")
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
        // Compose 의 시맨틱 트리를 검사하는 테스트용 의존성. 접근성 라벨, role, 선택/체크 상태,
        // 클릭 타깃의 유일성은 컴파일러가 잡아주지 않으므로 실제로 컴포즈해서 트리를 조회하는
        // 수단이 없으면 검증할 방법이 없다. runComposeUiTest 는 iosSimulatorArm64Test 에서
        // 실제로 실행되며, Android host test 는 JVM 단위 테스트라 Robolectric 없이는 이 API 를
        // 실행하지 못한다 — 그래서 시맨틱 테스트는 iosTest 소스셋에 둔다.
        commonTest.dependencies {
            implementation(libs.findLibrary("compose-uiTest").get())
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
