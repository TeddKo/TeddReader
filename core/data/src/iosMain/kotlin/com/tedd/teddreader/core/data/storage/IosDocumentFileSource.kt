package com.tedd.teddreader.core.data.storage

import com.tedd.teddreader.core.common.model.DocumentLocation
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/**
 * iOS의 [DocumentFileSource]: 문서를 샌드박스 파일시스템 경로로 읽고 복사하며, 이 클래스 자신의
 * [materialize]와 [copyIntoAppContainer]가 일관되게 저장하는 [DocumentLocation.sourceUri] 방식대로
 * `file://` 접두사로 주소를 지정한다.
 */
class IosDocumentFileSource : DocumentFileSource {
    /**
     * @param location 읽을 문서.
     * @return [location]의 해석된 경로에서 `NSData`로 읽은 문서의 원시 바이트.
     * @throws IllegalStateException [location]의 저장된 경로에도, 현재 컨테이너의 `Documents` 아래
     *   같은 파일 이름으로도 파일이 존재하지 않을 때.
     */
    override suspend fun readBytes(location: DocumentLocation): ByteArray {
        val path = resolveExistingPath(location) ?: error("Cannot open document: ${location.sourceUri}")
        val data = NSData.dataWithContentsOfFile(path) ?: error("Cannot open document: $path")
        return data.toByteArray()
    }

    /**
     * [location]이 *지금* 실제로 읽을 수 있는 파일시스템 경로.
     *
     * 저장된 `sourceUri`는 절대 경로인데, iOS에서는 앱 자체 샌드박스 안의 절대 경로가 그대로
     * 유지되지 않는다: 재설치 — 시뮬레이터로의 Xcode/Studio 리빌드든, 기기에서의 앱스토어
     * 업데이트든 — 는 데이터 컨테이너를 새 UUID로 옮기므로, 임포트 시점에 기록된 경로는 더 이상
     * 존재하지 않는 컨테이너를 가리키게 된다. 파일 자체는 살아남는다: iOS는 컨테이너의 *내용물*을
     * 마이그레이션하므로, 같은 이름의 materialize된 사본이 새 컨테이너의 `Documents` 아래 그대로
     * 있다. 읽기 시점에 해석하는 것 — 저장된 경로 먼저, 그다음 현재 `Documents` 아래 같은 파일
     * 이름 — 이 저장된 행의 마이그레이션 없이도 모든 책을 업데이트 전반에서 계속 읽을 수 있게
     * 해주는 방법이다.
     *
     * @param location 읽을 수 있는 경로가 필요한 문서.
     * @return 아직 존재하면 저장된 경로, 아니면 현재 컨테이너 폴백, 그것도 아니면 null.
     */
    private fun resolveExistingPath(location: DocumentLocation): String? {
        val stored = location.sourceUri.removePrefix("file://")
        if (fileSize(stored) > 0L) return stored
        val fileName = stored.substringAfterLast('/')
        if (fileName.isEmpty()) return null
        val fallback = "${NSHomeDirectory()}/Documents/$fileName"
        return fallback.takeIf { fileSize(it) > 0L }
    }

    /**
     * 문서를 해석된 샌드박스 경로에서 [destination]으로 네이티브 파일시스템을 통해 복사하며, 큰
     * EPUB과 CBZ 스크래치 사본이 이전에 유지했던 전체 파일 Kotlin [ByteArray]를 피한다. 기존
     * 목적지는 [DocumentFileSource.copyTo]의 싱크 형태의 덮어쓰기 계약을 보존하기 위해 교체된다.
     *
     * @param location 복사할 원본 문서.
     * @param destination 사본을 쓸 위치.
     * @throws IllegalStateException [location]의 저장된 경로나 재배치된 경로에 파일이 존재하지
     *   않거나, 네이티브 파일시스템이 목적지 사본을 만들 수 없을 때.
     */
    @OptIn(ExperimentalForeignApi::class)
    override suspend fun copyTo(location: DocumentLocation, destination: okio.Path) {
        val sourcePath = resolveExistingPath(location) ?: error("Cannot open document: ${location.sourceUri}")
        FileSystem.SYSTEM.delete(destination, mustExist = false)
        check(
            NSFileManager.defaultManager.copyItemAtPath(
                srcPath = sourcePath,
                toPath = destination.toString(),
                error = null,
            ),
        ) { "Cannot copy document to $destination" }
    }

    /**
     * [bytes]를 이 문서의 앱 전용 파일에 쓰되, 같은 크기의 파일이 이미 있으면 쓰기를 건너뛴다 — 이
     * 앱이 이미 임포트한 소스에 대해 두 번째 `materialize` 호출에서 문서 전체를 다시 쓰는 것을
     * 피하는 값싼 멱등성 검사다.
     *
     * @param location 문서의 현재 위치.
     * @param bytes 문서의 바이트.
     * @return materialize된 `file://` 경로를 가리키도록 갱신되고, `sizeBytes`가 실제로 쓰인 바이트
     *   수로 설정된 [location].
     */
    override suspend fun materialize(location: DocumentLocation, bytes: ByteArray): DocumentLocation {
        val destination = materializedPath(sourceKey = location.sourceUri, displayName = location.displayName)
        if (fileSize(destination) != bytes.size.toLong()) {
            val sink = FileSystem.SYSTEM.sink(destination.toPath()).buffer()
            try {
                sink.write(bytes)
            } finally {
                sink.close()
            }
        }
        return location.copy(
            sourceUri = "file://$destination",
            sizeBytes = bytes.size.toLong(),
        )
    }

    /**
     * 이 앱의 현재 또는 재배치된 `Documents` 디렉터리의 직계 자식들을 삭제한다. 컨테이너 루트
     * 비교는 관계없는 외부 디렉터리를 거부하면서도 UUID 변경에 걸친 [resolveExistingPath]와
     * 일치하며, 레거시 해시 이전 방식 materialize 이름도 대상에 포함시킨다.
     *
     * @param location shelf 행이 삭제되기 전에 캡처된 저장된 위치.
     * @throws IllegalStateException 소유한 파일이 존재하지만 파일시스템이 삭제를 거부할 때.
     */
    override suspend fun deleteMaterialized(location: DocumentLocation) {
        if (!location.sourceUri.startsWith("file://")) return
        val storedPath = location.sourceUri.removePrefix("file://").toPath().normalized()
        val currentDirectory = "${NSHomeDirectory()}/Documents".toPath().normalized()
        if (!isDirectChildOfCurrentOrRelocatedDirectory(storedPath, currentDirectory)) return

        val candidate = if (isDirectChildOf(storedPath, currentDirectory)) {
            storedPath
        } else {
            currentDirectory / storedPath.name
        }
        try {
            FileSystem.SYSTEM.delete(candidate, mustExist = false)
        } catch (cause: Throwable) {
            throw IllegalStateException("Cannot delete materialized document: $candidate", cause)
        }
    }

    /**
     * 원본 파일로부터 곧바로 문서를 materialize한다. 방금 선택되어 바이트가 아직 메모리에 로드되지
     * 않은 문서를 위한 것이다(진입점인 [materialize] 자체는 [ByteArray]가 필요하다). 이것은
     * `AndroidDocumentFileSource.materializeFromSource`의 iOS 쪽 대응물이다.
     *
     * 이 문서가 이미 갖고 있는 사본이 있다면 다시 쓰는 대신 재사용된다: 같은 책을 다른 앱에서 다시
     * 여는 것이, 예전에는 첫 사본 옆에 전체를 다시 쓰게 만들었다(
     * [materializedDocumentFileName] 참고). 파일을 읽어서 크기를 재는 대신 디스크상의 기존 파일
     * 크기만 확인하는 것이, 사본이 필요한지 결정하기 위해 그 첫 사본조차 두 번 읽히지 않게 해주는
     * 지점이다.
     *
     * @param sourcePath 원본 문서의 파일시스템 경로(`file://` 접두사 없음).
     * @param displayName 문서의 표시 이름. materialize된 파일 이름을 만드는 데 쓰이고 반환되는
     *   [DocumentLocation]에 저장된다.
     * @param mimeType 알려진 경우 문서의 MIME 타입. 반환되는 [DocumentLocation]에 저장된다.
     * @return materialize된 사본을 가리키는 [DocumentLocation].
     * @throws IllegalStateException 같은 크기의 사본이 아직 없고 `NSFileManager`가 [sourcePath]를
     *   materialize 목적지로 복사하는 데 실패할 때.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun copyIntoAppContainer(
        sourcePath: String,
        displayName: String,
        mimeType: String? = null,
    ): DocumentLocation {
        val destination = materializedPath(sourceKey = sourcePath, displayName = displayName)
        if (fileSize(destination) <= 0L) {
            check(
                NSFileManager.defaultManager.copyItemAtPath(
                    srcPath = sourcePath,
                    toPath = destination,
                    error = null,
                ),
            ) { "Cannot copy document: $sourcePath" }
        }
        return DocumentLocation(
            sourceUri = "file://$destination",
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = fileSize(destination),
        )
    }

    /**
     * @return `Documents`가 아니라 `Library/Caches`: 표지는 캐시 미스 시 책 자신의 바이트로부터
     *   값싸게 다시 만들어지므로(`DocumentRepositoryImpl.getDocumentCover` 참고), 읽기 데이터베이스
     *   (`Documents`를 실제로 쓰는 `TeddReaderDatabaseBuilder.ios.kt`와 달리) 이 파일은 iCloud로
     *   백업되거나 기기 내 Files 앱에 나타날 이유가 없다.
     */
    override fun appPrivateDirectory(): okio.Path =
        "${NSHomeDirectory()}/Library/Caches".toPath()

    /**
     * [sourceKey]로 식별되는 원본의 materialize된 사본이 쓰여 있거나, 쓰이게 될 `Documents` 디렉터리
     * 경로.
     *
     * @param sourceKey 원본 소스를 식별한다; [materializedDocumentFileName] 참고.
     * @param displayName 문서의 표시 이름; [materializedDocumentFileName] 참고.
     * @return materialize된 사본의 절대 파일시스템 경로.
     */
    private fun materializedPath(sourceKey: String, displayName: String): String =
        "${NSHomeDirectory()}/Documents/${materializedDocumentFileName(sourceKey, displayName)}"

    /** 아무것도 저장되어 있지 않은 경로에는 0. "아직 사본 없음"과 "빈 사본"이 같게 읽히도록 한다. */
    private fun fileSize(path: String): Long =
        FileSystem.SYSTEM.metadataOrNull(path.toPath())?.size ?: 0L
}

/**
 * 이 `NSData`의 바이트를 Kotlin [ByteArray]로 복사한다.
 *
 * @receiver 복사할 데이터.
 * @return 같은 길이의 [ByteArray]. 빈 입력은 네이티브 메모리를 건드리지 않고 빈 배열로 특수 처리된다.
 *   길이 0인 [ByteArray]를 pin하고 그 주소를 얻는 것은 Kotlin/Native에서 정의되지 않은 동작이기
 *   때문이다.
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val result = ByteArray(size)
    if (size == 0) return result

    result.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, size.convert())
    }
    return result
}
