package com.tedd.teddreader.core.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tedd.teddreader.core.common.model.DocumentLocation
import java.io.File
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.Path.Companion.toPath
import okio.source

/**
 * Android용 [DocumentFileSource]: 일반 `file://` 경로로 접근하거나, 더 흔하게는 문서 선택기나
 * 다른 앱의 공유 시트가 넘겨주는 Storage Access Framework `content://` Uri로 접근하는 문서를
 * 읽고 복사한다.
 *
 * @param context 임의의 컨텍스트; `applicationContext`만 보관하므로, 생성 시 넘겨받은 수명이 더
 *   짧은 컨텍스트보다 이 클래스가 더 오래 살아남는(또는 그것을 누수시키는) 일이 없다.
 */
class AndroidDocumentFileSource(
    context: Context,
) : DocumentFileSource {
    /** [context]의 애플리케이션 컨텍스트. [context] 자체 대신 이것을 보관해 이 인스턴스가 그것보다 오래 살지 못하게 한다. */
    private val appContext = context.applicationContext

    /** [appContext]의 콘텐츠 리졸버. `content://` 문서를 열고, 읽고, 권한을 영속화하는 데 쓰인다. */
    private val contentResolver = appContext.contentResolver

    /**
     * @param location 읽을 문서; 두 스킴 모두 허용된다.
     * @return 문서의 원본 바이트. `file://` [location]이면 [File]로, 그 외에는 콘텐츠 리졸버의
     *   `openInputStream`으로 읽는다.
     * @throws IllegalStateException 파일이 존재하지 않거나 콘텐츠 Uri를 열 수 없는 경우.
     */
    override suspend fun readBytes(location: DocumentLocation): ByteArray =
        when (Uri.parse(location.sourceUri).scheme) {
            "file" -> File(Uri.parse(location.sourceUri).path ?: error("Cannot open document: ${location.sourceUri}"))
                .readBytes()

            else -> contentResolver.openInputStream(Uri.parse(location.sourceUri))
                ?.use { input -> input.readBytes() }
                ?: error("Cannot open document: ${location.sourceUri}")
        }

    /**
     * @param location 복사해 올 문서; 두 스킴 모두 허용된다.
     * @param destination 복사본을 쓸 위치.
     * @throws IllegalStateException 파일이 존재하지 않거나 콘텐츠 Uri를 열 수 없는 경우.
     */
    override suspend fun copyTo(location: DocumentLocation, destination: Path) {
        when (Uri.parse(location.sourceUri).scheme) {
            "file" -> FileSystem.SYSTEM.source(
                File(Uri.parse(location.sourceUri).path ?: error("Cannot open document: ${location.sourceUri}")).toOkioPath(),
            ).buffer().use { source ->
                FileSystem.SYSTEM.sink(destination).buffer().use { sink ->
                    sink.writeAll(source)
                }
            }

            else -> contentResolver.openInputStream(Uri.parse(location.sourceUri))
                ?.use { input ->
                    FileSystem.SYSTEM.sink(destination).buffer().use { sink ->
                        sink.writeAll(input.source())
                    }
                }
                ?: error("Cannot open document: ${location.sourceUri}")
        }
    }

    /**
     * [bytes]를 이 문서의 앱 전용 파일에 쓴다. 같은 크기의 파일이 이미 있으면 쓰기를 건너뛴다 —
     * 이 앱이 이미 임포트한 소스에 대해 두 번째 `materialize` 호출이 들어왔을 때 문서 전체를
     * 다시 쓰는 것을 피하는, 비용이 적게 드는 멱등성 체크다.
     *
     * @param location 문서의 현재 위치.
     * @param bytes 문서의 바이트.
     * @return 구체화된 `file://` Uri를 가리키도록 갱신된 [location]. `sizeBytes`는 실제로 쓰인
     *   바이트 수로 설정된다.
     */
    override suspend fun materialize(location: DocumentLocation, bytes: ByteArray): DocumentLocation {
        val file = documentFile(location)
        if (file.length() != bytes.size.toLong()) file.writeBytes(bytes)
        return location.copy(
            sourceUri = Uri.fromFile(file).toString(),
            sizeBytes = bytes.size.toLong(),
        )
    }

    /**
     * 저장된 파일은 그 정규 경로(canonical path)가 이 앱의 `filesDir/documents` 디렉터리의 직접
     * 자식일 때만 삭제한다. Content URI와 외부 파일 경로는 건드리지 않는다; 레거시 구체화 파일명도
     * 여전히 삭제 대상이 된다 — 파일을 앱이 소유하는지 여부는 현재의 명명 규칙이 아니라 디렉터리
     * 소유권이 결정하기 때문이다.
     *
     * @param location 서가 행(row)이 삭제되기 전에 캡처된 저장 위치.
     * @throws IllegalStateException 소유한 파일이 존재하지만 파일시스템이 삭제를 거부하는 경우.
     */
    override suspend fun deleteMaterialized(location: DocumentLocation) {
        val uri = Uri.parse(location.sourceUri)
        if (uri.scheme != "file") return
        val sourcePath = uri.path ?: return
        val candidate = File(sourcePath).canonicalFile
        val directory = File(appContext.filesDir, "documents").canonicalFile
        if (!isDirectChildOf(candidate.toOkioPath(), directory.toOkioPath())) return
        if (candidate.exists()) check(candidate.delete()) { "Cannot delete materialized document: $candidate" }
    }

    /**
     * [location]을 원본 소스로부터 바로 구체화한다. [materialize]가 요구하는 방식대로 바이트가
     * 이미 메모리에 로드되지 않은, 방금 선택된 문서를 위한 것이다 — [materialize]에 넘겨주기
     * 위해서만 먼저 [ByteArray]로 읽는다면 이유 없이 문서 전체를 메모리에 두 번 들고 있는 셈이
     * 된다.
     *
     * 이 문서에 이미 사본이 있다면 다시 쓰지 않고 재사용한다: 다른 앱이 같은 책을 두 번째로
     * 넘겨줄 때 전에는 새 이름으로 전체를 다시 썼고([materializedDocumentFileName] 참고), 그
     * 결과 이 앱도 그것을 다시 임포트하게 됐다. 대상 파일이 이미 어떤 내용이든 가진 채 존재하는지
     * 확인함으로써 첫 번째 전달 시 쓰인 파일을 찾아내고, 복사와 재임포트를 둘 다 건너뛴다.
     *
     * @param location 원본 소스를 가리키는 문서의 현재 위치.
     * @return 구체화된 `file://` Uri를 가리키도록 갱신된 [location]. `sizeBytes`는 디스크상의
     *   파일에서 다시 읽어들인 값이다.
     */
    suspend fun materializeFromSource(location: DocumentLocation): DocumentLocation {
        val file = documentFile(location)
        if (!file.exists() || file.length() == 0L) copyTo(location, file.toOkioPath())
        return location.copy(
            sourceUri = Uri.fromFile(file).toString(),
            sizeBytes = file.length(),
        )
    }

    /** @return [appContext]의 files 디렉터리, 즉 [Context.getFilesDir]. */
    override fun appPrivateDirectory(): Path = appContext.filesDir.toOkioPath()

    /**
     * 문서 선택기나 공유 시트 Intent가 [sourceUri]에 대해 부여한 읽기 권한을 영속화해서, 프로세스
     * 재시작이나 재부팅 후에도 사용자가 문서를 다시 선택하지 않고 이 앱이 같은 `content://` Uri를
     * 다시 열 수 있게 한다.
     *
     * @param sourceUri 부여된 콘텐츠 Uri.
     * @param grantFlags Intent의 `flags`; 읽기 권한 플래그만, 있는 경우에 한해 영속화된다.
     */
    fun persistReadPermission(sourceUri: String, grantFlags: Int) {
        val readFlag = grantFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (readFlag != 0) {
            contentResolver.takePersistableUriPermission(Uri.parse(sourceUri), readFlag)
        }
    }

    /**
     * [location]의 구체화된 사본이 저장되는(또는 저장될) 앱 전용 파일. [appContext]의 files
     * 디렉터리 아래 `documents` 서브디렉터리에 두며, 존재하지 않으면 생성한다.
     * [materializedDocumentFileName]으로 이름을 붙여, 같은 소스는 항상 같은 파일로 해석되게 한다.
     *
     * @param location 구체화된 파일을 해석할 대상 문서.
     * @return 이 문서의 바이트가 쓰여 있거나 쓰여야 할 [File].
     */
    private fun documentFile(location: DocumentLocation): File {
        val directory = File(appContext.filesDir, "documents").apply { mkdirs() }
        return File(directory, materializedDocumentFileName(location.sourceUri, location.displayName))
    }
}

/**
 * @receiver okio [Path]로 다룰 [File].
 * @return 이 파일의 절대 경로를 okio [Path]로 나타낸 것.
 */
private fun File.toOkioPath(): Path = absolutePath.toPath()
