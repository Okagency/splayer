# sPlayer 실행 방법

## Android Studio에서 실행하기 (권장)

### 1. Android Studio 열기
1. Android Studio를 실행합니다
2. "Open" 또는 "Open an Existing Project" 선택
3. `d:\dev\splayer3` 폴더를 선택합니다

### 2. Gradle Sync
- 프로젝트가 열리면 자동으로 Gradle Sync가 시작됩니다
- 상단에 "Sync Now" 버튼이 나타나면 클릭합니다
- 필요한 의존성이 자동으로 다운로드됩니다

### 3. 에뮬레이터 설정
1. 상단 툴바에서 "Device Manager" 클릭
2. "Create Device" 선택
3. Phone > Pixel 6 또는 원하는 기기 선택
4. System Image > API 34 (Android 14) 선택 및 다운로드
5. "Finish"로 에뮬레이터 생성 완료

### 4. 앱 실행
1. 상단 툴바에서 생성한 에뮬레이터 선택
2. 초록색 "Run" 버튼 (▶) 클릭
3. 에뮬레이터가 시작되고 앱이 자동으로 설치 및 실행됩니다

## 커맨드라인에서 실행하기

### 1. Gradle Wrapper 초기화
```bash
cd d:\dev\splayer3
gradle wrapper
```

### 2. 빌드
```bash
./gradlew assembleDebug
```

### 3. 에뮬레이터 실행
```bash
# 사용 가능한 에뮬레이터 목록 확인
emulator -list-avds

# 에뮬레이터 실행
emulator -avd <AVD_NAME>
```

### 4. APK 설치
```bash
# 에뮬레이터가 실행 중일 때
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 필수 요구사항
- Android Studio Hedgehog (2023.1.1) 이상
- JDK 17 이상
- Android SDK 34
- 최소 8GB RAM (에뮬레이터 실행 시)

## 주의사항
- 첫 실행 시 권한 요청 화면이 나타납니다
- "파일 및 미디어" 접근 권한을 허용해야 비디오 목록이 표시됩니다
- 에뮬레이터에 비디오 파일을 추가하려면:
  1. Android Studio > Device File Explorer
  2. `/sdcard/Movies` 또는 `/sdcard/Download` 폴더에 비디오 파일 업로드

## 테스트용 비디오 추가
```bash
# 로컬 비디오를 에뮬레이터로 복사
adb push video.mp4 /sdcard/Movies/
```

## 문제 해결

### Gradle Sync 실패
- File > Invalidate Caches and Restart
- SDK Manager에서 필요한 SDK 설치

### 에뮬레이터가 느린 경우
- BIOS에서 가상화(VT-x 또는 AMD-V) 활성화
- Intel HAXM 또는 AMD Hypervisor 설치

### 앱이 크래시하는 경우
- Logcat에서 에러 확인 (View > Tool Windows > Logcat)
- 권한이 제대로 부여되었는지 확인
