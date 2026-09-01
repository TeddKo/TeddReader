import buildlogic.Material3ImportCheckTask

// Material 3 import 정책 gate.
//
// 앱이 사용하는 모든 Material 컴포넌트는 자체 디자인 시스템으로 감싸므로 색상, 모양, 서체, ripple은
// Material 기본값이 아니라 앱 토큰에서 온다. 다른 어떤 코드도 androidx.compose.material3를 직접
// import할 수 없을 때만 이 조건이 유지되므로, 모듈별 검증 태스크를 등록해 `check`에 연결한다. Compose를
// 사용하는 모든 모듈이 이미 적용하는 teddreader.kmp.compose를 통해 전이적으로 적용된다.
//
// 검사 로직은 여기의 doLast 블록이 아니라 buildlogic.Material3ImportCheckTask에 둔다. 스크립트 본문의
// lambda는 configuration cache가 직렬화할 수 없는 바깥 스크립트 객체를 캡처하므로 빌드가
// "cannot serialize Gradle script object references" 오류로 실패하기 때문이다.

val checkMaterial3Imports = tasks.register<Material3ImportCheckTask>("checkMaterial3Imports") {
    group = "verification"
    description = "Fails when androidx.compose.material3 is imported outside the modules allowed to."

    modulePath.set(project.path)

    // Material을 감싸는 역할의 두 모듈이다. designsystem은 앱의 색상과 모양을 MaterialTheme에
    // 전달하고, core:ui는 앱이 사용하는 컴포넌트의 wrapper를 소유한다.
    fullyAllowedModulePaths.set(setOf(":core:ui", ":core:designsystem"))

    // 리더의 목차 drawer는 swipe gesture, back 처리, focus trap을 플랫폼에 위임하며
    // 사용처가 하나뿐이라 wrapper로 얻는 이점이 없다. 의도적인 예외이며, 이 목록의 확장은 편의가
    // 아니라 설계 결정이다.
    allowedSymbols.set(
        setOf(
            "DrawerValue",
            "ModalDrawerSheet",
            "ModalNavigationDrawer",
            "NavigationDrawerItem",
            "rememberDrawerState",
        ),
    )

    sourceFiles.from(
        project.layout.projectDirectory.dir("src").asFileTree.matching { include("**/*.kt") },
    )

    marker.set(project.layout.buildDirectory.file("reports/material3-gate/passed.txt"))
}

// plugins.withId는 base plugin이 `check`를 등록한 뒤에만 실행되므로, 이를 등록하지 않는 모듈에서도
// 안전하다.
plugins.withId("org.gradle.base") {
    tasks.named("check") {
        dependsOn(checkMaterial3Imports)
    }
}
