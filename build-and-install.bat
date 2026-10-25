@echo off
rem Installs the launcher APK on the TV and opens it. It does NOT make it the Home app yet.
rem Usage:  build-and-install.bat [path-to-app-debug.apk]
set APK=%~1
if "%APK%"=="" set APK=app-debug.apk
if not exist "%APK%" (
  echo Cannot find %APK%. Download app-debug.apk from the GitHub Actions run and put it next to this file.
  exit /b 1
)
adb devices
adb install -r "%APK%"
rem lets the launcher read the Wi-Fi network name for the top-right pill
adb shell pm grant com.cleantv.home android.permission.ACCESS_FINE_LOCATION
adb shell am start -n com.cleantv.home/.MainActivity
timeout /t 3 >nul
adb exec-out screencap -p > shot-launcher.png
echo Saved shot-launcher.png - upload it so we can check the layout.
