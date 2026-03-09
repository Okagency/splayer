# sPlayer 로그 분석

## 로그 파일 위치

에뮬레이터/기기 내부:
```
/sdcard/Android/data/com.splayer.video/files/logs/splayer_crash_log.txt
```

## 로그 파일 가져오기

```bash
# 로그 파일을 로컬로 복사
adb pull /sdcard/Android/data/com.splayer.video/files/logs/splayer_crash_log.txt d:/dev/splayer3/logs/

# 또는 직접 확인
adb shell cat /sdcard/Android/data/com.splayer.video/files/logs/splayer_crash_log.txt
```

## 로그 확인 명령어

### 실시간 로그 확인
```bash
adb logcat | findstr "MainActivity VideoRepository CrashLogger"
```

### 로그 파일로 저장
```bash
adb logcat -d > d:/dev/splayer3/logs/logcat_dump.txt
```

### 앱 크래시 로그만 확인
```bash
adb logcat -s AndroidRuntime:E
```

## 로그 파일 포맷

### CRASH REPORT
앱이 크래시되면 자동으로 다음 정보가 기록됩니다:
- 발생 시간
- 스레드 이름
- 기기 정보
- 안드로이드 버전
- 예외 메시지
- 전체 스택 트레이스

### ERROR
런타임 에러가 발생하면 기록됩니다:
- 발생 시간
- 태그 (어느 클래스에서 발생했는지)
- 에러 메시지
- 스택 트레이스

### INFO
일반 정보성 로그:
- 앱 시작
- 주요 액션 수행
- 상태 변경
