package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.suspendRunCatching
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.data.parser.EpubDocumentParser
import com.tedd.teddreader.core.data.parser.PdfDocumentParser
import com.tedd.teddreader.core.data.parser.systemFileSystem
import com.tedd.teddreader.core.data.storage.DocumentFileSource
import kotlin.random.Random
import okio.FileSystem
import okio.Path

/**
 * 문서의 표지 그림을 소유한다: 디스크 어디에 캐시되는지, 어떻게 다시 읽히는지, 아직 아무것도 캐시되지
 * 않았을 때 책에서 어떻게 추출되는지.
 *
 * 이 네 가지 관심사는 예전에 [DocumentRepositoryImpl] 안 네 곳에 흩어져 있었다 — 읽기와 쓰기는
 * `getDocumentCover` 안에, 추출은 그 옆의 private 메서드에, 삭제는 문서 삭제 경로 옆에, 그리고 쓰기는
 * 임포트 경로의 `persistParsedDocument` 안에 다시 한 번. 표지에 관한 어떤 것도 저장소의 캐시나 스크래치
 * 복사 락과 공유되지 않으므로, 클래스 전체에 흩어놓는 것은 얻는 것 없이 그 수명 주기 — 한 번 추출하고,
 * 영원히 파일에서 서빙하고, 문서와 함께 삭제한다 — 를 한곳에서 읽을 수 없게 만들었을 뿐이다.
 *
 * 여기서 말하는 표지 *추출*은 EPUB과 PDF만 다룬다. CBZ는 [DocumentRepositoryImpl]에 남아 있는데, 그
 * 표지는 공유된, 뮤텍스로 보호되는 코믹 아카이브 슬롯에서 나오기 때문이며, 그것을 그 락 바깥으로
 * 옮기는 일은 표지 파일이 어디에 사는지와는 별개의 관심사다.
 *
 * @property epubDocumentParser EPUB 컨테이너에서 선언된 표지 항목을 읽는다.
 * @property pdfDocumentParser PDF의 첫 페이지를 표지로 렌더링한다.
 * @property documentFileSource 표지가 캐시되는 앱 전용 디렉터리를 해석하고, EPUB 표지를 추출해야 할 때
 *   원본 파일을 스트리밍한다. 파일 접근이 없는 컨텍스트에서는 null이며, 이 경우 여기의 모든 동작이
 *   오류 대신 no-op이 된다.
 */
internal class DocumentCoverStore(
    private val epubDocumentParser: EpubDocumentParser,
    private val pdfDocumentParser: PdfDocumentParser,
    private val documentFileSource: DocumentFileSource?,
) {
    /**
     * [documentId]의 표지가 캐시되어 있는, 혹은 캐시될 위치.
     *
     * 호출자가 [coverFilePath]가 유도하는 해시를 다시 계산하지 않고도 진단 로그에서 경로를 언급할 수
     * 있도록 노출되어 있다.
     *
     * @param documentId 표지 경로를 해석할 문서.
     * @return 경로, 또는 이를 해석할 파일 접근이 없으면 null.
     */
    fun pathFor(documentId: DocumentId): Path? =
        documentFileSource?.let { fileSource -> coverFilePath(fileSource, documentId) }

    /**
     * [documentId]에 대해 이미 추출된 표지. 이전 요청이나 책을 처음 파싱한 임포트가 캐시해 두었다면.
     *
     * `use { }` 대신 Okio 자체의 `read { }` 스코핑을 쓴다: `okio.Closeable`은 Kotlin/Native에서
     * `kotlin.AutoCloseable`이 아니어서, `use`는 Android에서는 컴파일되지만 iOS 타겟에서는 실패한다.
     * [store]도 `write { }`로 같은 선례를 따른다.
     *
     * @param documentId 캐시된 표지를 읽을 문서.
     * @return 표지 바이트, 또는 캐시된 게 없거나 파일 접근이 없거나 읽기가 실패했을 때 null — 이 셋 모두
     *   호출자는 다시 추출하는 것으로 똑같이 처리한다.
     */
    fun cached(documentId: DocumentId): ByteArray? {
        val path = pathFor(documentId) ?: return null
        return runCatching { systemFileSystem().read(path) { readByteArray() } }.getOrNull()
    }

    /**
     * [bytes]를 [documentId]의 표지로 캐시해, 이후 어떤 요청도 책을 다시 열지 않아도 되게 한다.
     *
     * 실패는 삼켜진다: 캐시에 실패한 표지는 다음 요청에서 그냥 다시 추출될 뿐이며, 이는 그 표지를 만든
     * 요청 자체를 실패시키는 것보다 확실히 낫다.
     *
     * @param documentId 표지가 속한 문서.
     * @param bytes 기록할 표지 이미지 바이트.
     */
    fun store(documentId: DocumentId, bytes: ByteArray) {
        val path = pathFor(documentId) ?: return
        runCatching {
            path.parent?.let { parent -> systemFileSystem().createDirectories(parent) }
            systemFileSystem().write(path) { write(bytes) }
        }
    }

    /**
     * [documentId]의 캐시된 표지를 제거한다. 문서 삭제는 서가 행을 지우는 것 외에 이 작업도 해야 한다:
     * 표지 파일은 문서 id의 해시로 이름 붙기 때문에, 그렇지 않으면 같은 위치에서 다시 임포트된 책이
     * 이전 임포트의 그림을 그대로 받게 된다.
     *
     * @param documentId 캐시된 표지를 삭제할 문서.
     */
    fun delete(documentId: DocumentId) {
        val path = pathFor(documentId) ?: return
        runCatching { systemFileSystem().delete(path) }
    }

    /**
     * 이 스토어가 접근할 줄 아는 포맷에 대해, [metadata]의 표지를 책에서 직접 추출한다.
     *
     * @param metadata 추출할 대상 문서.
     * @return 표지 이미지 바이트, 또는 그 포맷의 표지가 이 스토어가 추출할 대상이 아니거나(CBZ, 또는
     *   표지가 아예 없는 포맷), 파일 접근이 없거나, 책이 읽을 수 있는 표지를 선언하지 않았으면 null.
     */
    suspend fun extract(metadata: DocumentMetadata): ByteArray? = when (metadata.format) {
        DocumentFormat.EPUB -> documentFileSource?.let { fileSource ->
            extractEpubCoverWithoutBuffering(metadata, fileSource)
        }
        DocumentFormat.PDF -> pdfDocumentParser.coverImageBytes(metadata.location, bytes = null)
        DocumentFormat.CBZ,
        DocumentFormat.TXT,
        DocumentFormat.IMAGE,
        DocumentFormat.UNKNOWN,
            -> null
    }

    /**
     * 책을 자체 수명이 짧은 임시 파일로 스트리밍한 뒤 거기서 표지 항목만 다시 읽어들여, EPUB의 표지를
     * 추출한다.
     *
     * 여기서 뻔해 보이는 대안 두 가지는 모두 틀렸다. 파일을 [ByteArray]로 먼저 읽어들이는 방식 — 이
     * 경로가 예전에 하던 방식 — 은 그림 한 장에 도달하기 위해 책 전체 크기를 힙에 부과했고, 수백
     * 메가바이트짜리 삽화가 많은 책은 저사양 메모리 기기에서 프로세스를 고갈시킬 수 있었다. 저장소의
     * 오래 지속되는 EPUB 스크래치 슬롯을 재사용하는 것은 다른 방식으로 더 나쁘다: 홈 화면은 여러 문서의
     * 표지를 요청하는데, 각 요청이 리더가 현재 열어둔 책의 스크래치 복사본을 몰아내 버려서 다음 페이지를
     * 넘길 때 그 책을 다시 복사하게 만들 것이다.
     *
     * 임시 파일은 `finally`에서 삭제되므로, 실패한 추출이 책의 전체 크기를 디스크에 남기지 않는다.
     * 저장소의 버려진-스크래치 정리는 이 접두사를 다루지 않는데, 이 함수보다 오래 남아 정리가 필요한
     * 것이 아무것도 없기 때문이다.
     *
     * @param metadata 표지를 추출할 EPUB. 스트리밍되는 것은 그 위치이다.
     * @param fileSource 원본 파일을 스트리밍해 오는 곳.
     * @return 표지 이미지의 바이트, 또는 복사가 실패했거나 책이 읽을 수 있는 표지를 선언하지 않았으면 null.
     */
    private suspend fun extractEpubCoverWithoutBuffering(
        metadata: DocumentMetadata,
        fileSource: DocumentFileSource,
    ): ByteArray? {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "tedd-reader-epub-cover-source-${Random.nextLong().toString(16)}.epub"
        return try {
            suspendRunCatching { fileSource.copyTo(metadata.location, path) }.getOrNull()
                ?: return null
            epubDocumentParser.coverImageBytes(path)
        } finally {
            runCatching { systemFileSystem().delete(path) }
        }
    }
}
