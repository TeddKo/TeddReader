package com.tedd.teddreader.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The app's type scale: Material's fifteen roles plus seven the app needs that Material has no name for.
 *
 * The Material roles are here so stock components take the app's type (see `toMaterialTypography`), and the
 * seven extras exist because the alternative was screens re-deriving the same `TextStyle` with a local
 * `copy()` — a library title styled slightly differently on every screen that shows one.
 *
 * @property displayLarge the largest display size, for a hero heading.
 * @property displayMedium a display heading.
 * @property displaySmall the smallest display heading.
 * @property headlineLarge a screen's own heading.
 * @property headlineMedium a section heading.
 * @property headlineSmall a subsection heading.
 * @property titleLarge a bar title.
 * @property titleMedium a card or dialog title.
 * @property titleSmall a dense title, as in a list header.
 * @property bodyLarge body text at reading size for app chrome.
 * @property bodyMedium the app's default body size.
 * @property bodySmall body text where space is tight.
 * @property labelLarge a button's label.
 * @property labelMedium a chip or tab label.
 * @property labelSmall the smallest label, for badges and captions.
 * @property documentTitle a book's title in the library, so every list and card renders one the same way.
 * @property documentMeta a book's format, size and dates beside that title.
 * @property settingTitle the name of a setting row.
 * @property settingDescription the explanation under a setting row.
 * @property statValue a large figure on the statistics and document-info screens.
 * @property readerBody the reader's own chrome text, distinct from the *book's* text, which is styled from
 * the reader's own [ReaderStyle] rather than from this scale.
 * @property readerCaption a caption inside the reader, such as a page counter.
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

/** The type scale the theme installs: sizes, weights and letter spacing tuned for reading, not for chrome. */
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
 * Hands the Material half of this scale to Material, so a stock component takes the app's type.
 *
 * The app-specific roles are deliberately left out: Material has no slot for them, and a screen that wants
 * one reads it from the app theme instead.
 *
 * @receiver the app's full type scale.
 * @return only its Material roles, in Material's own type, for `MaterialTheme(typography = …)`.
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
