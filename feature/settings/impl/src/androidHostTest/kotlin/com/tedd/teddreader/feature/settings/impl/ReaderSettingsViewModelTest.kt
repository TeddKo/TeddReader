package com.tedd.teddreader.feature.settings.impl

import com.tedd.teddreader.core.common.model.AppLanguage
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.AutoScrollMode
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.domain.repository.ReaderSettings
import com.tedd.teddreader.core.domain.repository.ReaderSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun updateMethodsForwardSettingsToRepository() = runTest(dispatcher) {
        val repository = FakeReaderSettingsRepository()
        val viewModel = ReaderSettingsViewModel(repository)
        val style = ReaderStyle(fontSizeSp = 22f, lineHeightMultiplier = 1.7f, fontFamilyName = "mono")

        viewModel.updateStyle(style)
        viewModel.updatePageTurnMode(PageTurnMode.VERTICAL)
        viewModel.updatePageAnimation(PageAnimation.CURL_PAGER)
        viewModel.updateAutoScrollConfig(AutoScrollConfig(enabled = true, mode = AutoScrollMode.LINE, speed = 0.7f))
        viewModel.updateAppLanguage(AppLanguage.KOREAN)
        advanceUntilIdle()

        assertEquals(style, repository.lastStyle)
        assertEquals(PageTurnMode.VERTICAL, repository.lastPageTurnMode)
        assertEquals(PageAnimation.CURL_PAGER, repository.lastPageAnimation)
        assertEquals(AutoScrollConfig(enabled = true, mode = AutoScrollMode.LINE, speed = 0.7f), repository.lastAutoScrollConfig)
        assertEquals(AppLanguage.KOREAN, repository.lastAppLanguage)
    }

    @Test
    fun updateAutoScrollConfigClampsSpeedBeforeSaving() = runTest(dispatcher) {
        val repository = FakeReaderSettingsRepository()
        val viewModel = ReaderSettingsViewModel(repository)

        viewModel.updateAutoScrollConfig(AutoScrollConfig(enabled = true, mode = AutoScrollMode.PAGE, speed = 5f))
        advanceUntilIdle()

        assertEquals(1f, repository.lastAutoScrollConfig?.speed)
    }
}

private class FakeReaderSettingsRepository : ReaderSettingsRepository {
    private val state = MutableStateFlow(ReaderSettings())
    override val settings: Flow<ReaderSettings> = state

    var lastStyle: ReaderStyle? = null
    var lastPageTurnMode: PageTurnMode? = null
    var lastPageAnimation: PageAnimation? = null
    var lastAutoScrollConfig: AutoScrollConfig? = null
    var lastAppLanguage: AppLanguage? = null

    override suspend fun updateStyle(style: ReaderStyle) {
        lastStyle = style
        state.value = state.value.copy(style = style)
    }

    override suspend fun updatePageTurnMode(pageTurnMode: PageTurnMode) {
        lastPageTurnMode = pageTurnMode
        state.value = state.value.copy(pageTurnMode = pageTurnMode)
    }

    override suspend fun updatePageAnimation(pageAnimation: PageAnimation) {
        lastPageAnimation = pageAnimation
        state.value = state.value.copy(pageAnimation = pageAnimation)
    }

    override suspend fun updateAutoScrollConfig(autoScrollConfig: AutoScrollConfig) {
        lastAutoScrollConfig = autoScrollConfig
        state.value = state.value.copy(autoScrollConfig = autoScrollConfig)
    }

    override suspend fun updateAppLanguage(appLanguage: AppLanguage) {
        lastAppLanguage = appLanguage
        state.value = state.value.copy(appLanguage = appLanguage)
    }
}
