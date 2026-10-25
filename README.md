# Clean TV Home (Mi TV 4i)

An Apple TV style home screen: black background, a dock of four apps, the rest of your apps in a grid below,
a glass highlight that glides between tiles, clock and date top left, Inputs / Wi-Fi / Settings pills top right.

## Build (no Android Studio needed)
1. Create a free repository on github.com and upload the contents of this folder (including `.github/workflows/build.yml`).
2. Open the **Actions** tab, pick **Build APK**, wait for the green tick (2-4 minutes).
3. Open the finished run and download **CleanTVHome-debug-apk** (a zip containing `app-debug.apk`).
4. Put `app-debug.apk` next to `build-and-install.bat` and run it with the TV connected over ADB.

## Re-enable any disabled app
Run `restore.bat` to re-enable everything we disabled, or one app: `adb shell pm enable <package>`.
Disabled list: `adb shell pm list packages -d`.

## Change the dock order or apps
Edit `app/src/main/res/values/config.xml`:
- `dock_apps`: the first `columns` packages become the dock, left to right.
- `grid_apps`: order of the apps below the dock. Anything not listed follows A to Z.
- `columns`: 4 to 7 (dock slots = grid columns).
- `greeting_name`: optional name for the greeting.
Then rebuild (commit the change on GitHub, download the new APK) and run `build-and-install.bat` again.

## Wallpaper
The home screen is pitch black on purpose. To add a picture, save it as `wallpaper.jpg` in
`app/src/main/res/drawable-nodpi/`, rebuild and reinstall. Delete the file to go back to black.

## Make it your Home / undo
Only after `build-and-install.bat` shows the launcher working. The takeover commands are given step by step in the chat.
To undo: `adb shell pm enable com.mitv.tvhome.atv` and remove it as default under Settings > Apps, or uninstall with
`adb uninstall com.cleantv.home` and Home falls back to Projectivy / PatchWall.

## Finish: close network ADB
When everything works: turn USB debugging and Network debugging off in Developer options on the TV, reboot,
and confirm port 5555 refuses connections.

Font: Outfit (SIL Open Font License, see OFL-Outfit.txt).
