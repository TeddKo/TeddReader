package buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction

/**
 * 모듈이 허용된 두 위치 밖에서 Material 3를 import하면 빌드를 실패시킨다.
 *
 * 앱이 사용하는 모든 Material 컴포넌트는 자체 디자인 시스템으로 감싸므로 색상, 모양, 서체, ripple은
 * Material 기본값이 아니라 앱 토큰에서 온다. 어떤 화면도 `androidx.compose.material3`에 직접 접근할 수
 * 없을 때만 이 정책이 유지된다. import 하나만 벗어나도 Material 기본값이 UI에 다시 들어오며, 얼핏
 * 올바르게 보여 리뷰에서 놓칠 수 있다. 이 태스크는 관례에 불과한 정책을 빌드 실패로 강제한다.
 *
 * configuration cache를 위해 `.gradle.kts` 스크립트 안이 아니라 태스크 클래스로 작성했다. 스크립트
 * 본문의 `doLast { }` 블록은 Gradle이 직렬화할 수 없는 바깥 스크립트 객체를 캡처하므로 전체 빌드가
 * "cannot serialize Gradle script object references" 오류로 실패한다. 따라서 이 태스크에 필요한 모든
 * 값은 선언된 입력 속성을 통해 받는다.
 */
@CacheableTask
abstract class Material3ImportCheckTask : DefaultTask() {

    /**
     * 검사할 모듈의 Gradle 경로이다. 예: `:feature:home:impl`.
     *
     * 최신 상태 검사 입력이자 이 모듈이 [fullyAllowedModulePaths] 중 하나인지 판단하는 식별자이므로,
     * 모듈 이름이 바뀌면 새 규칙으로 다시 검사한다.
     */
    @get:Input
    abstract val modulePath: Property<String>

    /**
     * 모든 Material 3 import가 허용되는 모듈 경로이다.
     *
     * Material을 감싸는 역할의 모듈들이다. 디자인 시스템은 앱의 색상과 모양을 `MaterialTheme`에
     * 전달하고, 공유 UI 모듈은 앱이 사용하는 Material 컴포넌트의 wrapper를 소유한다.
     */
    @get:Input
    abstract val fullyAllowedModulePaths: SetProperty<String>

    /**
     * 그 밖의 모든 모듈이 import할 수 있는 유일한 Material 3 심볼이다.
     *
     * 일반적인 우회로가 아니라 의도적으로 제한한 예외 목록이다. 각 항목은 플랫폼 동작을 wrapper 뒤에서
     * 다시 구현할 가치가 없는 컴포넌트이다.
     */
    @get:Input
    abstract val allowedSymbols: SetProperty<String>

    /**
     * 모든 소스 세트에 걸친 모듈의 전체 Kotlin 소스 파일이다.
     *
     * 테스트 소스도 의도적으로 포함한다. Material을 직접 import하는 테스트는 프로덕션 코드와 똑같이
     * wrapper 계층을 우회하며, 앱 토큰이 아니라 Material 기본값을 기준으로 검증하게 된다.
     *
     * checkout 위치가 바뀌어도 검사가 최신 상태를 유지하도록 `@PathSensitive(RELATIVE)`를 사용한다.
     * `@SkipWhenEmpty`는 소스가 전혀 없는 모듈에서 의미 없는 실패를 보고하는 대신 Gradle이 태스크를
     * 건너뛰게 한다. 실제로 소스가 있지만 빈 집합으로 해석된 모듈은 검사할 것이 없는 경우가 아니라
     * 연결이 잘못된 경우이므로 [check] 내부에서 실패한다.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:SkipWhenEmpty
    abstract val sourceFiles: ConfigurableFileCollection

    /**
     * 성공할 때 기록하여 Gradle이 태스크를 최신 상태로 표시할 수 있게 하는 파일이다.
     *
     * 검증 태스크에는 실제 산출물이 없으므로 이 파일이 없으면 Gradle이 빌드할 때마다 모든 소스 파일을
     * 다시 검사한다. 실행 실패 후 marker가 없으면 다음 실행에서 다시 검사한다.
     */
    @get:OutputFile
    abstract val marker: RegularFileProperty

    /**
     * 모든 소스 파일을 검사하고 위반한 import 전체 목록과 함께 실패한다.
     *
     * 첫 위반에서 멈추지 않고 모든 위반을 한 번에 보고한다. Material에 접근한 화면은 대개 여러 번
     * 접근하므로 위반이 묶음으로 발생하며, 빌드 한 번에 하나씩 고치는 것은 불필요한 작업이다.
     *
     * @throws GradleException 허용되지 않은 import를 찾았거나, 디스크에 모듈 소스가 있지만 이 태스크에
     *   하나도 전달되지 않은 경우. 후자는 소스 연결이 올바른 것이 아니라 끊어졌음을 뜻한다.
     */
    @TaskAction
    fun check() {
        val path = modulePath.get()
        val isFullyAllowed = path in fullyAllowedModulePaths.get()
        val permitted = allowedSymbols.get()
        val files = sourceFiles.files.filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }

        if (files.isEmpty()) {
            throw GradleException(
                "checkMaterial3Imports [$path]: no Kotlin sources reached the task. " +
                    "The source wiring is broken — this is not a clean pass.",
            )
        }

        logger.lifecycle("checkMaterial3Imports [$path]: scanning ${files.size} .kt file(s)")

        if (isFullyAllowed) {
            writeMarker()
            return
        }

        val importPattern = Regex("""^import\s+androidx\.compose\.material3\.(\S+)""")
        val violations = mutableListOf<String>()

        files.forEach { file ->
            file.bufferedReader().useLines { lines ->
                lines.forEachIndexed { index, line ->
                    val symbol = importPattern.find(line.trimStart())
                        ?.groupValues
                        ?.get(1)
                        ?.trimEnd(';', ' ')
                        ?: return@forEachIndexed
                    if (symbol !in permitted) {
                        violations += "${file.path}:${index + 1}: $symbol"
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Material 3 import policy violated in $path (${violations.size}).")
                    appendLine()
                    appendLine("androidx.compose.material3 may only be imported in:")
                    fullyAllowedModulePaths.get().sorted().forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("Every other module may import only:")
                    permitted.sorted().forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("Offending imports:")
                    violations.forEach { appendLine("  $it") }
                    appendLine()
                    append("Wrap the component in :core:ui and expose it through the design system.")
                },
            )
        }

        writeMarker()
    }

    /**
     * 변경되지 않은 모듈을 다시 검사하지 않도록 모듈의 통과를 기록한다.
     */
    private fun writeMarker() {
        val file = marker.get().asFile
        file.parentFile?.mkdirs()
        file.writeText("ok\n")
    }
}
