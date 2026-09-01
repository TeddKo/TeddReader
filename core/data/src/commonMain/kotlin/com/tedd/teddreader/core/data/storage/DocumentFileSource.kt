package com.tedd.teddreader.core.data.storage

import com.tedd.teddreader.core.common.model.DocumentLocation
import okio.ByteString.Companion.encodeUtf8
import okio.Path

/**
 * 이 플랫폼에서 문서의 바이트에 실제로 도달하는 방법 — Android의 SAF `content://` Uri와 일반
 * 파일 대 iOS의 샌드박스 파일 경로 — 를 다루어, [DocumentRepositoryImpl]이 플랫폼 자체를 분기하지
 * 않고도 문서를 임포트하고, 다시 열고, 복사할 수 있게 한다.
 */
interface DocumentFileSource {
    /**
     * 문서 전체 내용을 메모리로 읽는다.
     *
     * @param location 읽을 문서.
     * @return 문서의 원시 바이트.
     * @throws IllegalStateException [location]에 더 이상 도달할 수 없을 때, 예: Android에서 취소된
     *   SAF 권한이나 어느 플랫폼에서든 이동/삭제된 파일.
     */
    suspend fun readBytes(location: DocumentLocation): ByteArray

    /**
     * 문서의 바이트를 로컬 파일시스템의 [destination]으로 복사한다.
     *
     * @param location 복사할 원본 문서.
     * @param destination 사본을 쓸 위치.
     * @throws IllegalStateException [location]에 더 이상 도달할 수 없을 때.
     */
    suspend fun copyTo(location: DocumentLocation, destination: Path)

    /**
     * 문서가 디스크에 durable하고 앱이 소유한 [bytes]의 사본을 갖도록 보장하고, 그것을 가리키는
     * [location]을 반환한다. 기본 구현은 [location]을 그대로 반환하는 무동작이다; 플랫폼 구현들은
     * 이를 오버라이드하여 실제로 [bytes]를 앱 전용 저장소에 쓰며, [materializedDocumentFileName]으로
     * 이름을 붙여 같은 소스를 다시 materialize해도 중복을 쓰는 대신 같은 파일에 도달하도록 한다.
     *
     * @param location 문서의 현재 위치.
     * @param bytes 문서의 바이트. durable한 사본이 아직 없으면 쓰인다.
     * @return [location], 또는 materialize된 사본을 가리키는 갱신된 [DocumentLocation].
     */
    suspend fun materialize(location: DocumentLocation, bytes: ByteArray): DocumentLocation = location

    /**
     * shelf 행이 삭제된 후, [location]이 나타내는 durable한 앱 소유 사본을 제거한다.
     *
     * 구현체는 외부 소스와 자신의 materialize된 문서 디렉터리 바깥의 경로를 반드시 무시해야 한다.
     * durable한 사본을 전혀 만들지 않는 파일 소스에서는 기본값이 무동작이다.
     *
     * @param location 데이터베이스 행이 제거되기 전에 캡처된 저장된 위치.
     * @throws IllegalStateException 소유한 파일이 존재하지만 삭제될 수 없을 때.
     */
    suspend fun deleteMaterialized(location: DocumentLocation) = Unit

    /**
     * 이 플랫폼이 앱 전용으로 유지하는 저장소의 루트 — Android에서는
     * [android.content.Context.filesDir], iOS에서는 샌드박스의 Library/Caches. 캐시된 표지 이미지가
     * 이 아래에 산다(DocumentRepositoryImpl.getDocumentCover 참고). 이것이 [systemFileSystem]처럼
     * 순수한 expect/actual이 아니라 인터페이스 위에 있는 이유는, 그 함수와 달리 Android의 답이
     * `Context`를 필요로 하기 때문이다 — [readBytes]와 [copyTo]가 이미 여기서 `expect`/`actual`
     * 대신 플랫폼별로 다른 것과 같은 이유다.
     */
    fun appPrivateDirectory(): Path
}

/**
 * 경로 순회 세그먼트를 해석한 후 [path]가 [directory]의 직계 자식 하나인지 여부.
 *
 * @param path 저장된 문서 위치에서 나온 후보 파일 경로.
 * @param directory 플랫폼이 소유한 materialize된 문서 디렉터리.
 * @return [path]를 삭제해도 [directory]를 벗어나거나 그 아래로 더 내려갈 수 없을 때만 true.
 */
internal fun isDirectChildOf(path: Path, directory: Path): Boolean =
    path.normalized().parent == directory.normalized()

/**
 * [path]가 [currentDirectory]의 직계 자식이거나, 같은 플랫폼 컨테이너 루트 아래 이전 앱 컨테이너
 * UUID 안의 같은 디렉터리인지 여부.
 *
 * iOS는 컨테이너 UUID를 바꾸면서도 샌드박스의 내용물은 보존한다. 컨테이너 루트와 디렉터리 이름을
 * 비교하면 관계없는 외부 `Documents` 디렉터리를 앱 소유로 취급하지 않으면서도 그러한 재배치를
 * 받아들일 수 있다.
 *
 * @param path 더 오래된 앱 컨테이너 UUID를 갖고 있을 수도 있는 후보 저장 경로.
 * @param currentDirectory 현재 앱 컨테이너의 materialize된 문서 디렉터리.
 * @return [path]가 현재 형태 또는 재배치된 형태의 그 디렉터리에 속할 때 true.
 */
internal fun isDirectChildOfCurrentOrRelocatedDirectory(path: Path, currentDirectory: Path): Boolean {
    val candidate = path.normalized()
    val current = currentDirectory.normalized()
    if (candidate.parent == current) return true
    val storedDirectory = candidate.parent ?: return false
    val storedContainer = storedDirectory.parent ?: return false
    val currentContainer = current.parent ?: return false
    return storedDirectory.name == current.name && storedContainer.parent == currentContainer.parent
}

/**
 * 앱 저장소로 복사된 문서에 부여되는 이름. 사용되지 않는 번호나 임시 파일 접미사가 아니라 그것이
 * 어디서 왔는지에서 유도된다.
 *
 * 예전에는 두 플랫폼 모두 사본마다 새 이름을 지어냈다 — Android는 `File.createTempFile`, iOS는
 * `-2`, `-3` 접미사 — 그래서 같은 책을 다른 앱에서 "다른 앱으로 열기" 할 때마다 전체 사본을 하나
 * 더 쓰고, 새 id로 다시 임포트하고, shelf에 카드 하나를 더 남겼다. 사본을 원본 이름을 따서 명명하면
 * 두 번째로 열 때 첫 번째 사본을 찾게 되고, 이는 또한 DocumentRepositoryImpl.importDocument가 이미
 * shelf에 있는 책을 인식하고 그냥 열 수 있게 해주는 방법이기도 하다.
 *
 * 해시가 접두사가 아니라 이름 그 자체인 이유: 표시 이름은 데이터베이스에 저장되고 거기서 보여지므로
 * 파일에는 사람이 읽을 수 있는 이름이 필요 없고, 해싱하면 파일시스템이 무엇을 받아들일지에 대한
 * 모든 질문을 피할 수 있다. 확장자는 포맷 감지가 그것을 읽으므로 유지된다.
 */
internal fun materializedDocumentFileName(sourceKey: String, displayName: String): String {
    val name = displayName.substringAfterLast('/').substringAfterLast('\\')
    val extension = name.substringAfterLast('.', "")
        .takeIf { it.isNotBlank() && it.length <= MaxMaterializedExtensionLength && it.all(Char::isLetterOrDigit) }
    val hash = sourceKey.encodeUtf8().sha1().hex()
    return if (extension == null) hash else "$hash.$extension"
}

/**
 * [materializedDocumentFileName]이 실제 파일 확장자로 취급할 최대 접미사 길이. 마지막 점 뒤에 긴
 * 문자/숫자 연속으로 끝나는 표시 이름은 실제 확장자라기보다 버전 문자열이나 ID일 가능성이 크므로,
 * 이 길이를 넘으면 유지하지 않고 버려진다.
 */
private const val MaxMaterializedExtensionLength = 8
