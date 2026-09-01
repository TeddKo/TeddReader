plugins {
    // 버전을 지정하지 않는다. Android plugin은 build-logic을 통해 이미 빌드 classpath에 있으며,
    // 버전을 지정해 다시 요청하면 Gradle이 해석을 거부한다. AGP 9가 Kotlin을 직접 컴파일하므로
    // 별도의 Kotlin plugin도 여기에 두지 않는다.
    id("com.android.test")
    alias(libs.plugins.androidxBaselineProfile)
}

// 프로필은 기기에서 실제 앱을 구동하여 생성하므로 이 모듈은 라이브러리가 아니라 Android test
// project이다. 앱이 링크하는 코드가 없고 생성물도 일반 빌드에 포함되지 않는다. 앱은 생성된 텍스트
// 파일만 사용한다.
android {
    namespace = "com.tedd.teddreader.baselineprofile"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig {
        minSdk = 28
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath = ":androidApp"
}

// 생성에는 rooted emulator 또는 userdebug 기기가 필요하다. 잠긴 production 기기는 release 빌드에
// instrumented runner를 설치할 수 없다.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
