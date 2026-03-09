# VLC 플러그인 구현 가이드

Cubby 앱에서 ExoPlayer가 지원하지 않는 포맷(WMV, ASF, WMA 등)을 재생하기 위해 libVLC 기반의 별도 플러그인 APK를 구현한 전체 기술 문서.

---

## 목차

1. [아키텍처 개요](#1-아키텍처-개요)
2. [모듈 구성](#2-모듈-구성)
3. [호스트 앱 → 플러그인 호출 (Intent 라우팅)](#3-호스트-앱--플러그인-호출-intent-라우팅)
4. [SMB 스트리밍 처리](#4-smb-스트리밍-처리)
5. [VLC 플레이어 초기화](#5-vlc-플레이어-초기화)
6. [자막 처리](#6-자막-처리)
7. [제스처 컨트롤](#7-제스처-컨트롤)
8. [컨트롤러 UI](#8-컨트롤러-ui)
9. [배속 재생](#9-배속-재생)
10. [위치 기억 (Position Memory)](#10-위치-기억-position-memory)
11. [코덱 정보 표시](#11-코덱-정보-표시)
12. [설정 시스템](#12-설정-시스템)
13. [SMB 재연결 (Reconnect)](#13-smb-재연결-reconnect)
14. [리소스 파일](#14-리소스-파일)
15. [빌드 및 배포](#15-빌드-및-배포)
16. [문제 해결 (Troubleshooting)](#16-문제-해결-troubleshooting)

---

## 1. 아키텍처 개요

### 왜 별도 APK인가?

- **ExoPlayer의 한계**: Android의 ExoPlayer/MediaParser는 ASF/WMV 컨테이너를 지원하지 않음
- **libVLC 크기**: `libvlc-all:3.6.0`은 arm64-v8a + armeabi-v7a 네이티브 라이브러리 포함 시 약 80MB
- **별도 APK 결정**: 메인 앱(~42MB)에 VLC를 포함하면 120MB+ 되므로, 플러그인 APK(~85MB)로 분리하여 필요한 사용자만 설치하도록 구성

### 통신 방식

```
[Cubby 메인 앱]  ──Intent(ComponentName)──>  [VLC 플러그인 APK]
     │                                              │
     │  content://com.cubby.smb.stream/path          │
     │  ← FLAG_GRANT_READ_URI_PERMISSION →           │
     │                                              │
     ├── SmbStreamProvider (ContentProvider)         │
     │     └── StorageManager.openProxyFileDescriptor│
     │                                              │
     │                                    VlcPlayerActivity
     │                                    ├── ContentResolver.openFileDescriptor()
     │                                    ├── fd://FD_NUMBER (VLC Media)
     │                                    └── libVLC 재생
```

**핵심 원리**: 메인 앱의 ContentProvider가 SMB 파일을 스트리밍하고, VLC 플러그인은 `content://` URI를 받아 File Descriptor로 변환하여 `fd://` 스킴으로 VLC에 전달.

---

## 2. 모듈 구성

### 디렉토리 구조

```
cubby/
├── app/                          # 메인 앱 (com.cubby)
│   └── src/main/java/com/cubby/
│       ├── provider/
│       │   ├── SmbStreamProvider.kt    # SMB ContentProvider
│       │   └── SmbStreamManager.kt     # SMB 연결 관리 싱글톤
│       └── presentation/screens/
│           ├── explorer/ExplorerScreen.kt  # VLC 라우팅 (3곳)
│           ├── network/NetworkScreen.kt    # VLC 라우팅 (1곳)
│           └── sharing/SharingScreen.kt    # VLC 라우팅 (1곳)
│
├── vlcplugin/                    # VLC 플러그인 APK (com.cubby.vlcplugin)
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/cubby/vlcplugin/
│       │   └── VlcPlayerActivity.kt    # 플레이어 (~1250줄)
│       └── res/
│           ├── layout/activity_vlc_player.xml
│           └── drawable/               # 아이콘 리소스
│
└── settings.gradle.kts           # include(":vlcplugin")
```

### vlcplugin/build.gradle.kts

```kotlin
plugins {
    id("com.android.application")      // library가 아닌 application
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cubby.vlcplugin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cubby.vlcplugin"  // 별도 패키지
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            // 지원할 ABI 제한 (APK 사이즈 최적화)
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("org.videolan.android:libvlc-all:3.6.0")  // VLC 핵심
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.12.0")
}
```

**중요**: `app/build.gradle.kts`에는 vlcplugin 의존성이 없다. 두 모듈은 완전히 독립된 APK로 빌드된다.

### AndroidManifest.xml

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="false"
        android:label="Cubby VLC Plugin"
        android:supportsRtl="true">
        <activity
            android:name=".VlcPlayerActivity"
            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize"
            android:exported="true"
            android:theme="@style/Theme.AppCompat.NoActionBar" />
    </application>
</manifest>
```

- `exported="true"`: 다른 앱(메인 앱)에서 ComponentName으로 실행 가능
- `configChanges`: 화면 회전 시 Activity 재생성 방지
- `Theme.AppCompat.NoActionBar`: 전체화면 영상 플레이어용

---

## 3. 호스트 앱 → 플러그인 호출 (Intent 라우팅)

### VLC 포맷 판별

```kotlin
val vlcFormats = listOf("wmv", "asf", "wma")
val useVlc = settingsUiState.useVlcForAll || extension in vlcFormats
```

- 기본적으로 WMV/ASF/WMA 확장자만 VLC로 라우팅
- 설정의 "모든 영상 VLC로 재생" 토글(`useVlcForAll`)이 켜져 있으면 모든 영상 포맷을 VLC로 전송
- VLC 플러그인 설치 여부는 설정 화면에서 `PackageManager.getPackageInfo("com.cubby.vlcplugin", 0)` 으로 확인

### Intent 구성 (SMB 파일 예시)

```kotlin
val intent = Intent().apply {
    // ComponentName으로 외부 앱의 특정 Activity 지정
    component = ComponentName(
        "com.cubby.vlcplugin",              // 플러그인 패키지명
        "com.cubby.vlcplugin.VlcPlayerActivity"  // Activity 전체 경로
    )

    // URI를 Intent.data에 설정 (중요: String extra가 아님!)
    data = uri  // content://com.cubby.smb.stream/path/to/video.wmv

    // 부가 정보
    putExtra("video_title", fileName)
    putExtra("file_path", file.absolutePath)  // 로컬 파일인 경우에만

    // URI 읽기 권한 부여 (핵심!)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    // 자막 파일들의 URI 권한도 ClipData로 함께 부여
    if (uri.authority == "com.cubby.smb.stream" && extension.isNotEmpty()) {
        val subtitleExts = listOf("srt", "vtt", "ass", "ssa", "smi", "sami")
        val videoUriStr = uri.toString()
        val clips = subtitleExts.mapNotNull { subExt ->
            val subUriStr = videoUriStr.substringBeforeLast(".$extension") + ".$subExt"
            if (subUriStr != videoUriStr)
                ClipData.Item(Uri.parse(subUriStr))
            else null
        }
        if (clips.isNotEmpty()) {
            clipData = ClipData("subtitles", arrayOf("*/*"), clips.first())
            clips.drop(1).forEach { clipData!!.addItem(it) }
        }
    }
}
context.startActivity(intent)
```

### 왜 Intent.data를 사용하는가?

`FLAG_GRANT_READ_URI_PERMISSION`은 **Intent.data** (또는 ClipData)에 설정된 URI에만 적용된다. `putExtra("uri_string", uriStr)` 같은 String extra로 전달하면 URI 권한이 부여되지 않아 VLC 플러그인에서 `content://` URI를 열 수 없다.

### ClipData를 통한 자막 URI 권한 부여

하나의 Intent에는 `data`로 1개의 URI만 설정 가능하다. 자막 파일들의 URI에도 읽기 권한이 필요하므로, `ClipData`에 추가하여 권한을 함께 부여한다.

```kotlin
// 동영상 URI: content://com.cubby.smb.stream/folder/video.wmv
// 자막 URI:   content://com.cubby.smb.stream/folder/video.srt
//             content://com.cubby.smb.stream/folder/video.ass
//             ...
```

### 플러그인 미설치 시 Fallback

```kotlin
try {
    context.startActivity(intent)
} catch (e: ActivityNotFoundException) {
    // VLC 플러그인 미설치 → 내장 플레이어(ExoPlayer)로 재생
    val fallbackIntent = Intent(context, PlayerActivity::class.java).apply {
        putExtra(PlayerActivity.EXTRA_VIDEO_URI, uri.toString())
        putExtra(PlayerActivity.EXTRA_VIDEO_TITLE, fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(fallbackIntent)
}
```

### 호출 지점 (5곳)

| 파일 | 위치 | 용도 |
|------|------|------|
| `ExplorerScreen.kt` | ~384행 | SMB/OneDrive/Dropbox 스트리밍 URI 재생 |
| `ExplorerScreen.kt` | ~2668행 | NetworkScreen 팝업에서 SMB 파일 재생 |
| `ExplorerScreen.kt` | ~6122행 | 로컬 파일 열기 |
| `NetworkScreen.kt` | ~232행 | 네트워크(SMB) 파일 재생 |
| `SharingScreen.kt` | ~188행 | P2P(Cubby) 공유 파일 재생 |

### 소스별 URI 생성 및 VLC 전달 방식

모든 소스는 공통적으로 `ExplorerUiState.streamingUri`에 URI를 설정하고, `ExplorerScreen.kt`의 `LaunchedEffect(uiState.streamingUri)` 블록에서 VLC 라우팅이 수행된다. 소스별로 URI를 생성하는 방식이 다르다.

#### LOCAL (로컬 파일)

```kotlin
// ExplorerScreen.kt openFile() 함수 (~6104행)
val file = File(path)
val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
// 결과: content://com.cubby.provider/external_files/path/to/video.wmv

val intent = Intent().apply {
    component = ComponentName("com.cubby.vlcplugin", "com.cubby.vlcplugin.VlcPlayerActivity")
    data = uri
    putExtra("video_title", file.name)
    putExtra("file_path", file.absolutePath)  // ← 로컬 파일만 전달 (자막 탐색용)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
```

- **URI 스킴**: `content://` (FileProvider)
- **VLC에서의 처리**: `contentResolver.openFileDescriptor()` → `fd://` 변환
- **자막 탐색**: `file_path` extra로 전달된 실제 경로의 같은 디렉토리에서 `findSubtitlesForFile()` 사용
- **ClipData**: 자막 URI도 FileProvider content:// 로 생성하여 권한 부여

#### SMB (네트워크 파일 공유)

```kotlin
// ExplorerViewModel.kt (~4346행)
val uri = SmbStreamProvider.buildUri(item.path)
// 결과: content://com.cubby.smb.stream/share_folder/video.wmv

_uiState.value = _uiState.value.copy(
    streamingUri = uri,
    streamingMimeType = mimeType
)
```

- **URI 스킴**: `content://` (SmbStreamProvider)
- **VLC에서의 처리**: `contentResolver.openFileDescriptor()` → SmbStreamProvider가 SMB에서 읽기 → ProxyFileDescriptor 반환 → `fd://` 변환
- **자막 탐색**: `findSubtitlesForContent()` — URI 확장자 치환으로 content:// URI를 만들어 SmbStreamProvider에 요청
- **ClipData**: 자막 확장자 치환된 content:// URI들을 추가하여 읽기 권한 부여

#### OneDrive (마이크로소프트 클라우드)

```kotlin
// ExplorerViewModel.kt (~5810행)
fun openOneDriveFile(node: TreeNode) {
    viewModelScope.launch {
        val itemId = node.oneDriveItemId ?: return@launch
        val downloadUrl = OneDriveManager.getDownloadUrl(itemId)
        // 결과: https://xxxxx.sharepoint.com/...&download=1 (임시 다운로드 URL)

        _uiState.update {
            it.copy(
                streamingUri = Uri.parse(downloadUrl),
                streamingMimeType = getMimeTypeFromExtension(...)
            )
        }
    }
}
```

- **URI 스킴**: `https://` (OneDrive 임시 다운로드 URL)
- **VLC에서의 처리**: VLC가 HTTPS URL을 **직접** 스트리밍 재생 (fd:// 변환 불필요)
- **자막 탐색**: content:// 스킴이 아니고 file:// 도 아니므로 자막 탐색 대상 아님
- **참고**: `OneDriveManager.getDownloadUrl()`은 Graph API를 통해 `@microsoft.graph.downloadUrl` 필드를 가져옴. 이 URL은 사전 인증된 임시 URL이므로 별도 인증 없이 접근 가능

#### Dropbox

```kotlin
// ExplorerViewModel.kt (~5994행)
fun openDropboxFile(node: TreeNode) {
    viewModelScope.launch {
        val dropboxPath = node.dropboxPath ?: return@launch
        val temporaryLink = DropboxManager.getTemporaryLink(dropboxPath)
        // 결과: https://dl.dropboxusercontent.com/... (4시간 유효 임시 링크)

        _uiState.update {
            it.copy(
                streamingUri = Uri.parse(temporaryLink),
                streamingMimeType = getMimeTypeFromExtension(...)
            )
        }
    }
}
```

- **URI 스킴**: `https://` (Dropbox 임시 다운로드 링크)
- **VLC에서의 처리**: VLC가 HTTPS URL을 **직접** 스트리밍 재생
- **자막 탐색**: 자막 탐색 대상 아님
- **참고**: `DropboxManager.getTemporaryLink()`는 Dropbox API v2 `/files/get_temporary_link`를 호출하여 4시간 유효한 다운로드 링크를 생성

#### Cubby (P2P 공유)

```kotlin
// ExplorerViewModel.kt (~6171행)
fun openCubbyFile(node: TreeNode) {
    val client = CubbyClient(host.url)
    val fileUrl = client.getFileUrl(remotePath)
    // 결과: http://192.168.x.x:8080/files/path/to/video.wmv (LAN 내 HTTP URL)

    _uiState.update {
        it.copy(
            streamingUri = Uri.parse(fileUrl),
            streamingMimeType = getMimeTypeFromExtension(...)
        )
    }
}
```

- **URI 스킴**: `http://` (로컬 네트워크 NanoHTTPD 서버)
- **VLC에서의 처리**: VLC가 HTTP URL을 **직접** 스트리밍 재생
- **자막 탐색**: 자막 탐색 대상 아님

#### 소스별 비교 요약

| 소스 | URI 스킴 | VLC 재생 방식 | fd:// 변환 | 자막 탐색 | 자막 권한 부여 |
|------|----------|--------------|------------|----------|---------------|
| **LOCAL** | `content://` (FileProvider) | openFileDescriptor → fd:// | O | `findSubtitlesForFile()` (파일 경로) | ClipData |
| **SMB** | `content://` (SmbStreamProvider) | openFileDescriptor → fd:// | O | `findSubtitlesForContent()` (URI 치환) | ClipData |
| **OneDrive** | `https://` (임시 URL) | VLC 직접 스트리밍 | X | 불가 | 불필요 |
| **Dropbox** | `https://` (임시 링크) | VLC 직접 스트리밍 | X | 불가 | 불필요 |
| **Cubby** | `http://` (LAN HTTP) | VLC 직접 스트리밍 | X | 불가 | 불필요 |

#### VlcPlayerActivity에서의 분기 처리

```kotlin
// VlcPlayerActivity.kt initializePlayer() (~511행)
val media = if (videoUri.scheme == "content") {
    // content:// → ContentResolver로 fd 획득 → fd:// 스킴
    pfd = contentResolver.openFileDescriptor(videoUri, "r")
    val fdUri = Uri.parse("fd://${pfd!!.fd}")
    Media(libVlc!!, fdUri)
} else {
    // https://, http://, file:// 등 → VLC가 직접 처리
    Media(libVlc!!, videoUri)
}
```

`content://` 스킴일 때만 fd:// 변환이 필요하고, `https://`/`http://`/`file://`은 VLC의 내장 네트워크/파일 모듈이 직접 처리한다.

---

## 4. SMB 스트리밍 처리

SMB 파일을 VLC에서 재생하는 것은 이 프로젝트에서 가장 복잡한 부분이다. 3개 계층으로 구성된다.

### 4.1. SmbStreamManager (연결 관리 싱글톤)

파일: `app/.../provider/SmbStreamManager.kt`

```kotlin
object SmbStreamManager {
    var diskShare: DiskShare? = null
    private var smbClient: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null

    // 재연결용 정보 저장
    private var lastHost: String? = null
    private var lastPort: Int = 445
    private var lastUsername: String? = null
    private var lastPassword: String? = null
    private var lastDomain: String? = null
    private var lastShareName: String? = null
    var autoReconnectEnabled: Boolean = true
}
```

**역할**:
- SMB 세션을 앱 전역에서 공유하는 싱글톤
- `setConnectionInfo()`: 연결 시 호스트/인증 정보 저장 (재연결용)
- `getOrReconnect()`: 연결 상태 확인 → 끊겼으면 자동 재연결 → DiskShare 반환
- `reconnect()`: 저장된 정보로 새 연결 생성 (SMBClient → Connection → Session → DiskShare)
- `createOptimizedClient()`: 스트리밍용 긴 타임아웃 설정 (5분)

**SMB 클라이언트 설정**:
```kotlin
fun createOptimizedClient(): SMBClient {
    val config = SmbConfig.builder()
        .withTimeout(300, TimeUnit.SECONDS)     // 읽기/쓰기 타임아웃 5분
        .withSoTimeout(300, TimeUnit.SECONDS)   // 소켓 타임아웃 5분
        .build()
    return SMBClient(config)
}
```

### 4.2. SmbStreamProvider (ContentProvider)

파일: `app/.../provider/SmbStreamProvider.kt`

Android의 ContentProvider를 이용하여 SMB 파일을 `content://` URI로 노출한다.

**URI 형식**: `content://com.cubby.smb.stream/<SMB경로>`

```kotlin
class SmbStreamProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.cubby.smb.stream"

        fun buildUri(smbPath: String): Uri {
            return Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .path(smbPath)
                .build()
        }
    }
}
```

**핵심 메서드 - openFile()**:

```kotlin
override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
    // ⚠️ StrictMode 임시 해제 (아래 '문제 해결' 참고)
    val oldPolicy = StrictMode.getThreadPolicy()
    StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder().permitAll().build()
    )

    val smbPath = uri.path?.removePrefix("/") ?: return null
    val diskShare = SmbStreamManager.getOrReconnect() ?: return null

    return try {
        // 1. 파일 사이즈 조회
        val fileInfo = diskShare.getFileInformation(smbPath)
        val fileSize = fileInfo.standardInformation.endOfFile

        // 2. SMB 파일 핸들 열기
        val remoteFile = diskShare.openFile(
            smbPath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )

        // 3. ProxyFileDescriptor로 변환
        val callback = SmbProxyCallback(remoteFile, fileSize, smbPath)
        val storageManager = context?.getSystemService(StorageManager::class.java)
        storageManager!!.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            callback,
            handler!!
        )
    } finally {
        StrictMode.setThreadPolicy(oldPolicy)  // 원래 정책 복원
    }
}
```

### 4.3. SmbProxyCallback (파일 읽기 콜백)

`StorageManager.openProxyFileDescriptor()`는 가상의 파일 디스크립터를 생성하고, 실제 읽기 요청이 올 때마다 `ProxyFileDescriptorCallback.onRead()`를 호출한다.

```kotlin
private inner class SmbProxyCallback(
    private var remoteFile: File,      // SMB 파일 핸들
    private val fileSize: Long,
    private val smbPath: String
) : ProxyFileDescriptorCallback() {

    private val MAX_RETRIES = 2

    override fun onGetSize(): Long = fileSize

    @Synchronized
    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        for (attempt in 0..MAX_RETRIES) {
            try {
                val buffer = ByteBuffer.wrap(data, 0, size)
                val bytesRead = remoteFile.read(buffer, offset)
                return if (bytesRead < 0) 0 else bytesRead.toInt()
            } catch (e: Exception) {
                if (attempt < MAX_RETRIES) {
                    // 파일 핸들 닫기
                    remoteFile.close()
                    // SMB 재연결
                    SmbStreamManager.reconnect()
                    // 파일 다시 열기
                    val share = SmbStreamManager.getOrReconnect()
                    if (share != null) {
                        remoteFile = share.openFile(smbPath, ...)
                    }
                }
            }
        }
        throw ErrnoException("onRead", OsConstants.EIO)
    }

    override fun onRelease() {
        remoteFile.close()
    }
}
```

**데이터 흐름 요약**:
```
VLC 플러그인 앱:
  contentResolver.openFileDescriptor(content://com.cubby.smb.stream/path, "r")
      ↓ (Binder IPC)
Cubby 메인 앱:
  SmbStreamProvider.openFile()
    → SmbStreamManager.getOrReconnect() → DiskShare
    → diskShare.openFile(smbPath) → SMB File 핸들
    → StorageManager.openProxyFileDescriptor(callback) → ParcelFileDescriptor
      ↓ (Binder IPC 반환)
VLC 플러그인 앱:
  pfd.fd → "fd://${pfd.fd}" → Media(libVlc, fdUri) → 재생
```

### 4.4. StrictMode 문제와 해결

**문제**: VLC 플러그인(별도 프로세스)에서 `contentResolver.openFileDescriptor()`를 호출하면, 이 요청은 Binder IPC를 통해 Cubby 메인 앱의 `SmbStreamProvider.openFile()`에 전달된다. 이때 **호출측의 StrictMode 정책이 Binder를 통해 전파**된다.

Android의 기본 StrictMode는 메인 스레드에서 네트워크 작업을 금지하므로, ContentProvider의 `openFile()`에서 SMB 네트워크 작업을 수행하면 `NetworkOnMainThreadException`이 발생한다.

**해결**:
```kotlin
override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
    val oldPolicy = StrictMode.getThreadPolicy()
    StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder().permitAll().build()
    )
    try {
        // SMB 네트워크 작업 수행
    } finally {
        StrictMode.setThreadPolicy(oldPolicy)
    }
}
```

`SmbStreamProvider.openFile()`의 시작에서 StrictMode를 임시 해제하고, 작업 완료 후 원래 정책으로 복원한다. 조기 반환 경로에서도 반드시 복원해야 한다.

---

## 5. VLC 플레이어 초기화

파일: `vlcplugin/.../VlcPlayerActivity.kt`

### 5.1. URI 수신

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // ...
    val videoUri = intent.data              // Intent.data에서 URI 수신
    val videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: "Video"
    originalFilePath = intent.getStringExtra("file_path")  // 로컬 파일 경로 (있으면)
    // ...
    initializePlayer(videoUri)
}
```

### 5.2. LibVLC 인스턴스 생성

```kotlin
private fun initializePlayer(videoUri: Uri) {
    val args = arrayListOf(
        "--aout=opensles",         // Android OpenSLES 오디오 출력
        "--audio-time-stretch",    // 배속 시 피치 보정
        "-vvv"                     // 디버그 로그 (개발 시, 출시 시 제거)
    )

    // 디코더 모드에 따른 옵션
    when (decoderMode) {
        DECODER_HW -> args.add("--avcodec-hw=any")    // HW 디코더 강제
        DECODER_SW -> args.add("--avcodec-hw=none")   // SW 디코더 강제
        // DECODER_AUTO: 옵션 없음 (VLC 자동 판단)
    }

    libVlc = LibVLC(this, args)
    mediaPlayer = MediaPlayer(libVlc!!)
}
```

### 5.3. content:// URI → fd:// 변환

VLC는 `content://` URI를 직접 열 수 없다. Android ContentResolver를 통해 File Descriptor를 얻어 `fd://` 스킴으로 변환한다.

```kotlin
val media = if (videoUri.scheme == "content") {
    // 재시도 로직 (최대 3회, 2초 간격)
    var openAttempt = 0
    while (openAttempt < 3) {
        try {
            pfd = contentResolver.openFileDescriptor(videoUri, "r")
            if (pfd != null) break
        } catch (e: Exception) {
            Log.w(TAG, "Attempt ${openAttempt + 1} failed: ${e.message}")
        }
        openAttempt++
        if (openAttempt < 3) {
            Toast.makeText(this, "연결 시도 중... ($openAttempt/3)", Toast.LENGTH_SHORT).show()
            Thread.sleep(2000)  // 2초 대기
        }
    }

    if (pfd == null) {
        Toast.makeText(this, "파일을 열 수 없습니다", Toast.LENGTH_LONG).show()
        finish()
        return
    }

    // fd://FD_NUMBER 형식으로 VLC에 전달
    val fdUri = Uri.parse("fd://${pfd!!.fd}")
    Media(libVlc!!, fdUri)
} else {
    // file:// 또는 http:// 등은 VLC가 직접 처리
    Media(libVlc!!, videoUri)
}
```

**fd:// 스킴**: VLC의 고유 스킴으로, 이미 열려있는 File Descriptor 번호를 직접 전달한다. `/proc/self/fd/N` 경로보다 안정적이다.

### 5.4. Event Listener

```kotlin
mediaPlayer!!.setEventListener { event ->
    when (event.type) {
        MediaPlayer.Event.Playing -> {
            // 로딩 인디케이터 숨기기, 재생 버튼 → 일시정지 아이콘
            // 프로그레스 업데이트 시작, 코덱 정보 표시, 사운드 상태 적용
        }
        MediaPlayer.Event.Paused -> {
            // 일시정지 아이콘 → 재생 아이콘
        }
        MediaPlayer.Event.Buffering -> {
            // 버퍼링 100% 미만이면 로딩 표시
        }
        MediaPlayer.Event.LengthChanged -> {
            // 전체 재생 시간 업데이트
        }
        MediaPlayer.Event.EncounteredError -> {
            // content:// URI이면 재연결 시도 (최대 3회)
            // 아니면 에러 토스트 후 종료
        }
        MediaPlayer.Event.EndReached -> {
            // 재생 완료 → 저장된 위치 삭제 → 종료
        }
    }
}
```

### 5.5. 영상 표시

```kotlin
mediaPlayer!!.attachViews(videoLayout, null, true, false)
// 파라미터: VLCVideoLayout, subtitlesSurface, enableSubtitle, enableHardwareAcceleration
```

### 5.6. 위치 복원

```kotlin
val savedPosition = prefs.getLong("pos_${currentVideoUri}", 0L)
if (savedPosition > 0) {
    mediaPlayer?.play()
    handler.postDelayed({
        mediaPlayer?.time = savedPosition  // 500ms 후 위치 이동
    }, 500)
} else {
    mediaPlayer?.play()
}
```

500ms 지연은 미디어가 준비되기 전에 seek하면 무시되는 것을 방지한다.

---

## 6. 자막 처리

### 6.1. 지원 형식

```kotlin
private val subtitleExtensions = listOf("srt", "vtt", "ass", "ssa", "smi", "sami")
```

### 6.2. 자막 탐색 전략

영상 URI의 종류에 따라 다른 탐색 방식을 사용한다:

```kotlin
private fun findSubtitles(videoUri: Uri): List<SubtitleInfo> {
    when (videoUri.scheme) {
        "content" -> findSubtitlesForContent(videoUri, subtitles)
        "file", null -> findSubtitlesForFile(videoUri, subtitles)
    }
}
```

### 6.3. 로컬 파일 자막 탐색

```kotlin
private fun findSubtitlesForFile(videoUri: Uri, subtitles: MutableList<SubtitleInfo>) {
    val videoFile = File(videoUri.path!!)
    val parentDir = videoFile.parentFile ?: return
    val videoName = videoFile.nameWithoutExtension

    for (ext in subtitleExtensions) {
        // 1. 정확한 이름 매칭: video.srt, video.ass, ...
        val subFile = File(parentDir, "$videoName.$ext")
        if (subFile.exists()) {
            subtitles.add(SubtitleInfo(ensureUtf8Subtitle(subFile), subFile.name))
            continue
        }

        // 2. 대소문자 무시 검색
        parentDir.listFiles()?.find { f ->
            f.nameWithoutExtension.equals(videoName, true) &&
            f.extension.lowercase() == ext &&
            subtitles.none { it.name == f.name }  // 중복 방지
        }?.let { f ->
            subtitles.add(SubtitleInfo(ensureUtf8Subtitle(f), f.name))
        }
    }
}
```

### 6.4. Content URI 자막 탐색 (SMB)

SMB 파일은 `content://` URI를 통해 접근하므로 파일시스템 API를 쓸 수 없다. **URI 문자열의 확장자만 치환**하여 자막을 탐색한다.

```kotlin
private fun findSubtitlesForContent(videoUri: Uri, subtitles: MutableList<SubtitleInfo>) {
    val videoUriStr = videoUri.toString()
    // content://com.cubby.smb.stream/folder/video.wmv
    val videoExt = lastSegment.substringAfterLast(".", "")

    for (ext in subtitleExtensions) {
        try {
            // URI 확장자 치환: video.wmv → video.srt
            val subUriStr = videoUriStr.substringBeforeLast(".$videoExt") + ".$ext"
            val subUri = Uri.parse(subUriStr)

            // ContentResolver로 자막 데이터 읽기 시도
            val content = contentResolver.openInputStream(subUri)?.use { input ->
                val buffer = ByteArrayOutputStream()
                val data = ByteArray(16384)
                var totalRead = 0
                var nRead: Int
                while (input.read(data).also { nRead = it } != -1) {
                    totalRead += nRead
                    if (totalRead > 5 * 1024 * 1024) break  // 5MB 제한
                    buffer.write(data, 0, nRead)
                }
                buffer.toByteArray()
            }

            if (content != null && content.isNotEmpty()) {
                // 인코딩 변환 후 캐시에 UTF-8로 저장
                val cacheDir = File(cacheDir, "vlc_subtitles")
                cacheDir.mkdirs()
                val cacheFile = File(cacheDir, "$videoNameWithoutExt.$ext")
                val text = decodeSubtitleBytes(content)
                cacheFile.writeText(text, Charsets.UTF_8)

                subtitles.add(SubtitleInfo(
                    uri = Uri.fromFile(cacheFile),  // file:// URI로 VLC에 전달
                    name = "$videoNameWithoutExt.$ext"
                ))
            }
        } catch (e: Exception) {
            // 해당 확장자 자막 없음 → 조용히 무시
        }
    }
}
```

**원리**: `content://com.cubby.smb.stream/folder/video.wmv`에서 `.wmv`를 `.srt`로 바꿔 `content://com.cubby.smb.stream/folder/video.srt`를 만든다. SmbStreamProvider가 이 URI를 받으면 SMB에서 `folder/video.srt`를 열려고 시도한다. 파일이 없으면 예외가 발생하고, 있으면 데이터를 읽어온다.

### 6.5. 자막 인코딩 변환

한국어 자막 파일은 EUC-KR/CP949 인코딩이 매우 많다. VLC는 UTF-8을 기본으로 사용하므로 변환이 필요하다.

```kotlin
private fun decodeSubtitleBytes(bytes: ByteArray): String {
    // 1단계: UTF-8 BOM 확인 (EF BB BF)
    if (bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte()) {
        return String(bytes.drop(3).toByteArray(), Charsets.UTF_8)
    }

    // 2단계: UTF-8로 디코딩 시도
    try {
        val text = String(bytes, Charsets.UTF_8)
        // 대체 문자(U+FFFD)가 없으면 유효한 UTF-8
        if (!text.contains("\uFFFD")) return text
    } catch (_: Exception) {}

    // 3단계: EUC-KR / MS949 / CP949 순서로 시도
    for (enc in listOf("EUC-KR", "MS949", "CP949")) {
        try {
            val text = String(bytes, Charset.forName(enc))
            // 한글 포함 + 대체 문자 없음 → 성공
            if (text.contains(Regex("[가-힣]")) && !text.contains("\uFFFD")) {
                return text
            }
        } catch (_: Exception) {}
    }

    // 4단계: 최후의 수단 - EUC-KR 강제 적용
    return try {
        String(bytes, Charset.forName("EUC-KR"))
    } catch (_: Exception) {
        String(bytes, Charsets.UTF_8)
    }
}
```

**판별 순서가 중요한 이유**:
1. UTF-8 BOM이 있으면 확실한 UTF-8
2. UTF-8 디코딩 시 `\uFFFD`(대체 문자)가 없으면 유효한 UTF-8
3. EUC-KR 시도 시 한글(`[가-힣]`)이 포함되어야 EUC-KR로 판정 (영문만 있는 파일의 오판 방지)

### 6.6. 로컬 자막 파일의 인코딩 변환

로컬 파일도 EUC-KR일 수 있으므로, 읽기 후 UTF-8로 변환하여 캐시에 저장한다.

```kotlin
private fun ensureUtf8Subtitle(file: File): Uri {
    val bytes = file.readBytes()
    val text = decodeSubtitleBytes(bytes)         // 인코딩 감지 + 변환
    val dir = File(cacheDir, "vlc_subtitles")
    dir.mkdirs()
    val cached = File(dir, file.name)
    cached.writeText(text, Charsets.UTF_8)        // UTF-8로 저장
    return Uri.fromFile(cached)                   // 캐시 파일 URI 반환
}
```

### 6.7. 자막 적용

```kotlin
private fun addSubtitleToPlayer(subtitle: SubtitleInfo) {
    val result = mediaPlayer?.addSlave(
        SUBTITLE_SLAVE_TYPE,    // 0 = subtitle (1 = audio)
        subtitle.uri,            // file:// URI (캐시된 UTF-8 파일)
        true                     // select: 즉시 활성화
    )
}
```

`MediaPlayer.addSlave()`는 VLC의 자막 슬레이브 기능으로, 외부 자막 파일을 미디어에 첨부한다. `SUBTITLE_SLAVE_TYPE = 0`은 자막 트랙을 의미.

### 6.8. 자막 선택 UI

```kotlin
private fun showSubtitleDialog() {
    val items = mutableListOf("자막 끄기")
    items.addAll(availableSubtitles.map { it.name })

    AlertDialog.Builder(this)
        .setTitle("자막 선택")
        .setItems(items.toTypedArray()) { _, which ->
            when (which) {
                0 -> {
                    isSubtitleEnabled = false
                    mediaPlayer?.spuTrack = -1     // 자막 트랙 끄기
                }
                else -> {
                    isSubtitleEnabled = true
                    addSubtitleToPlayer(availableSubtitles[which - 1])
                }
            }
        }
        .show()
}
```

---

## 7. 제스처 컨트롤

`GestureDetectorCompat`을 사용하여 터치 제스처를 처리한다.

### 7.1. 제스처 모드 판별

```kotlin
enum class GestureMode { BRIGHTNESS, VOLUME, SEEK }
```

스크롤 시작 시 이동 방향으로 모드를 결정하고, 한번 결정되면 손가락을 뗄 때까지 유지된다.

```kotlin
if (currentGestureMode == null) {
    val totalMovement = abs(deltaX) + abs(deltaY)
    if (totalMovement > 10) {  // 10px 이상 이동해야 제스처 시작
        currentGestureMode = when {
            // 세로 이동이 우세할 때
            abs(deltaY) > abs(deltaX) * 0.3f -> {
                if (e1.x < screenWidth * 0.5f)
                    GestureMode.BRIGHTNESS    // 화면 왼쪽 = 밝기
                else if (isSoundEnabled)
                    GestureMode.VOLUME        // 화면 오른쪽 = 볼륨
                else null
            }
            // 가로 이동이 우세할 때
            abs(deltaX) > abs(deltaY) * 0.3f -> GestureMode.SEEK
            else -> null
        }
    }
}
```

### 7.2. 밝기 조절 (화면 왼쪽 세로 스와이프)

```kotlin
private fun adjustBrightness(delta: Float) {
    val layoutParams = window.attributes
    var brightness = layoutParams.screenBrightness
    if (brightness < 0) brightness = getCurrentBrightness()

    brightness += delta * 0.1f                  // 감도 계수
    brightness = brightness.coerceIn(0.01f, 1.0f)

    layoutParams.screenBrightness = brightness
    window.attributes = layoutParams

    prefs.edit().putFloat("saved_brightness", brightness).apply()  // 다음 재생에도 유지
}
```

`window.attributes.screenBrightness`는 현재 Activity의 밝기만 조절하며 시스템 밝기에는 영향 없음.

### 7.3. 볼륨 조절 (화면 오른쪽 세로 스와이프)

```kotlin
private fun adjustVolume(delta: Float) {
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    if (volumeFloat < 0f) {
        volumeFloat = initialVolume.toFloat() / maxVolume  // 첫 터치 시 현재값으로 초기화
    }

    volumeFloat += delta * 0.1f
    volumeFloat = volumeFloat.coerceIn(0f, 1f)

    val newVolume = (volumeFloat * maxVolume + 0.5f).toInt().coerceIn(0, maxVolume)
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
}
```

`volumeFloat`를 float로 유지하면 미세 조절이 가능하다. `AudioManager`의 볼륨은 정수 단계이므로 반올림.

### 7.4. 탐색 (가로 스와이프)

```kotlin
private fun seekGesture(deltaX: Float) {
    if (!isGestureSeeking) {
        isGestureSeeking = true
        seekStartTime = mp.time     // 제스처 시작 시점의 재생 위치 저장
        lastSeekTime = seekStartTime
    }

    val sensitivityMultiplier = swipeSensitivity / 10.0f  // 설정된 민감도
    val seekAmount = (deltaX / videoLayout.width) * duration * 0.015f * sensitivityMultiplier
    var newTime = seekStartTime + seekAmount.toLong()
    newTime = newTime.coerceIn(0, duration)

    // 최소 50ms 이상 차이날 때만 실제 seek (과도한 seek 방지)
    if (abs(newTime - lastSeekTime) >= 50) {
        mp.time = newTime
        lastSeekTime = newTime
    }

    showSeekPreview(newTime, duration)
}
```

### 7.5. 기타 터치 이벤트

| 제스처 | 동작 |
|--------|------|
| **싱글 탭** | 컨트롤러 토글 |
| **더블 탭 (왼쪽 반)** | 뒤로 스킵 (설정된 초) |
| **더블 탭 (오른쪽 반)** | 앞으로 스킵 |
| **더블 탭 (하단 1/5)** | 종료 |
| **아래로 빠른 스와이프 (하단)** | 종료 (velocityY > 2000) |
| **롱프레스** | 배속 재생 (위치에 따라 1.5x~4x) |

### 7.6. ACTION_UP 처리

손가락을 뗄 때 모든 제스처 상태를 초기화한다:

```kotlin
MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
    isGestureSeeking = false           // seek 종료
    seekPreviewText?.visibility = GONE
    brightnessIndicator?.visibility = GONE
    volumeIndicator?.visibility = GONE
    if (isSpeedActive) {
        mediaPlayer?.rate = originalSpeed  // 원래 속도 복원
        isSpeedActive = false
        speedIndicator?.visibility = GONE
    }
    currentGestureMode = null          // 모드 초기화
}
```

---

## 8. 컨트롤러 UI

### 8.1. 레이아웃 구조

```
FrameLayout (rootLayout, 검은 배경)
├── VLCVideoLayout (영상)
├── ProgressBar (로딩)
├── FrameLayout (controllerOverlay, 탭으로 토글)
│   ├── LinearLayout (topBar, 상단 그라데이션)
│   │   ├── ImageButton (뒤로)
│   │   ├── TextView (제목)
│   │   ├── TextView (코덱 정보)
│   │   ├── ImageButton (자막)
│   │   ├── ImageButton (소리)
│   │   ├── ImageButton (회전)
│   │   └── ImageButton (설정)
│   ├── ImageButton (중앙 재생/일시정지, 80dp)
│   └── LinearLayout (bottomBar, 하단 그라데이션)
│       ├── TextView (현재 시간)
│       ├── SeekBar (재생 위치)
│       └── TextView (전체 시간)
├── [프로그래매틱] FrameLayout (밝기 인디케이터, 오른쪽)
├── [프로그래매틱] FrameLayout (볼륨 인디케이터, 왼쪽)
└── [프로그래매틱] TextView (seek 미리보기, 중앙)
```

### 8.2. 자동 숨김

```kotlin
private const val CONTROLLER_TIMEOUT = 3000L  // 3초 후 자동 숨김

private fun showController() {
    controllerOverlay.visibility = View.VISIBLE
    isControllerVisible = true
    updateProgress()
    scheduleHideController()
}

private fun scheduleHideController() {
    handler.removeCallbacks(hideControllerRunnable)
    handler.postDelayed(hideControllerRunnable, CONTROLLER_TIMEOUT)
}
```

### 8.3. 프로그레스 업데이트

```kotlin
private const val PROGRESS_INTERVAL = 500L  // 0.5초 간격

private fun updateProgress() {
    val time = mp.time
    val length = mp.length

    if (length > 0 && !isUserSeeking) {
        tvPosition.text = formatTime(time)
        seekBar.progress = ((time * 1000) / length).toInt()
    }
}
```

SeekBar의 max는 1000으로, 0~1000 범위의 비율로 진행 상태를 표시한다.

### 8.4. 전체화면 설정

```kotlin
private fun setupFullscreen() {
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)  // 화면 꺼짐 방지
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
```

---

## 9. 배속 재생

롱프레스로 활성화되며, 화면 세로 위치에 따라 속도가 결정된다.

```kotlin
override fun onLongPress(e: MotionEvent) {
    val quarterHeight = screenHeight / 4f
    val speed = when {
        e.y < quarterHeight -> 1.5f        // 상단 1/4
        e.y < quarterHeight * 2 -> 2.0f    // 상단 2/4
        e.y < quarterHeight * 3 -> 3.0f    // 하단 2/4
        else -> 4.0f                        // 하단 1/4
    }

    if (!isSpeedActive) {
        originalSpeed = mp.rate      // 현재 속도 저장
        isSpeedActive = true
    }
    mp.rate = speed
    showSpeedIndicator(speed)
}
```

손가락을 떼면 원래 속도로 복원된다 (ACTION_UP 처리).

---

## 10. 위치 기억 (Position Memory)

### 저장

```kotlin
private fun saveCurrentPosition() {
    val uri = currentVideoUri ?: return
    val time = mediaPlayer?.time ?: return
    if (time > 0) {
        prefs.edit().putLong("pos_$uri", time).apply()
    }
}
```

호출 시점:
- `onPause()`: 백그라운드 전환 시
- `finish()` 직전: 제스처로 종료 시
- 아래로 스와이프 종료 시

### 복원

```kotlin
val savedPosition = prefs.getLong("pos_${currentVideoUri}", 0L)
if (savedPosition > 0) {
    mediaPlayer?.play()
    handler.postDelayed({
        mediaPlayer?.time = savedPosition
    }, 500)  // 500ms 후 seek
}
```

### 완료 시 삭제

```kotlin
MediaPlayer.Event.EndReached -> {
    prefs.edit().remove("pos_$uri").apply()  // 다 본 영상의 위치 삭제
    finish()
}
```

### 키 형식

`SharedPreferences`의 키는 `"pos_" + URI문자열`이다. 같은 파일이라도 경로가 다르면 별도로 저장된다.

---

## 11. 코덱 정보 표시

재생 시작 1.5초 후 미디어 트랙 정보를 조회하여 상단 바에 표시한다.

```kotlin
private fun showCodecInfo() {
    handler.postDelayed({
        val media = mediaPlayer?.media ?: return@postDelayed
        try {
            for (i in 0 until media.trackCount) {
                val track = media.getTrack(i)
                if (track is IMedia.VideoTrack) {
                    val codecStr = codecToName(track.codec)   // "WMV3", "H.264" 등
                    val resLabel = getResolutionLabel(w, h)    // "UHD", "FHD", "HD", "SD"
                    displayCodecInfo("$resLabel  ${w}x${h}  $codecStr")
                    break
                }
            }
        } finally {
            media.release()
        }
    }, 1500)
}
```

### 코덱 이름 매핑

```kotlin
private fun codecToName(codec: String?): String {
    return when (raw.lowercase()) {
        "h264", "avc1", "avc3" -> "H.264"
        "hevc", "hev1", "h265", "hvc1" -> "H.265"
        "wmv3" -> "WMV3"
        "wvc1" -> "VC-1"
        "vp90", "vp09", "vp9" -> "VP9"
        "av01", "av1" -> "AV1"
        // ... 기타
        else -> raw.uppercase()
    }
}
```

### 해상도 레이블

```kotlin
private fun getResolutionLabel(width: Int, height: Int): String {
    val pixels = maxOf(width, height)
    return when {
        pixels >= 3840 -> "UHD"   // 4K
        pixels >= 1920 -> "FHD"   // 1080p
        pixels >= 1280 -> "HD"    // 720p
        else -> "SD"
    }
}
```

---

## 12. 설정 시스템

`SharedPreferences`(`vlc_player_prefs`)에 저장되며, 앱 재시작 후에도 유지된다.

### 설정 항목

| 키 | 타입 | 기본값 | 설명 |
|----|------|--------|------|
| `sound_enabled` | Boolean | true | 소리 켜기/끄기 |
| `skip_seconds` | Int | 10 | 더블탭 스킵 시간 (초) |
| `swipe_sensitivity` | Int | 10 | 스와이프 탐색 민감도 (1~20) |
| `decoder_mode` | Int | 0 (AUTO) | 디코더 모드 (0=자동, 1=HW, 2=SW) |
| `subtitle_enabled` | Boolean | true | 자막 켜기/끄기 |
| `saved_brightness` | Float | -1 | 마지막 밝기 값 |
| `pos_<URI>` | Long | 0 | 영상별 재생 위치 (ms) |

### 소리 끄기 구현

```kotlin
private fun applySoundState() {
    mediaPlayer?.let { mp ->
        if (isSoundEnabled) {
            mp.volume = 100
            if (savedAudioTrackId >= 0) {
                mp.audioTrack = savedAudioTrackId  // 이전 오디오 트랙 복원
            }
        } else {
            val currentTrack = mp.audioTrack
            if (currentTrack >= 0) savedAudioTrackId = currentTrack  // 현재 트랙 저장
            mp.volume = 0
            mp.audioTrack = -1  // 오디오 트랙 비활성화
        }
    }
}
```

### 디코더 모드

디코더 변경 시 VLC LibVLC 인스턴스 재생성이 필요하므로, 변경 후 재시작해야 적용된다.

```kotlin
when (decoderMode) {
    DECODER_HW -> args.add("--avcodec-hw=any")    // GPU 하드웨어 디코딩
    DECODER_SW -> args.add("--avcodec-hw=none")   // CPU 소프트웨어 디코딩
    // DECODER_AUTO: VLC가 자동 선택
}
```

---

## 13. SMB 재연결 (Reconnect)

네트워크 불안정으로 SMB 스트리밍이 끊겼을 때 자동 재연결을 시도한다.

### VlcPlayerActivity의 재연결

VLC `EncounteredError` 이벤트 발생 시:

```kotlin
MediaPlayer.Event.EncounteredError -> {
    if (videoUri.scheme == "content" && retryCount < 3) {
        retryPlayback(videoUri)  // 재연결 시도
    } else {
        Toast.makeText(..., "재생 오류가 발생했습니다", ...).show()
        finish()
    }
}
```

```kotlin
private fun retryPlayback(videoUri: Uri) {
    retryCount++
    val resumePosition = mediaPlayer?.time ?: 0L  // 현재 재생 위치 저장

    handler.postDelayed({
        // 1. 기존 리소스 정리
        mediaPlayer?.stop()
        pfd?.close()
        pfd = null

        // 2. 새 File Descriptor 열기 (SMB 재연결 포함)
        pfd = contentResolver.openFileDescriptor(videoUri, "r")

        if (pfd != null) {
            // 3. 새 Media 생성 및 재생
            val fdUri = Uri.parse("fd://${pfd!!.fd}")
            val media = Media(libVlc!!, fdUri)
            mediaPlayer?.media = media
            media.release()
            mediaPlayer?.play()

            // 4. 이전 위치로 이동
            if (resumePosition > 0) {
                handler.postDelayed({
                    mediaPlayer?.time = resumePosition
                }, 500)
            }
            retryCount = 0  // 성공 시 카운터 리셋
        } else {
            if (retryCount < 3) retryPlayback(videoUri)  // 재시도
            else finish()
        }
    }, 2000)  // 2초 대기 후 시도
}
```

### SmbProxyCallback의 읽기 재시도

`onRead()` 중 오류 발생 시 (최대 2회 재시도):

1. 현재 파일 핸들 닫기
2. `SmbStreamManager.reconnect()` → 새 SMB 세션
3. `share.openFile()` → 새 파일 핸들
4. 읽기 재시도

---

## 14. 리소스 파일

### Drawable

| 파일 | 용도 |
|------|------|
| `ic_vlc_back.xml` | 뒤로 가기 아이콘 |
| `ic_vlc_play.xml` | 재생 아이콘 |
| `ic_vlc_pause.xml` | 일시정지 아이콘 |
| `ic_vlc_volume_up.xml` | 소리 켜짐 아이콘 |
| `ic_vlc_volume_off.xml` | 소리 꺼짐 아이콘 |
| `ic_vlc_rotate.xml` | 화면 회전 아이콘 |
| `ic_vlc_settings.xml` | 설정 아이콘 |
| `ic_vlc_subtitle.xml` | 자막 아이콘 |
| `vlc_gradient_top.xml` | 상단 바 그라데이션 (검정→투명) |
| `vlc_gradient_bottom.xml` | 하단 바 그라데이션 (투명→검정) |

모든 아이콘은 Vector Drawable (XML)로, 해상도 독립적이다.

---

## 15. 빌드 및 배포

### APK 빌드

```bash
# 메인 앱
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk (~42MB)

# VLC 플러그인
./gradlew :vlcplugin:assembleDebug
# → vlcplugin/build/outputs/apk/debug/vlcplugin-debug.apk (~85MB)
```

### 설치

두 APK를 각각 설치한다. 설치 순서는 무관하다.

```bash
adb install app-debug.apk
adb install vlcplugin-debug.apk
```

메인 앱을 재설치해도 VLC 플러그인은 유지된다 (별도 패키지이므로).

### Play Store 배포 시 고려사항

`docs/commercialization_todo.md` 참고:
- Dynamic Feature Module 전환 가능 (필요 시에만 다운로드)
- LGPL 2.1 라이센스 고지 필수 (libVLC)
- AAB 빌드 시 ABI별 자동 분리

---

## 16. 문제 해결 (Troubleshooting)

### "no access modules matched" 에러

**원인**: VLC가 `content://` URI를 직접 열려고 했지만 접근 권한이 없음.

**해결**: URI를 `Intent.data`로 전달하고 `FLAG_GRANT_READ_URI_PERMISSION` 플래그를 설정. VlcPlayerActivity에서는 `intent.data`에서 URI를 읽어 `contentResolver.openFileDescriptor()` → `fd://` 스킴으로 변환.

### NetworkOnMainThreadException (크로스 프로세스)

**원인**: 별도 APK에서 `contentResolver.openFileDescriptor()` 호출 시, Binder를 통해 StrictMode 정책이 전파됨. SmbStreamProvider의 `openFile()`에서 SMB 네트워크 작업이 메인 스레드 정책에 위배.

**해결**: `SmbStreamProvider.openFile()` 시작에서 `StrictMode.permitAll()`, finally에서 복원.

### openFileDescriptor() 반환값 null

**원인**: SMB 연결 끊김, 파일 없음, 또는 StrictMode 미해제.

**해결**: VlcPlayerActivity에서 최대 3회 재시도 (2초 간격), null이면 에러 메시지 후 종료.

### 자막 깨짐

**원인**: EUC-KR/CP949 인코딩 자막을 UTF-8로 읽으면 깨짐.

**해결**: `decodeSubtitleBytes()`에서 UTF-8 BOM → UTF-8 → EUC-KR/MS949/CP949 순서로 인코딩 감지 후 변환. 결과를 캐시에 UTF-8로 저장.

### VLC 재생 중 SMB 끊김

**원인**: 네트워크 불안정, SMB 세션 타임아웃.

**해결**:
1. `SmbProxyCallback.onRead()`에서 최대 2회 파일 핸들 재오픈
2. VLC `EncounteredError` 이벤트 시 `retryPlayback()` 최대 3회 (2초 간격)
3. `SmbStreamManager.createOptimizedClient()`의 5분 타임아웃

### APK 사이즈 최적화

- `ndk.abiFilters`로 필요한 ABI만 포함 (arm64-v8a, armeabi-v7a)
- x86, x86_64 제외 (에뮬레이터 전용)
- Play Store AAB 배포 시 디바이스별 ABI만 포함되어 자동 최적화

---

## 부록: 주요 VLC API

| API | 용도 |
|-----|------|
| `LibVLC(context, args)` | VLC 엔진 초기화 |
| `MediaPlayer(libVlc)` | 미디어 플레이어 생성 |
| `MediaPlayer.attachViews(layout)` | 비디오 출력 연결 |
| `Media(libVlc, uri)` | 미디어 소스 생성 (URI) |
| `mediaPlayer.media = media` | 미디어 설정 |
| `mediaPlayer.play() / pause() / stop()` | 재생 제어 |
| `mediaPlayer.time` | 현재 재생 위치 (ms, 읽기/쓰기) |
| `mediaPlayer.length` | 전체 재생 시간 (ms) |
| `mediaPlayer.rate` | 재생 속도 (1.0f = 기본) |
| `mediaPlayer.volume` | 볼륨 (0~100) |
| `mediaPlayer.audioTrack` | 오디오 트랙 ID (-1 = 비활성) |
| `mediaPlayer.spuTrack` | 자막 트랙 ID (-1 = 비활성) |
| `mediaPlayer.addSlave(type, uri, select)` | 외부 자막/오디오 파일 추가 |
| `media.trackCount / getTrack(i)` | 트랙 정보 조회 |
| `IMedia.VideoTrack.width/height/codec` | 비디오 트랙 상세 정보 |
| `media.release()` | Media 객체 해제 (설정 후 즉시) |
| `mediaPlayer.release()` | 플레이어 리소스 해제 |
| `libVlc.release()` | VLC 엔진 해제 |

### libVLC 명령줄 옵션

| 옵션 | 설명 |
|------|------|
| `--aout=opensles` | Android OpenSLES 오디오 출력 사용 |
| `--audio-time-stretch` | 배속 재생 시 피치 보정 활성화 |
| `--avcodec-hw=any` | 하드웨어 디코더 강제 사용 |
| `--avcodec-hw=none` | 소프트웨어 디코더 강제 사용 |
| `-vvv` | 상세 디버그 로그 (출시 시 제거) |

### Lifecycle 처리

```kotlin
onPause()   → mediaPlayer?.pause() + saveCurrentPosition()
onResume()  → mediaPlayer?.play()
onDestroy() → stopProgressUpdates() + removeCallbacks()
             + mediaPlayer?.stop() / detachViews() / release()
             + libVlc?.release()
             + pfd?.close()
```

리소스 해제 순서가 중요하다: MediaPlayer → LibVLC → ParcelFileDescriptor 순서로 해제해야 한다. 순서가 바뀌면 fd가 먼저 닫혀 VLC에서 크래시가 발생할 수 있다.
