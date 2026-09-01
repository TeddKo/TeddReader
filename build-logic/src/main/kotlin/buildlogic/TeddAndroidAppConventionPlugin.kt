package buildlogic

import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Android 애플리케이션 셸([androidApp] 모듈)을 위한 convention plugin이다.
 *
 * Android application, Compose, Kotlin Compose plugin을 적용하고, 선택적 서명을 포함한 release 및
 * debug 빌드용 [ApplicationExtension]을 설정하며, baseline-profile plugin 자체가 요구하는 순서로
 * 연결하고, 모듈의 런타임 의존성을 추가한다. 모든 공유 결정이 여기에 있으므로 모듈 자체
 * `build.gradle.kts`는 `id(...)` 한 줄로 유지된다.
 */
class TeddAndroidAppConventionPlugin : Plugin<Project> {
    /**
     * [target]에 plugin을 적용한다.
     *
     * baseline-profile plugin은 적용되는 즉시 모듈의 Android 설정을 검사하고 `ApplicationExtension`을
     * 찾지 못하면 모듈을 즉시 거부하므로 반드시 [configureAndroid] **이후**에 적용해야 한다.
     * `baselineProfile` 의존성 configuration도 plugin 다음에 추가한다. plugin 적용의 부수 효과로 해당
     * configuration을 생성하므로, 그 전에 profile 모듈을 연결하면 해석되지 않은 configuration 이름으로
     * 실패한다.
     *
     * @param target 이 plugin을 적용할 Gradle [Project].
     */
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        configureAndroid()
        exposeReleaseTasks()
        configureDependencies()
        pluginManager.apply("androidx.baselineprofile")
        dependencies.add("baselineProfile", project(":baselineprofile"))
    }

    /**
     * 이 모듈의 [ApplicationExtension]을 설정한다.
     *
     * namespace, compile/min/target SDK 버전, packaging 제외 항목, 선택적 서명, build type, Java 호환성
     * 옵션을 설정한다.
     *
     * **서명.** 서명 자료는 의도적으로 버전 관리에서 제외한다. `.signing/`과
     * `keystore.properties`는 모두 gitignore 대상이며, 어느 기기에서나 동일하게 읽도록 저장소 루트에서
     * 경로를 해석한다. 이 파일들이 없는 checkout은 제공되지 않은 파일 때문에 실패하는 대신 서명되지
     * 않은 release 빌드를 생성한다.
     *
     * **Build type.** debug 빌드는 `.dev` application-ID suffix와 "TeddReader dev" label을 사용하여
     * 같은 기기에 release 빌드와 나란히 설치된다. 이 분리가 없으면 debug와 release를 비교 측정할 때
     * 앱을 제거해야 했고, 그 과정에서 리더 라이브러리와 저장된 읽기 위치가 모두 지워졌다.
     *
     * **R8 및 shrinking.** release 빌드는 minification과 resource shrinking을 활성화한다. R8이 없으면
     * 책을 처음 열 때 리더의 전체 composable 트리를 로드하고 JIT 컴파일하며, 데이터 경로가 이미
     * 230 ms에 끝난 상황에서도 탭부터 첫 페이지까지 약 1.9 s가 걸렸다. R8은 설치 전에 트리를 AOT
     * 최적화하고 가지치기하여 이 cold-start 비용을 제거한다.
     */
    private fun Project.configureAndroid() {
        extensions.configure<ApplicationExtension> {
            namespace = "com.tedd.teddreader"
            compileSdk = findVersion("android-compileSdk").toInt()

            defaultConfig {
                applicationId = "com.tedd.teddreader"
                minSdk = findVersion("android-minSdk").toInt()
                targetSdk = findVersion("android-targetSdk").toInt()
                versionCode = 1
                versionName = "1.0"
            }

            packaging {
                resources {
                    excludes += "/META-INF/{AL2.0,LGPL2.1}"
                }
            }

            val signing = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
                Properties().apply { file.inputStream().use(::load) }
            }
            if (signing != null) {
                signingConfigs.create("release") {
                    storeFile = rootProject.file(signing.getProperty("storeFile"))
                    storePassword = signing.getProperty("storePassword")
                    keyAlias = signing.getProperty("keyAlias")
                    keyPassword = signing.getProperty("keyPassword")
                }
            }

            buildTypes {
                getByName("debug") {
                    applicationIdSuffix = ".dev"
                    versionNameSuffix = "-dev"
                    manifestPlaceholders["appLabel"] = "TeddReader dev"
                }
                getByName("release") {
                    manifestPlaceholders["appLabel"] = "TeddReader"
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                    signing?.let { signingConfig = signingConfigs.getByName("release") }
                }
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }
        }
    }

    /** AGP에서 그룹이 없는 release lifecycle 태스크를 Android Studio의 Gradle 도구 창에 표시한다. */
    private fun Project.exposeReleaseTasks() {
        tasks.matching { task -> task.name == "assembleRelease" || task.name == "bundleRelease" }
            .configureEach { group = "build" }
    }

    /**
     * Android 애플리케이션 셸의 런타임 의존성을 추가한다.
     *
     * 이 모듈은 DI graph, 탐색 host, theme를 연결하는 composition root인 `app:reader`, Compose를
     * 인식하는 Activity 진입점용 `androidx-activity-compose`, non-debug 빌드에서 레이아웃을 검사하기
     * 위한 Compose UI tooling preview stub에 의존하며, 전체 Compose tooling은 debug variant에만
     * 의존한다.
     */
    private fun Project.configureDependencies() {
        dependencies.add("implementation", project(":app:reader"))
        dependencies.add("implementation", findLibrary("androidx-activity-compose"))
        dependencies.add("implementation", findLibrary("compose-uiToolingPreview"))
        dependencies.add("debugImplementation", findLibrary("compose-uiTooling"))
    }
}
