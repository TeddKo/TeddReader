package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderShapes
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.icon.TeddIcons

/**
 * 앱의 기본 텍스트 입력: 앱의 모양 스케일과 활자 스타일에 고정된 [OutlinedTextField]로, 컨테이너
 * 색상이 모든 상태(포커스, 비포커스, 비활성)에서 서피스 색상으로 고정되어 있어, 필드가 Material의
 * 기본 상태 의존적 컨테이너 색조를 결코 보여주지 않는다. 그 색조는 이 필드가 보통 놓이는 서피스
 * (옵션 시트, 폼)와 충돌했을 것이다. 이 모듈의 다른 모든 입력이 사용하는 것과 같은 48dp 최소 터치
 * 높이도 함께 강제한다.
 *
 * @param value 필드의 현재 텍스트.
 * @param onValueChange 사용자가 필드를 편집할 때마다 새 텍스트와 함께 호출된다.
 * @param modifier `fillMaxWidth()`와 48dp 최소 높이가 적용된 뒤, 필드에 적용되는 modifier.
 * @param label 필드 콘텐츠 위에 표시되는 떠 있는 라벨. null이면 생략된다.
 * @param placeholder [value]가 비어 있을 때 표시되는 텍스트. null이면 생략된다.
 * @param enabled 필드가 입력을 받을지 여부.
 * @param minLines 보이는 텍스트 줄의 최소 개수.
 * @param maxLines 필드가 내부적으로 스크롤되기 전, 보이는 텍스트 줄의 최대 개수.
 */
@Composable
fun TeddTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
) {
    val colors = teddReaderColors()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = teddReaderSpacing().touchTarget),
        label = label?.let { { TeddText(text = it) } },
        placeholder = placeholder?.let { { TeddText(text = it) } },
        enabled = enabled,
        minLines = minLines,
        maxLines = maxLines,
        shape = teddReaderShapes().medium,
        textStyle = teddReaderTypography().bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            disabledContainerColor = colors.surfaceContainerLow,
        ),
    )
}

/**
 * [OutlinedTextField] 위에 만든 한 줄짜리 검색 입력으로, IME의 검색 액션([ImeAction.Search])에
 * 연결되어 있고 [TeddTextField]와 마찬가지로 모든 상태에서 같은 서피스 색상 컨테이너로 고정되어
 * 있으며, [OutlinedTextField] 자체는 그저 원시 컴포저블 슬롯으로만 노출하는 leading/trailing 아이콘
 * 슬롯(보통 검색 글리프와 지우기 버튼)을 위한 자리를 마련해 둔다.
 *
 * @param value 필드의 현재 텍스트.
 * @param onValueChange 사용자가 필드를 편집할 때마다 새 텍스트와 함께 호출된다.
 * @param modifier `fillMaxWidth()`와 48dp 최소 높이가 적용된 뒤, 필드에 적용되는 modifier.
 * @param placeholder [value]가 비어 있을 때 표시되는 텍스트. 기본값은 지역화되지 않은 리터럴
 * 문자열 `"Search"`다 — 지역화된 화면의 호출자는 번역된 문자열을 명시적으로 전달해야 한다.
 * @param enabled 필드가 입력을 받을지 여부.
 * @param onSearch 사용자가 IME의 검색 액션을 트리거할 때 호출된다. 다른 어떤 이벤트에도 연결되어
 * 있지 않다.
 * @param leadingContent 필드의 시작 부분에 표시되는 콘텐츠로, 보통 검색 아이콘이다. null이면
 * 생략된다.
 * @param trailingContent 필드의 끝 부분에 표시되는 콘텐츠로, 보통 지우기 버튼이다. null이면
 * 생략된다.
 */
@Composable
fun TeddSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    enabled: Boolean = true,
    onSearch: (() -> Unit)? = null,
    onClearClick: (() -> Unit)? = null,
    clearDescription: String? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val colors = teddReaderColors()
    val resolvedTrailingContent: (@Composable () -> Unit)? = when {
        trailingContent != null -> trailingContent
        onClearClick != null && value.isNotEmpty() -> {
            {
                TeddIconButton(
                    onClick = onClearClick,
                    contentDescription = clearDescription ?: "Clear search",
                ) {
                    TeddIcon(
                        imageVector = TeddIcons.Close,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
        }
        else -> null
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = teddReaderSpacing().touchTarget),
        placeholder = { TeddText(text = placeholder) },
        leadingIcon = leadingContent,
        trailingIcon = resolvedTrailingContent,
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
        shape = teddReaderShapes().medium,
        textStyle = teddReaderTypography().bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            disabledContainerColor = colors.surfaceContainerLow,
        ),
    )
}
