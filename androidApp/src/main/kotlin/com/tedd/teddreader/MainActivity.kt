package com.tedd.teddreader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.app.reader.TeddReaderApp
import com.tedd.teddreader.app.reader.importer.ExternalDocumentImportRequest
import com.tedd.teddreader.app.reader.importer.androidExternalDocumentImportRequest

/**
 * TeddReader의 Android 진입점이며 앱이 선언하는 유일한 `Activity`이다. 앱이 표시하는 모든 화면인
 * 홈, 라이브러리, 리더, 검색, 북마크, 문서 정보, 설정은 별도 Activity가 아니라 [TeddReaderApp]의
 * 자체 탐색 호스트를 통해 도달하는 Composable이다. 따라서 이 클래스는 해당 Compose 트리를
 * 호스팅하고, Android의 Intent 기반 문서 전달(다른 앱이 TeddReader에 파일을 넘길 때 사용하는
 * 매니페스트의 `VIEW`/`SEND` 필터 또는 공유 시트 대상)을 컴포지션이 이해하는
 * [ExternalDocumentImportRequest]로 연결하기 위해서만 존재한다.
 */
class MainActivity : ComponentActivity() {

    /**
     * 이 Activity를 가장 최근에 시작하거나 대상으로 다시 지정한 [Intent]가 전달한 문서 열기
     * 요청이다. 문서 없이 앱 런처에서 일반적으로 실행된 경우에는 null이다. 로컬 값이 아니라 Compose
     * 상태로 보관하므로, Activity가 이미 화면에 있는 동안 [onNewIntent]가 새 요청을 전달하면 요청을
     * 조용히 버리지 않고 새 문서로 콘텐츠를 다시 컴포지션한다.
     */
    private var externalImportRequest by mutableStateOf<ExternalDocumentImportRequest?>(null)

    /**
     * 최초로 컴포지션되는 프레임부터 인셋이 적용되어야 하므로 프레임워크 자체 `onCreate`가 실행되기
     * 전에 edge-to-edge 그리기를 활성화한다. 그런 다음 실행 Intent가 전달한 문서를 읽고 이를 초기값으로
     * 받은 [TeddReaderApp]을 Compose 콘텐츠로 설정한다.
     *
     * @param savedInstanceState 프레임워크가 복원한 인스턴스 상태. 프로세스 재시작 후에도 유지해야 하는
     *   이 앱의 모든 상태(읽기 위치, 탐색 백 스택)는 이 Activity가 아니라 Compose 트리 내부의
     *   `rememberSaveable`로 복구하므로 여기서는 사용하지 않는다.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        externalImportRequest = androidExternalDocumentImportRequest(intent, this)
        setContent { TeddReaderApp(initialExternalImportRequest = externalImportRequest) }
    }

    /**
     * 이미 실행 중인 이 Activity 인스턴스에 다시 전달된 문서 열기 Intent를 처리한다. 예를 들어 앱이
     * 이미 포그라운드에 있을 때 두 번째로 "Open with TeddReader"를 선택하면, 이를
     * [externalImportRequest]로 다시 파싱하여 컴포지션이 Activity를 처음 생성할 때 사용한 문서 대신
     * 새로 요청된 문서를 받게 한다.
     *
     * @param intent 다시 전달된 Intent. 이후 구성 변경에서도 원래 실행 Intent로 되돌아가지 않고 같은
     *   문서를 읽도록 `setIntent`로도 저장한다.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalImportRequest = androidExternalDocumentImportRequest(intent, this)
    }
}

/**
 * 외부 가져오기 요청 없이 [TeddReaderApp]을 표시하는 Android Studio 디자인 타임 미리보기이다.
 * 미리보기 시점에 존재하지 않는 문서의 리더 화면 대신 앱의 일반 실행 상태인 홈 화면을 렌더링한다.
 */
@Preview
@Composable
private fun AppAndroidPreview() {
    TeddReaderApp()
}
