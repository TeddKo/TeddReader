package com.tedd.teddreader.core.ui

import com.tedd.teddreader.core.common.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [AppLanguage.resourceLocaleTag]의 매핑을 검증한다. [LocalAppLocale.provides]와
 * [ProvideTeddLocalization] 둘 다 모든 [AppLanguage] 값에 대해 올바른 태그(또는 null)를 고르는 데
 * 이를 의존하기 때문이다 — 여기서 매핑이 틀리면 앱의 모든 화면에서 잘못된 리소스 로케일을 조용히
 * 강제하거나, 시스템 로케일로 되돌아가지 못하게 된다.
 */
class TeddLocalizationTest {
    /** [AppLanguage.SYSTEM]은 반드시 null로 매핑되어야 하며, 이는 "재정의가 아니라 기기 자체 로케일을
     * 사용한다"는 뜻이다. */
    @Test
    fun resourceLocaleTagMapsSystemToNull() {
        assertNull(AppLanguage.SYSTEM.resourceLocaleTag())
    }

    /** 명시적인 [AppLanguage] 값은 각각 자신의 리소스 로케일 태그로 매핑된다. */
    @Test
    fun resourceLocaleTagMapsExplicitLanguages() {
        assertEquals("en", AppLanguage.ENGLISH.resourceLocaleTag())
        assertEquals("ko", AppLanguage.KOREAN.resourceLocaleTag())
    }
}
