package com.tedd.teddreader.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Material의 15개 역할과 Material에 이름이 없어 앱에서 필요한 7개 역할로 구성된 앱 활자 척도입니다.
 *
 * 기본 컴포넌트가 앱의 활자를 사용하도록 Material 역할을 포함합니다(`toMaterialTypography` 참조). 나머지
 * 7개 역할이 없다면 화면마다 같은 `TextStyle`을 로컬 `copy()`로 다시 만들어야 하며, 같은 라이브러리
 * 제목도 표시하는 화면마다 조금씩 다른 스타일이 됩니다.
 *
 * @property displayLarge 히어로 제목에 사용하는 가장 큰 디스플레이 크기입니다.
 * @property displayMedium 디스플레이 제목에 사용하는 크기입니다.
 * @property displaySmall 가장 작은 디스플레이 제목 크기입니다.
 * @property headlineLarge 화면 자체의 제목에 사용하는 크기입니다.
 * @property headlineMedium 섹션 제목에 사용하는 크기입니다.
 * @property headlineSmall 하위 섹션 제목에 사용하는 크기입니다.
 * @property titleLarge 바 제목에 사용하는 크기입니다.
 * @property titleMedium 카드나 다이얼로그 제목에 사용하는 크기입니다.
 * @property titleSmall 목록 헤더처럼 조밀한 제목에 사용하는 크기입니다.
 * @property bodyLarge 앱 크롬에서 읽기 크기의 본문 텍스트에 사용하는 스타일입니다.
 * @property bodyMedium 앱의 기본 본문 크기입니다.
 * @property bodySmall 공간이 좁을 때 사용하는 본문 텍스트 크기입니다.
 * @property labelLarge 버튼 레이블에 사용하는 스타일입니다.
 * @property labelMedium 칩이나 탭 레이블에 사용하는 스타일입니다.
 * @property labelSmall 배지와 캡션에 사용하는 가장 작은 레이블 스타일입니다.
 * @property documentTitle 모든 목록과 카드가 책 제목을 동일하게 렌더링하도록 라이브러리의 책 제목에
 * 사용하는 스타일입니다.
 * @property documentMeta 책 제목 옆의 형식, 크기, 날짜에 사용하는 스타일입니다.
 * @property settingTitle 설정 행의 이름에 사용하는 스타일입니다.
 * @property settingDescription 설정 행 아래의 설명에 사용하는 스타일입니다.
 * @property statValue 통계와 문서 정보 화면의 큰 수치에 사용하는 스타일입니다.
 * @property readerBody 이 척도가 아니라 독자 고유의 [ReaderStyle]로 꾸미는 *책* 텍스트와 구분되는 리더
 * 자체의 크롬 텍스트 스타일입니다.
 * @property readerCaption 페이지 카운터처럼 리더 안에 표시하는 캡션 스타일입니다.
 */
@Immutable
data class TeddReaderTypography(
    val displayLarge: TextStyle,
    val displayMedium: TextStyle,
    val displaySmall: TextStyle,
    val headlineLarge: TextStyle,
    val headlineMedium: TextStyle,
    val headlineSmall: TextStyle,
    val titleLarge: TextStyle,
    val titleMedium: TextStyle,
    val titleSmall: TextStyle,
    val bodyLarge: TextStyle,
    val bodyMedium: TextStyle,
    val bodySmall: TextStyle,
    val labelLarge: TextStyle,
    val labelMedium: TextStyle,
    val labelSmall: TextStyle,
    val documentTitle: TextStyle,
    val documentMeta: TextStyle,
    val settingTitle: TextStyle,
    val settingDescription: TextStyle,
    val statValue: TextStyle,
    val readerBody: TextStyle,
    val readerCaption: TextStyle,
)

/** 크롬이 아니라 읽기에 맞춰 크기, 굵기, 자간을 조정해 테마가 설치하는 활자 척도입니다. */
val DefaultTeddReaderTypography = TeddReaderTypography(
    displayLarge = TextStyle(fontSize = 52.sp, lineHeight = 58.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.6).sp),
    displayMedium = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.4).sp),
    displaySmall = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp),
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    documentTitle = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    documentMeta = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    settingTitle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    settingDescription = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    statValue = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    readerBody = TextStyle(fontSize = 18.sp, lineHeight = 31.sp, fontWeight = FontWeight.Normal),
    readerCaption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

/**
 * 기본 컴포넌트가 앱의 활자를 사용하도록 이 척도의 Material 부분을 Material에 전달합니다.
 *
 * 앱 고유 역할은 의도적으로 제외합니다. Material에는 이를 담을 슬롯이 없으며, 필요한 화면은 앱 테마에서
 * 직접 읽습니다.
 *
 * @receiver 앱의 전체 활자 척도입니다.
 * @return `MaterialTheme(typography = …)`에 전달할 수 있도록 Material 역할만 Material 타입으로 표현한
 * 값입니다.
 */
fun TeddReaderTypography.toMaterialTypography(): Typography = Typography(
    displayLarge = displayLarge,
    displayMedium = displayMedium,
    displaySmall = displaySmall,
    headlineLarge = headlineLarge,
    headlineMedium = headlineMedium,
    headlineSmall = headlineSmall,
    titleLarge = titleLarge,
    titleMedium = titleMedium,
    titleSmall = titleSmall,
    bodyLarge = bodyLarge,
    bodyMedium = bodyMedium,
    bodySmall = bodySmall,
    labelLarge = labelLarge,
    labelMedium = labelMedium,
    labelSmall = labelSmall,
)
