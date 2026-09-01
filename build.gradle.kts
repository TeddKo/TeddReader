plugins {
    // subproject의 classloader에서 plugin이 여러 번 로드되는 일을 방지하는 데
    // 필요하다.
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.koinCompiler) apply false
    alias(libs.plugins.ksp) apply false
}
