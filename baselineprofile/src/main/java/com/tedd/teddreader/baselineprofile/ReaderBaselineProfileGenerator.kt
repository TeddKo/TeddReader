package com.tedd.teddreader.baselineprofile

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * 리더가 첫 페이지에 도달하기까지 접근하는 클래스와 메서드를 기록한다.
 *
 * 실제 기기에서 책을 탭한 뒤 첫 페이지가 나타날 때까지 ~2.2 s가 걸렸지만 데이터 경로는 ~230 ms에
 * 끝났고, 크기가 크게 다른 책 3권에서도 모두 ~2.2 s였다. 나머지 시간은 리더의 composable 트리를
 * 처음 로드하고 컴파일하는 데 쓰인다. 프로필은 리더가 기다리는 동안이 아니라 이 작업을 미리
 * 수행할 수 있게 한다.
 *
 * 이 여정에는 페이지 넘김이 포함되어야 한다. pager, 페이지 표면, 텍스트 레이아웃은 페이지가 실제로
 * 이동한 뒤에만 접근하며, 이 클래스들이 미리 컴파일할 가치가 있는 대상이다.
 */
class ReaderBaselineProfileGenerator {
    /**
     * 설치된 앱을 대상으로 `BaselineProfileRule`의 instrumentation을 실행하여 아래 각 여정이 접근하는
     * 클래스와 메서드를 기록한다.
     */
    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * 이 생성기의 주된 목적인 읽기 여정이다. 이 모듈에 포함된 샘플 책을 열고 몇 페이지를 넘겨,
     * 페이지가 실제로 이동한 뒤에만 접근하는 pager, 페이지 표면, 텍스트 레이아웃 클래스를 프로필에
     * 기록한다.
     *
     * 기기 라이브러리에 이미 있는 책을 찾지 않고, 이 모듈이 책을 보유했다가 매 반복마다 기기에 새로
     * 게시한다. 생성 작업은 끝날 때 앱을 제거하므로 이전 실행에서 가져온 책은 다음 실행 때 사라진다.
     * 책을 찾는 데 의존한 여정은 라이브러리 화면만 기록하고 리더는 전혀 기록하지 않았으며, 리더 클래스가
     * 없는 프로필도 경고 없이 생성되고 빌드되므로 이 실패는 조용히 발생했다.
     *
     * 전체 페이지 중 현재 페이지를 보여 주는 `" / "` 포함 텍스트인 페이지 카운터를 기다리는 방식은,
     * 이 값이 리더 자체의 "페이지가 준비됨" 신호이므로 유효하다. 이 값에 도달했다는 것은 가져오기,
     * 최초 측정, 첫 페이지 준비가 모두 끝났다는 뜻이며, 페이지 넘김 기록을 시작하기 전에 이 프로필에
     * 필요한 조건은 이것으로 충분하다.
     */
    @Test
    fun openLibraryAndReadAFewPages() = rule.collect(packageName = PackageName) {
        pressHome()

        startActivityAndWait(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(publishSampleBook(), EpubMimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setPackage(PackageName)
            },
        )

        device.wait(Until.hasObject(By.textContains(" / ")), 60_000)
        repeat(3) {
            device.click(device.displayWidth * 4 / 5, device.displayHeight / 2)
            device.waitForIdle()
        }
    }

    /**
     * 라이브러리까지 실행한 뒤 멈추는 시작 프로필용 여정이다. 실제 실행에서 읽는 클래스가 한곳에 모여
     * 한 번에 읽히도록 dex를 정렬한다. 위 읽기 여정을 시작 프로필로 표시하면 리더 전체가 포함되며,
     * 모든 것을 지정하는 시작 프로필은 아무것도 정렬하지 못한다.
     */
    @Test
    fun launchToTheLibrary() = rule.collect(
        packageName = PackageName,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
    }

    /**
     * 앱에 읽을 수 있는 URI를 전달할 수 있도록 이 모듈의 샘플 책을 기기의 Downloads에 넣는다.
     * 일반 파일 경로 대신 MediaStore를 사용한다. 앱에는 저장소 권한이 없으며, 열 수 없는 `file://`
     * URI는 책이 없는 경우와 같은 방식으로 실패한다.
     *
     * 반복할 때마다 사본을 쌓지 않고 자체 이전 사본을 교체한다.
     */
    private fun publishSampleBook(): Uri {
        val context = InstrumentationRegistry.getInstrumentation().context
        val resolver = context.contentResolver
        resolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(SampleBookName),
        )
        val uri = requireNotNull(
            resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, SampleBookName)
                    put(MediaStore.Downloads.MIME_TYPE, EpubMimeType)
                },
            ),
        ) { "Downloads would not accept $SampleBookName" }
        resolver.openOutputStream(uri).use { destination ->
            requireNotNull(destination) { "Downloads gave no stream for $SampleBookName" }
            context.assets.open(SampleBookAsset).use { source -> source.copyTo(destination) }
        }
        return uri
    }
}

/** 이 생성기가 instrumentation을 실행하고 프로필을 수집하는 설치 빌드의 application id이다. */
private const val PackageName = "com.tedd.teddreader"

/** 샘플 책을 Downloads에 게시하고 `ACTION_VIEW`로 열 때 모두 사용하는 MIME type이다. */
private const val EpubMimeType = "application/epub+zip"

/**
 * 이 모듈에 asset으로 포함되어 [ReaderBaselineProfileGenerator.publishSampleBook]이 읽는 샘플 EPUB의
 * 파일 이름이다.
 */
private const val SampleBookAsset = "sample.epub"

/**
 * 샘플 책을 기기의 Downloads에 게시할 때 사용하는 표시 이름이다.
 * [ReaderBaselineProfileGenerator.publishSampleBook]이 다시 게시하기 전에 같은 이름의 이전 사본을
 * 삭제하고 새 사본의 이름을 지정하는 데 모두 사용한다.
 */
private const val SampleBookName = "tedd-reader-baseline-profile-sample.epub"
