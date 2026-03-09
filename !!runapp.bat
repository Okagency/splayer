@echo off
echo ========================================
echo   SPlayer - Build and Run
echo ========================================
echo.

cd /d %~dp0

set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
set ADB=D:\env\ANDROID_HOME\Sdk\platform-tools\adb.exe

echo [1/3] Building debug APK...
call gradlew.bat assembleDebug
if %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    pause
    exit /b 1
)
echo Build successful!
echo.

echo [2/3] Installing APK...
"%ADB%" install -r app\build\outputs\apk\debug\app-debug.apk
if %ERRORLEVEL% NEQ 0 (
    echo Install failed! Make sure emulator is running.
    pause
    exit /b 1
)
echo Install successful!
echo.

echo [3/3] Launching app...
"%ADB%" shell am start -n com.splayer.video/.ui.MainActivity
if %ERRORLEVEL% NEQ 0 (
    echo Launch failed!
    pause
    exit /b 1
)

echo.
echo ========================================
echo   App launched successfully!
echo ========================================
