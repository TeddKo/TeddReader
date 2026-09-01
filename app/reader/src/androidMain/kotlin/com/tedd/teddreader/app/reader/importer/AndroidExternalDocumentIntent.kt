package com.tedd.teddreader.app.reader.importer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

/**
 * 실제 [Intent] 대신 일반 문자열/액션 필드만 받아 수신 인텐트가 전달하는 문서를 실제로 식별하는 URI
 * 문자열을 선택한다. 두 가지 지원 인텐트 형태의 해석 로직을 Android [Intent]를 만들지 않고 단위
 * 테스트할 수 있게 한다.
 *
 * @param action 인텐트 액션이며 [Intent.ACTION_VIEW] 또는 [Intent.ACTION_SEND]여야 한다. 일반 실행
 *   인텐트를 포함한 다른 값은 문서가 없는 것으로 처리한다.
 * @param dataUri 문자열로 표현한 인텐트의 `data` URI다. 다른 앱이나 파일 브라우저에서 "TeddReader로
 *   열기"를 실행하는 [Intent.ACTION_VIEW]에 사용한다.
 * @param streamUri 문자열로 표현한 인텐트의 `EXTRA_STREAM` URI다. 공유 시트 대상인
 *   [Intent.ACTION_SEND]에 사용한다.
 * @return 문서를 식별하는 URI 문자열이다. [action]이 지원하는 액션이 아니거나 해당 URI 필드가
 *   null이면 null이다.
 */
internal fun externalDocumentUriString(
    action: String?,
    dataUri: String?,
    streamUri: String?,
): String? = when (action) {
    Intent.ACTION_VIEW -> dataUri
    Intent.ACTION_SEND -> streamUri
    else -> null
}

/**
 * 이 인텐트의 실제 `data`/`EXTRA_STREAM` 필드에 [externalDocumentUriString]의 액션 기반 규칙을
 * 적용하여 첨부 문서 [Uri]가 있으면 해석한다.
 *
 * 폐기 예정인 단일 인자 `getParcelableExtra` 사용을 억제한다. 이를 대체하는 타입 안전한 두 인자
 * 오버로드는 API 33부터 존재하지만 이 모듈의 `minSdk`는 24이므로, 폐기 예정인 reified 오버로드만 이
 * 앱이 지원하는 모든 기기에서 사용할 수 있다.
 *
 * @receiver 검사할 수신 인텐트다.
 * @return 인텐트가 전달하는 문서 [Uri]이며, 문서가 없으면 null이다.
 */
@Suppress("DEPRECATION")
internal fun Intent.externalDocumentUri(): Uri? {
    val streamUri = getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
    return externalDocumentUriString(
        action = action,
        dataUri = data?.toString(),
        streamUri = streamUri?.toString(),
    )?.let(Uri::parse)
}

/**
 * 수신 인텐트가 나타내는 [ExternalDocumentImportRequest]를 구성한다. Android 임포터의 다른 부분이
 * 사용자가 선택한 문서의 메타데이터를 해석하는 것과 같은 방식으로
 * [ContentResolver.queryDocumentMetadata]를 통해 표시 이름, MIME 타입, 크기를 해석하므로 외부에서
 * 전달된 문서도 앱 안에서 선택한 문서와 동일하게 처리된다.
 *
 * @param intent 호스팅 activity를 시작했거나 새 대상으로 지정한 인텐트다.
 * @param context 문서의 메타데이터와 MIME 타입을 해석하는 [ContentResolver]에 접근하는 데 사용한다.
 * @return 해석된 가져오기 요청이다. [intent]가 문서를 전달하지 않으면 null이다
 *   ([Intent.externalDocumentUri] 참고).
 */
fun androidExternalDocumentImportRequest(
    intent: Intent,
    context: Context,
): ExternalDocumentImportRequest? {
    val uri = intent.externalDocumentUri() ?: return null
    val resolver = context.contentResolver
    val metadata = resolver.queryDocumentMetadata(uri)
    return ExternalDocumentImportRequest(
        sourceUri = uri.toString(),
        displayName = metadata.displayName ?: uri.lastPathSegment,
        mimeType = intent.type ?: resolver.getType(uri),
        sizeBytes = metadata.sizeBytes ?: 0L,
        grantFlags = intent.flags,
    )
}

/**
 * [ContentResolver.queryDocumentMetadata]가 content provider의 `OpenableColumns` 프로젝션에서 읽을 수
 * 있는 문서 메타데이터다. provider가 열을 생략하면 [androidExternalDocumentImportRequest]와 Android
 * 임포터의 나머지 부분이 대체값을 적용하기 전의 값이다.
 *
 * @property displayName provider가 보고한 파일 이름이다. provider에 `DISPLAY_NAME` 열이 없거나 행을
 *   반환하지 않았으면 null이다.
 * @property sizeBytes provider가 보고한 바이트 크기다. provider에 `SIZE` 열이 없거나 행을 반환하지
 *   않았거나 크기를 null로 보고했으면 null이다.
 */
internal data class AndroidDocumentMetadata(
    val displayName: String?,
    val sizeBytes: Long?,
)

/**
 * 모든 Android content provider가 지원해야 하는 표준 [OpenableColumns] 프로젝션을 통해 content
 * [Uri]의 표시 이름과 크기를 조회한다. provider가 cursor나 행을 반환하지 않거나 어느 열이든
 * 생략해도 처리한다.
 *
 * @receiver 조회에 사용할 resolver다.
 * @param uri 설명할 content URI다.
 * @return provider가 보고한 메타데이터다. provider가 제공하지 않은 필드는 각각 null이다.
 */
internal fun ContentResolver.queryDocumentMetadata(uri: Uri): AndroidDocumentMetadata = query(
    uri,
    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
    null,
    null,
    null,
)?.use { cursor ->
    if (cursor.moveToFirst()) {
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        AndroidDocumentMetadata(
            displayName = nameIndex.takeIf { it >= 0 }?.let(cursor::getString),
            sizeBytes = sizeIndex.takeIf { it >= 0 }?.let(cursor::getLong),
        )
    } else {
        AndroidDocumentMetadata(displayName = null, sizeBytes = null)
    }
} ?: AndroidDocumentMetadata(displayName = null, sizeBytes = null)
