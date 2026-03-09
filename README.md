# sPlayer - 갤럭시 비디오플레이어 Clone

갤럭시 비디오플레이어와 동일한 기능을 가진 고성능 안드로이드 비디오 플레이어 앱입니다.

## 주요 기능

### 비디오 재생
- **ExoPlayer 기반**: Google의 공식 ExoPlayer를 사용하여 안정적이고 빠른 재생
- **다양한 포맷 지원**: MP4, MKV, AVI 등 주요 비디오 포맷 지원
- **하드웨어 가속**: GPU 하드웨어 가속을 통한 부드러운 재생
- **자막 지원**: 다양한 자막 형식 지원
- **멀티 오디오 트랙**: 여러 오디오 트랙 선택 가능

### 비디오 관리
- **자동 스캔**: 기기 내 모든 비디오 자동 검색
- **썸네일 표시**: 고성능 Glide 라이브러리로 빠른 썸네일 로딩
- **정렬 기능**: 이름, 날짜, 크기, 재생시간별 정렬
- **폴더별 분류**: 비디오를 폴더별로 구분하여 표시

### 재생 기능
- **재생 위치 저장**: 마지막 재생 위치 자동 저장 및 복원
- **재생 컨트롤**: 재생, 일시정지, 빨리감기, 되감기
- **터치 제스처**: 화면 탭으로 컨트롤 표시/숨김
- **전체화면 모드**: 몰입형 전체화면 재생

## 기술 스택

### 플랫폼
- **Android Native**: Kotlin
- **Min SDK**: API 24 (Android 7.0)
- **Target SDK**: API 34 (Android 14)

### 아키텍처
- **MVVM Pattern**: 명확한 관심사 분리
- **Repository Pattern**: 데이터 레이어 추상화
- **Single Activity**: 효율적인 메모리 관리

### 주요 라이브러리
- **ExoPlayer (Media3)**: 비디오 재생 엔진
- **Room Database**: 재생 위치 및 설정 저장
- **Glide**: 썸네일 이미지 로딩 및 캐싱
- **Kotlin Coroutines**: 비동기 처리
- **LiveData/StateFlow**: 반응형 상태 관리
- **View Binding**: 타입 안전 뷰 참조

### 성능 최적화
- **RecyclerView + DiffUtil**: 효율적인 목록 렌더링
- **이미지 캐싱**: 메모리 및 디스크 캐싱 전략
- **백그라운드 스캔**: UI 블로킹 없는 비디오 스캔
- **ProGuard/R8**: 앱 크기 최소화 및 난독화

## 프로젝트 구조

```
app/src/main/
├── java/com/splayer/video/
│   ├── data/
│   │   ├── local/          # Room Database 및 DAO
│   │   ├── model/          # 데이터 모델
│   │   └── repository/     # Repository 구현
│   ├── ui/
│   │   ├── adapter/        # RecyclerView 어댑터
│   │   ├── player/         # 플레이어 화면
│   │   ├── MainActivity.kt # 메인 화면
│   │   └── MainViewModel.kt
│   └── SPlayerApplication.kt
└── res/
    ├── layout/             # XML 레이아웃
    ├── drawable/           # 아이콘 및 그래픽
    ├── mipmap/             # 앱 아이콘
    └── values/             # 문자열, 색상, 테마
```

## 빌드 방법

### 요구사항
- Android Studio Hedgehog (2023.1.1) 이상
- JDK 17 이상
- Android SDK 34

### 빌드 명령
```bash
# Debug 빌드
./gradlew assembleDebug

# Release 빌드
./gradlew assembleRelease

# 앱 설치 및 실행
./gradlew installDebug
```

## 권한

앱은 다음 권한을 요청합니다:

- **READ_MEDIA_VIDEO** (Android 13+): 비디오 파일 접근
- **READ_EXTERNAL_STORAGE** (Android 12 이하): 저장소 접근
- **WAKE_LOCK**: 재생 중 화면 유지

## 라이선스

이 프로젝트는 교육 목적으로 제작되었습니다.

## 개발자

sPlayer - 갤럭시 비디오플레이어 클론 앱

---

**참고**: 이 앱은 갤럭시 비디오플레이어의 핵심 기능을 구현한 클론 프로젝트입니다.
