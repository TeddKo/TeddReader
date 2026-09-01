package buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/**
 * 이 프로젝트의 `libs.versions.toml` version catalog이다. convention plugin이 `build-logic`에
 * coordinate나 버전 문자열을 하드코딩하지 않고 catalog alias로 라이브러리나 버전을 찾을 수 있도록
 * 프로젝트마다 한 번 해석한다.
 *
 * @receiver `libs` catalog를 해석할 프로젝트.
 */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * convention plugin이 모듈의 자체 `build.gradle.kts`에 coordinate를 반복하지 않고 의존성을 추가할 수
 * 있도록 [libs]에서 alias로 라이브러리 의존성을 찾는다.
 *
 * @receiver version catalog를 해석할 프로젝트.
 * @param name `libs.versions.toml`에 있는 라이브러리 alias.
 * @return `dependencies { ... }`에 바로 전달할 수 있도록 해석된 의존성.
 * @throws NoSuchElementException [name]이 catalog의 라이브러리 alias가 아닌 경우.
 */
internal fun Project.findLibrary(name: String): Provider<MinimalExternalModuleDependency> =
    libs.findLibrary(name).get()

/**
 * 전체 의존성 coordinate 대신 원시 버전이 필요한 convention plugin을 위해 [libs]에서 alias로 버전
 * 문자열을 찾는다. 예를 들어 버전 문자열을 직접 받는 도구를 설정할 때 사용한다.
 *
 * @receiver version catalog를 해석할 프로젝트.
 * @param name `libs.versions.toml`에 있는 버전 alias.
 * @return alias의 필수 버전 문자열.
 * @throws NoSuchElementException [name]이 catalog의 버전 alias가 아닌 경우.
 */
internal fun Project.findVersion(name: String): String =
    libs.findVersion(name).get().requiredVersion
