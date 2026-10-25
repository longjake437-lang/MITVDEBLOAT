@echo off
rem Re-enables every package we disabled on the Mi TV 4i. Run it with the TV connected over ADB.
rem Usage:  adb connect 192.168.1.68:5555   then   restore.bat
rem (Six other packages were already disabled before we started; they are listed in packages_disabled.txt and are not touched here.)

rem Batch 1: telemetry
adb shell pm enable com.miui.tv.analytics
adb shell pm enable com.xiaomi.statistic
adb shell pm enable com.google.android.feedback
adb shell pm enable com.google.android.tv.bugreportsender
rem Batch 2: PatchWall content and recommendations
adb shell pm enable com.mitv.tvhome.mitvplus
adb shell pm enable com.mitv.tvhome.michannel
adb shell pm enable com.mitv.tvhome.oemtab
adb shell pm enable co.sensara.tv.mitv.res
rem Batch 3: unused preloads
adb shell pm enable com.android.gallery3d
adb shell pm enable com.android.htmlviewer
adb shell pm enable org.chromium.webview_shell
adb shell pm enable com.xiaomi.mimusic2
adb shell pm enable com.mitv.gallery
rem Batch 4: Mi updater (only if you ran it)
adb shell pm enable com.xiaomi.mitv.updateservice

adb shell pm list packages -d
echo Done. Packages left in the list above were disabled before we started.
