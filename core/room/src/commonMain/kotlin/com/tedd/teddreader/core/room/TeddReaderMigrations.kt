package com.tedd.teddreader.core.room

import androidx.room3.migration.Migration
import androidx.sqlite.execSQL

/** 책을 즐겨찾기에 넣을 수 있기 전에는 없었던 라이브러리의 즐겨찾기 플래그를 추가합니다. */
internal val TeddReaderMigration1To2 = Migration(1, 2) { connection ->
    connection.execSQL(
        "ALTER TABLE documents ADD COLUMN isBookmarked INTEGER NOT NULL DEFAULT 0",
    )
}

/**
 * 라이브러리에 폴더 소속 정보를 추가합니다. 두 열은 모두 nullable이며 항상 함께 기록됩니다. 폴더에 속한 행에는
 * 폴더 이름이 있고, 폴더 밖의 행에는 둘 다 없습니다.
 */
internal val TeddReaderMigration2To3 = Migration(2, 3) { connection ->
    connection.execSQL(
        "ALTER TABLE documents ADD COLUMN folderId TEXT",
    )
    connection.execSQL(
        "ALTER TABLE documents ADD COLUMN folderName TEXT",
    )
}

/**
 * 저장된 텍스트의 스타일을 지정하는 섹션별 블록 구조를 추가합니다. 이전 빌드에서 가져온 책도 다시 파싱될 때까지
 * 일반 텍스트로 읽을 수 있도록 빈 배열을 기본값으로 사용합니다.
 */
internal val TeddReaderMigration3To4 = Migration(3, 4) { connection ->
    connection.execSQL(
        "ALTER TABLE search_index ADD COLUMN blocksJson TEXT NOT NULL DEFAULT '[]'",
    )
}

/**
 * 문서 제목과 목차를 검색 인덱스에 추가합니다. 책 전체에 속하는 정보지만, 열린 문서가 이미 불러오는 섹션 행에서
 * 리더가 다시 읽기 때문입니다.
 */
internal val TeddReaderMigration4To5 = Migration(4, 5) { connection ->
    connection.execSQL(
        "ALTER TABLE search_index ADD COLUMN documentTitle TEXT",
    )
    connection.execSQL(
        "ALTER TABLE search_index ADD COLUMN navigationJson TEXT NOT NULL DEFAULT ''",
    )
}

/**
 * 페이지 레이아웃 캐시를 생성합니다. 측정값이 유효한 기준은 문서, 글자 설정, 뷰포트 전체 조합이므로 이를 함께
 * 키로 사용합니다. `ON DELETE CASCADE`는 행을 문서에 연결해 책을 삭제할 때 측정값이 남지 않게 합니다.
 */
internal val TeddReaderMigration5To6 = Migration(5, 6) { connection ->
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `page_layouts` (`documentId` TEXT NOT NULL, `fontSizeSp` REAL NOT NULL, " +
            "`lineHeightMultiplier` REAL NOT NULL, `fontFamilyName` TEXT NOT NULL, `viewportWidthPx` INTEGER NOT NULL, " +
            "`viewportHeightPx` INTEGER NOT NULL, `characterCount` INTEGER NOT NULL, `pageStartsJson` TEXT NOT NULL, " +
            "`writtenAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`documentId`, `fontSizeSp`, `lineHeightMultiplier`, " +
            "`fontFamilyName`, `viewportWidthPx`, `viewportHeightPx`), FOREIGN KEY(`documentId`) REFERENCES " +
            "`documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    )
    connection.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_page_layouts_documentId` ON `page_layouts` (`documentId`)",
    )
}

/**
 * 저장된 텍스트를 작성한 파서 버전을 추가합니다. 이후 빌드는 숫자 비교로 저장된 텍스트가 파서 변경보다
 * 이전인지 알 수 있습니다. 블록 자체를 검사하는 대안은 첫 삽화가 292장에 있는 책에서 질문 하나를 위해 293개
 * 챕터를 디코딩해야 했습니다.
 */
internal val TeddReaderMigration6To7 = Migration(6, 7) { connection ->
    connection.execSQL(
        "ALTER TABLE search_index ADD COLUMN parserVersion INTEGER NOT NULL DEFAULT 0",
    )
}

/**
 * 이 마이그레이션을 위해 작성된 나머지 계획에 v7->v8이 두 번이 아닌 한 번만 필요하도록 서로 무관한 두 열을
 * 하나의 버전 증가에 포함합니다.
 *
 * `page_layouts.pageStartsBlob`은 `pageStartsJson`이 JSON Long 배열로 담던 동일한 페이지 시작점을 새로
 * little-endian Int32로 인코딩합니다. 이 JSON 디코딩은 큰 책 복원 비용의 대부분이었습니다. 이 마이그레이션 전
 * 행에는 읽을 BLOB이 없으며 페이지 레이아웃은 언제나 캐시일 뿐입니다. 페이지 분할은 입력이 같으면 결정적이므로
 * 다음에 열 때 책을 한 번 다시 측정해 완전히 같은 페이지 경계를 재현합니다. 따라서
 * `DELETE FROM page_layouts`는 안전합니다. 어떤 데이터도 잃지 않고 책마다 한 번만 다시 계산합니다.
 *
 * `documents.importCompletedAtEpochMillis`는 이후 변경에서 점진적 EPUB 가져오기를 제공할 때까지 사용하지
 * 않습니다. 기존 행은 이미 완전하므로 완료 상태로 채웁니다.
 */
internal val TeddReaderMigration7To8 = Migration(7, 8) { connection ->
    connection.execSQL(
        "ALTER TABLE documents ADD COLUMN importCompletedAtEpochMillis INTEGER",
    )
    connection.execSQL(
        "UPDATE documents SET importCompletedAtEpochMillis = addedAtEpochMillis",
    )
    connection.execSQL(
        "ALTER TABLE page_layouts ADD COLUMN pageStartsBlob BLOB",
    )
    connection.execSQL(
        "DELETE FROM page_layouts",
    )
}

/**
 * 페이지 수 확정 최적화를 지원하는 세 열입니다. `documents`의 인덱싱된 글꼴 href 캐시,
 * `search_index`에서 `finishEpubImport`가 전체 텍스트 스캔을 건너뛸 수 있게 하는 원본 경로 열, 불완전한 가져오기
 * 앞부분에서 만든 측정값과 완전한 측정값을 구분하는 `page_layouts`의 부분 레이아웃 플래그입니다.
 *
 * 데이터 백필 없이도 기존 행이 유효하도록 세 열 모두 nullable이거나 기본값을 갖습니다. 글꼴 인덱스와 원본
 * 경로는 지연해서 또는 다음 가져오기 배치에서 채우고, `isPartial` 열의 기본값 `0` (false)은 점진적 레이아웃
 * 캐시가 도입되기 전에 존재한 모든 행에 맞습니다.
 */
internal val TeddReaderMigration8To9 = Migration(8, 9) { connection ->
    connection.execSQL(
        "ALTER TABLE documents ADD COLUMN embeddedFontHrefsJson TEXT",
    )
    connection.execSQL(
        "ALTER TABLE search_index ADD COLUMN sourcePath TEXT",
    )
    connection.execSQL(
        "ALTER TABLE page_layouts ADD COLUMN isPartial INTEGER NOT NULL DEFAULT 0",
    )
}

/**
 * 두 플랫폼 빌더가 등록하는 단일 원본인 전체 마이그레이션을 순서대로 담은 목록입니다.
 *
 * 플랫폼마다 호출을 직접 작성하지 않고 목록 하나를 사용합니다. Room의 `RoomDatabase.Builder`에는 추가한 뒤
 * 마이그레이션을 다시 읽는 방법이 없으므로 중복된 두 목록이 감지되지 않은 채 달라질 수 있습니다.
 * TeddReaderMigrationListTest는 대신 이 목록을 순회해 체인이 버전을 건너뛰거나 데이터베이스 자체 버전보다
 * 일찍 끝나면 실패합니다.
 */
internal val TeddReaderMigrationList: List<Migration> = listOf(
    TeddReaderMigration1To2,
    TeddReaderMigration2To3,
    TeddReaderMigration3To4,
    TeddReaderMigration4To5,
    TeddReaderMigration5To6,
    TeddReaderMigration6To7,
    TeddReaderMigration7To8,
    TeddReaderMigration8To9,
)
