@echo off
echo ========================================
echo sPlayer Log Checker
echo ========================================
echo.

echo [1] Pulling crash log from device...
adb pull /sdcard/Android/data/com.splayer.video/files/logs/splayer_crash_log.txt logs\splayer_crash_log.txt 2>nul

if exist logs\splayer_crash_log.txt (
    echo SUCCESS: Log file downloaded to logs\splayer_crash_log.txt
    echo.
    echo [2] Log file contents:
    echo ----------------------------------------
    type logs\splayer_crash_log.txt
    echo ----------------------------------------
) else (
    echo WARNING: Log file not found on device
    echo This might mean:
    echo   - App hasn't been run yet
    echo   - App doesn't have storage permission
    echo   - Device not connected
    echo.
    echo [3] Checking Logcat instead...
    echo ----------------------------------------
    adb logcat -d -s MainActivity:* VideoRepository:* MainViewModel:* CrashLogger:* AndroidRuntime:E
)

echo.
echo ========================================
echo Log check complete
echo ========================================
pause
