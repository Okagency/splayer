# SMB 자막 구현 가이드

## 개요

SMB 네트워크 공유 폴더의 동영상 재생 시 같은 폴더에 있는 자막 파일을 자동으로 찾아서 로드하는 기능.

## 핵심 구조

### 1. SMB 동영상 URI 형식

SMB 동영상은 `SmbStreamProvider`를 통해 `content://` URI로 변환되어 재생됨:

```
content://com.cubby.smb.stream/영화/폴더/video.mp4
```

- Authority: `com.cubby.smb.stream`
- Path: SMB 공유 내의 상대 경로 (URL 인코딩됨)

### 2. 관련 파일

| 파일 | 역할 |
|------|------|
| `PlayerActivity.kt` | 자막 검색 및 로드 로직 |
| `SmbStreamProvider.kt` | SMB 파일을 content:// URI로 제공 |
| `SmbStreamManager.kt` | SMB 세션(DiskShare) 싱글톤 관리 |

---

## 구현 상세

### 1. 자막 검색 흐름

```
initializePlayer()
    ↓
findSubtitles(videoUri)
    ↓
[content:// URI 감지] → SmbStreamProvider URI 확인
    ↓
findSubtitlesViaSmbUri()
    ↓
[백그라운드 스레드에서 SMB 작업]
    ↓
자막 파일 로드 → 캐시에 저장 → ExoPlayer에 적용
```

### 2. SmbStreamProvider URI 감지

```kotlin
// PlayerActivity.kt - findSubtitles() 함수 내
if (parsedVideoUri.authority == "com.cubby.smb.stream") {
    val smbPath = parsedVideoUri.path?.removePrefix("/") ?: ""
    if (smbPath.isNotEmpty()) {
        Log.d(TAG, "Detected SmbStreamProvider URI, SMB path: $smbPath")
        val smbVideoName = smbPath.replace("\\", "/").substringAfterLast("/").substringBeforeLast(".")
        findSubtitlesViaSmbUri("smb:///$smbPath", smbVideoName, subtitleExtensions, subtitles)
    }
}
```

**주의사항:**
- `parsedVideoUri.authority`로 SmbStreamProvider 여부 확인
- SMB 경로에서 파일명 추출 시 `\`와 `/` 모두 처리

### 3. SMB 자막 검색 함수

```kotlin
private fun findSubtitlesViaSmbUri(
    videoUri: String,
    videoName: String,
    subtitleExtensions: List<String>,
    subtitles: MutableList<SubtitleInfo>
) {
    val diskShare = SmbStreamManager.diskShare ?: run {
        Log.w(TAG, "SMB not connected, cannot search subtitles")
        return
    }

    try {
        val rawPath = Uri.parse(videoUri).path?.removePrefix("/") ?: return
        // ★ 중요: smbj는 백슬래시(\) 사용
        val normalizedPath = rawPath.replace("/", "\\")
        val parentPath = normalizedPath.substringBeforeLast("\\")

        // ★ 중요: 백그라운드 스레드에서 실행 (NetworkOnMainThreadException 방지)
        val files = runBlocking(Dispatchers.IO) {
            diskShare.list(parentPath)
        }

        for (ext in subtitleExtensions) {
            val matchingFile = files.find { fileInfo ->
                val fileName = fileInfo.fileName
                val fileNameWithoutExt = fileName.substringBeforeLast(".")
                val fileExt = fileName.substringAfterLast(".", "").lowercase()
                fileNameWithoutExt.equals(videoName, ignoreCase = true) && fileExt == ext.lowercase()
            }

            if (matchingFile != null) {
                val subtitlePath = "$parentPath\\${matchingFile.fileName}"

                // ★ 중요: SMB 파일 읽기도 백그라운드 스레드
                val subtitleContent = runBlocking(Dispatchers.IO) {
                    val smbFile = diskShare.openFile(
                        subtitlePath,
                        EnumSet.of(AccessMask.GENERIC_READ),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null
                    )

                    val content = smbFile.inputStream.use { input ->
                        // 파일 내용 읽기
                        val buffer = ByteArrayOutputStream()
                        val data = ByteArray(16384)
                        var nRead: Int
                        while (input.read(data).also { nRead = it } != -1) {
                            buffer.write(data, 0, nRead)
                        }
                        readTextWithEncoding(buffer.toByteArray(), ext)
                    }

                    smbFile.close()
                    content
                }

                addSubtitleToList(matchingFile.fileName, subtitleContent, ext, subtitles)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "SMB subtitle search error: ${e.javaClass.simpleName}: ${e.message}", e)
    }
}
```

---

## 핵심 주의사항

### 1. 경로 구분자 (★ 가장 중요)

| 용도 | 구분자 |
|------|--------|
| smbj API (`diskShare.list()`, `diskShare.openFile()`) | 백슬래시 `\` |
| URI 파싱 (`Uri.parse()`) | 포워드슬래시 `/` |

```kotlin
// 올바른 방법: URI → SMB 경로 변환
val rawPath = Uri.parse(videoUri).path?.removePrefix("/") ?: return
val smbPath = rawPath.replace("/", "\\")  // smbj용 백슬래시로 변환
```

### 2. 스레드 처리 (★ 필수)

Android에서 네트워크 작업은 메인 스레드에서 실행 불가 (`NetworkOnMainThreadException`).

**해결 방법:**
```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

// SMB 작업을 백그라운드에서 실행
val result = runBlocking(Dispatchers.IO) {
    diskShare.list(path)
}
```

### 3. 자막 인코딩 처리

한국어 자막(특히 .smi 파일)은 EUC-KR/MS949 인코딩이 많음.

**주의:** UTF-8 파일을 EUC-KR로 먼저 디코딩하면 깨진 문자가 생기므로 순서가 중요함.

```kotlin
private fun readTextWithEncoding(bytes: ByteArray, fileExtension: String): String {
    // 1. BOM (Byte Order Mark) 체크
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        Log.d(TAG, "UTF-8 BOM 감지됨")
        return String(bytes.drop(3).toByteArray(), Charsets.UTF_8)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        Log.d(TAG, "UTF-16LE BOM 감지됨")
        return String(bytes, Charsets.UTF_16LE)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        Log.d(TAG, "UTF-16BE BOM 감지됨")
        return String(bytes, Charsets.UTF_16BE)
    }

    // 2. UTF-8 먼저 시도 (replacement character가 없으면 유효)
    try {
        val utf8Text = String(bytes, Charsets.UTF_8)
        if (!utf8Text.contains("\uFFFD")) {
            Log.d(TAG, "UTF-8 인코딩 감지됨 (replacement char 없음)")
            return utf8Text
        }
    } catch (e: Exception) { }

    // 3. 한국어 레거시 인코딩 시도 (SMI 파일용)
    val legacyEncodings = if (fileExtension in listOf("smi", "sami")) {
        listOf("EUC-KR", "MS949", "CP949")
    } else {
        listOf("EUC-KR", "MS949")
    }

    for (encoding in legacyEncodings) {
        try {
            val text = String(bytes, charset(encoding))
            // 한글이 포함되어 있고 replacement character가 없으면 성공
            if (text.contains(Regex("[가-힣]")) && !text.contains("\uFFFD")) {
                Log.d(TAG, "$encoding 인코딩으로 한글 감지됨")
                return text
            }
        } catch (e: Exception) { }
    }

    // 4. 마지막 시도: EUC-KR (한국 SMI 파일 대부분이 이 인코딩)
    try {
        val eucKrText = String(bytes, charset("EUC-KR"))
        Log.d(TAG, "EUC-KR 인코딩 사용 (기본값)")
        return eucKrText
    } catch (e: Exception) { }

    Log.w(TAG, "인코딩 감지 실패, UTF-8 기본값 사용")
    return String(bytes, Charsets.UTF_8)
}
```

**인코딩 감지 순서의 중요성:**

| 순서 | 체크 항목 | 이유 |
|------|----------|------|
| 1 | BOM 확인 | BOM이 있으면 확실한 인코딩 정보 |
| 2 | UTF-8 먼저 시도 | replacement char(`\uFFFD`) 없으면 유효한 UTF-8 |
| 3 | EUC-KR/MS949 시도 | 한글 포함 + replacement char 없으면 성공 |
| 4 | EUC-KR 기본값 | 한국 SMI 파일 대부분이 이 인코딩 |

**주의:** EUC-KR을 UTF-8보다 먼저 시도하면, UTF-8 파일의 한글이 `[가-힣]` 범위의 깨진 문자로 변환되어 잘못된 인코딩으로 판정될 수 있음.

### 4. SMI → SRT 변환

ExoPlayer는 .smi 형식을 직접 지원하지 않으므로 SRT로 변환 필요.

**관련 파일:** `player/util/SamiToSrtConverter.kt`

```kotlin
// PlayerActivity.kt - addSubtitleToList() 내
if (ext in listOf("smi", "sami")) {
    val convertedFile = convertSmiContentToSrt(subtitleFileName, subtitleContent)
    // SRT 파일로 자막 추가
}

// SMI 내용을 SRT로 변환 (이미 디코딩된 String 사용)
private fun convertSmiContentToSrt(smiFileName: String, smiContent: String): File? {
    return try {
        val cacheDir = File(cacheDir, "converted_subtitles")
        cacheDir.mkdirs()

        val fileNameWithoutExt = smiFileName.substringBeforeLast(".")
        val convertedFileName = "${fileNameWithoutExt}_converted.srt"
        val convertedFile = File(cacheDir, convertedFileName)

        // ★ 중요: 이미 디코딩된 내용을 직접 변환 (파일 I/O 인코딩 문제 방지)
        val success = SamiToSrtConverter.convertContent(smiContent, convertedFile)

        if (success && convertedFile.exists()) convertedFile else null
    } catch (e: Exception) {
        Log.e(TAG, "SMI to SRT conversion failed: ${e.message}")
        null
    }
}
```

**SamiToSrtConverter 핵심 메서드:**

```kotlin
// SamiToSrtConverter.kt
object SamiToSrtConverter {
    /**
     * 이미 디코딩된 SMI 내용을 SRT로 변환
     * SMB에서 읽은 내용처럼 이미 String으로 변환된 경우 사용
     */
    fun convertContent(smiContent: String, outputFile: File): Boolean {
        val subtitles = parseSamiContent(smiContent)
        if (subtitles.isEmpty()) return false
        writeSrtFile(outputFile, subtitles)  // UTF-8로 저장
        return true
    }
}
```

**SMI 변환 흐름:**
```
SMB에서 bytes 읽기
    ↓
readTextWithEncoding() → 올바른 인코딩으로 String 변환
    ↓
SamiToSrtConverter.convertContent() → SMI 파싱 → SRT로 변환
    ↓
UTF-8로 캐시 파일 저장
    ↓
ExoPlayer에 SRT 파일 전달
```

**주의:** `convert(File, File)` 대신 `convertContent(String, File)` 사용 이유:
- SMB에서 이미 `readTextWithEncoding()`으로 올바르게 디코딩된 String을 사용
- 파일을 다시 읽으면 인코딩 감지가 다시 수행되어 오류 발생 가능

### 5. 자막 캐시

SMB에서 읽은 자막은 로컬 캐시에 저장 후 ExoPlayer에 전달:

```kotlin
val cacheDir = File(cacheDir, "subtitle_cache")
cacheDir.mkdirs()
val cachedFile = File(cacheDir, subtitleFileName)
cachedFile.writeText(subtitleContent)

subtitles.add(SubtitleInfo(
    uri = Uri.fromFile(cachedFile),
    name = cachedFile.name,
    mimeType = MimeTypes.APPLICATION_SUBRIP
))
```

---

## 지원하는 자막 형식

| 확장자 | MIME Type | 비고 |
|--------|-----------|------|
| .srt | `MimeTypes.APPLICATION_SUBRIP` | 가장 일반적 |
| .vtt | `MimeTypes.TEXT_VTT` | WebVTT |
| .ass | `MimeTypes.TEXT_SSA` | Advanced SubStation Alpha |
| .ssa | `MimeTypes.TEXT_SSA` | SubStation Alpha |
| .smi | - | SRT로 변환 필요 |
| .sami | - | SRT로 변환 필요 |

---

## 필요한 Import

```kotlin
import android.net.Uri
import android.util.Log
import com.cubby.provider.SmbStreamManager
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.EnumSet
```

---

## 디버깅 체크리스트

1. **자막이 전혀 안 보일 때:**
   - 로그에서 `Detected SmbStreamProvider URI` 확인
   - `SmbStreamManager.diskShare`가 null인지 확인

2. **SMB subtitle search error 발생:**
   - `NetworkOnMainThreadException` → `runBlocking(Dispatchers.IO)` 누락
   - 경로 에러 → 백슬래시/포워드슬래시 확인

3. **자막 파일을 찾았지만 로드 실패:**
   - 인코딩 문제 → `readTextWithEncoding` 확인
   - SMI 파일 → SRT 변환 로직 확인

4. **SMI 자막 글자 깨짐:**
   - 인코딩 감지 순서 확인 (UTF-8 → EUC-KR 순서 필수)
   - 로그에서 어떤 인코딩이 선택되었는지 확인
   - `SamiToSrtConverter` 로그에서 첫 자막 내용 확인
   ```bash
   adb logcat -s SamiToSrtConverter | grep "첫 자막\|한글포함\|미리보기"
   ```

5. **SMI 변환 실패 (파싱된 자막이 없음):**
   - SMI 파일 구조 확인 (`<SYNC Start=숫자>` 태그 존재 여부)
   - `SamiToSrtConverter` 로그 확인

6. **로그 확인 명령:**
   ```bash
   # 전체 자막 관련 로그
   adb logcat -s PlayerActivity SamiToSrtConverter | grep -i "subtitle\|자막\|인코딩\|SMI"

   # 인코딩 감지 로그만
   adb logcat -s PlayerActivity | grep -i "인코딩\|BOM\|UTF\|EUC"
   ```

---

## 예제 로그 (정상 동작)

### SRT 자막 (정상)
```
D/PlayerActivity: Finding subtitles for: content://com.cubby.smb.stream/영화/Movie/video.mp4
D/PlayerActivity: Detected SmbStreamProvider URI, SMB path: 영화/Movie/video.mp4
D/PlayerActivity: Searching SMB subtitles in: 영화\Movie for video: video
D/PlayerActivity: Found SMB subtitle: 영화\Movie\video.srt
D/PlayerActivity: UTF-8 인코딩 감지됨 (replacement char 없음)
D/PlayerActivity: Loaded SMB subtitle: video.srt
D/PlayerActivity: Found 1 subtitles
```

### SMI 자막 (정상 - EUC-KR 인코딩)
```
D/PlayerActivity: Finding subtitles for: content://com.cubby.smb.stream/영화/Movie/video.mp4
D/PlayerActivity: Searching SMB subtitles in: 영화\Movie for video: video
D/PlayerActivity: Found SMB subtitle: 영화\Movie\video.smi
D/PlayerActivity: EUC-KR 인코딩으로 한글 감지됨
D/SamiToSrtConverter: SMI 내용 변환 시작 -> video_converted.srt
D/SamiToSrtConverter: 입력 길이: 45230, 한글포함: true
D/SamiToSrtConverter: 파싱된 자막 수: 523
D/SamiToSrtConverter: 첫 자막: 안녕하세요
D/SamiToSrtConverter: SMI -> SRT 변환 성공
D/PlayerActivity: Loaded SMB subtitle: video.smi
```

### SMI 자막 (문제 - 인코딩 오감지)
```
D/PlayerActivity: UTF-8 인코딩 감지됨 (replacement char 없음)  ← 잘못된 감지!
D/SamiToSrtConverter: 입력 길이: 45230, 한글포함: false  ← 한글 없음 = 깨짐
D/SamiToSrtConverter: SMI 미리보기: <SAMI>\n<HEAD>...  ← 깨진 문자 확인
```

이런 로그가 나오면 `readTextWithEncoding()` 함수의 인코딩 감지 순서 확인 필요.

---

## 트러블슈팅: SMI 인코딩 문제

### 문제: SMI 자막 글자 깨짐

**원인 1: 인코딩 감지 순서 오류**
- EUC-KR을 UTF-8보다 먼저 시도하면 UTF-8 한글이 깨진 상태로 `[가-힣]` 범위에 매칭됨
- **해결:** UTF-8 먼저 시도 → replacement char 없으면 UTF-8 확정

**원인 2: BOM 없는 UTF-8 파일을 EUC-KR로 읽음**
- UTF-8 파일에 BOM이 없고 replacement char도 생성되지 않는 경우
- **해결:** UTF-8 디코딩 후 한글 유효성 추가 검증

**원인 3: convertContent() 대신 convert() 사용**
- `convert(File, File)`은 파일을 다시 읽어서 인코딩 재감지
- 이미 `readTextWithEncoding()`으로 올바르게 디코딩한 내용이 다시 잘못 디코딩될 수 있음
- **해결:** `convertContent(String, File)` 사용 (이미 디코딩된 String 전달)

### 확인 방법

```bash
# 1. 인코딩 감지 결과 확인
adb logcat -s PlayerActivity | grep "인코딩"

# 2. SMI 변환 입력 확인
adb logcat -s SamiToSrtConverter | grep "한글포함\|미리보기"

# 3. 변환된 SRT 파일 내용 확인
adb shell cat /data/data/com.cubby/cache/converted_subtitles/*.srt | head -20
```

### 인코딩 감지 로직 핵심

```kotlin
// ✅ 올바른 순서
1. BOM 확인 (확실한 인코딩 정보)
2. UTF-8 시도 → replacement char 없으면 확정
3. EUC-KR/MS949 시도 → 한글 포함 + replacement char 없으면 확정
4. EUC-KR 기본값 (한국 SMI 대부분)

// ❌ 잘못된 순서
1. EUC-KR 먼저 시도 → UTF-8 한글이 깨진 채로 매칭됨
```
