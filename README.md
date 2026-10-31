# Wallpep

A personal-use Android live wallpaper: a clock and date over a locally selected photo. There are no accounts, network services, ads, payments or locked features. Phase 1 is an independent implementation using Android's public wallpaper, View, SurfaceHolder and Choreographer APIs; it does not use DepthX code or assets.

> **Device status:** No Samsung Galaxy F54 5G or other physical device has been tested in this environment. The checklist below is a plan, not a claim of device behavior. Android's chooser controls destination options; choose **Home screen only** when offered. See [the chooser limitation](#home-screen-destination).

## Build toolchain (pinned)

| Component | Version |
| --- | --- |
| Minimum Android | API 33 (Android 13) |
| Compile / target Android | API 37.0 / 37 (Android 17, stable) |
| Android SDK build tools | 37.0.0 |
| Android SDK command-line tools in CI | 13114758 |
| Android Gradle Plugin | 9.1.1 |
| Gradle wrapper | 9.3.1, distribution SHA-256 verified |
| Kotlin | 2.2.10 (AGP 9 built-in Kotlin, runtime explicitly pinned) |
| CI Java | Eclipse Temurin 17.0.17+10 |

Install the JDK, Android SDK command-line tools, platform `android-37.0` and build-tools `37.0.0`, accept the Android SDK licenses, and point `ANDROID_HOME` at the SDK. Android Studio Panda 3 (2025.3.3 Patch 1) or a newer compatible stable version can also import the project. Google's Maven repository and the Gradle distribution host must be reachable. Then, from the repository root:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

On Windows use `gradlew.bat`. The local debug APK is `app/build/outputs/apk/debug/app-debug.apk`. The [Android CI workflow](.github/workflows/android.yml) runs separate compile, unit-test and lint jobs. Its compile job uploads `wallpep-debug-<commit SHA>` for 14 days, containing that commit's debug APK. CI statuses and the artifact on a particular run are the authoritative evidence, not this document.

### Debug signing and updates

No stable signing keystore is included or assumed. CI uses the Android SDK's generated debug key; **different CI runs can have different signing keys, so an existing installation may need to be uninstalled before installing a later APK**. Uninstalling removes the app's settings and private photo copy. Do not commit keystores or secrets. This personal app is not intended for Play Store publishing.

## Use

1. Open Wallpep. Choose a system photo-picker image (or keep the synthetic built-in backdrop); the app privately copies the selected image while its temporary picker grant is available. The source photo is not modified. Supported images up to 40 MB are decoded with orientation respected. The image is automatically **center-cropped** to fill the screen; there is no manual crop editor in Phase 1. If the system picker is unavailable, AndroidX's system document-picker fallback may be used; no broad media permission is requested.
2. Adjust time format, date, typeface, text color, size and horizontal/vertical position. System time format follows Android's 12/24-hour preference; explicit 12/24 options override it. The in-app preview approximates the device's crop.
3. Tap **Save changes**. The save atomically updates the active configuration, including an already-applied wallpaper. **Open wallpaper chooser** prompts to save first if there are unsaved edits. Canceling the chooser cancels only application of the wallpaper; it does not undo saved settings.
4. In Android's live-wallpaper preview, choose **Home screen only** if the system offers destination choices. The exact system UI varies by device. No root, overlay, battery-optimization exemption or other special setup is needed.

### Home-screen destination

The public `ACTION_CHANGE_LIVE_WALLPAPER` API opens an OEM-controlled chooser and does not let this app force or filter its destination options. If the user selects both home and lock, the OS can install a shared wallpaper; a cached home frame may also be visible on the lock screen. On API 34+ Wallpep refuses to render an engine identified as lock-only, and all engines stop work when reported hidden, screen-off or locked. API 33 has no public engine destination flags. This is defense-in-depth, **not a guarantee that an OEM chooser cannot apply to the lock screen**. Use only the Home screen option; if the F54 does not offer it, do not complete application and report that limitation.

## Privacy and lifecycle

- No internet, gallery-wide, storage, overlay, notification, wake-lock or exact-alarm permission is requested. No network or analytics SDK is included. The only URI received is the single selected image; it is not persisted as a URI.
- Photo copies live under `noBackupFilesDir`; configuration is local Preferences DataStore (schema version 1). Backup is disabled and explicit cloud/device-transfer exclusions are included. Abandoned drafts and old copies are cleaned on the next successful app start/save. Uninstall deletes the local copy. Android's own picker/provider may have its own behavior; Wallpep does not upload files.
- Each engine owns its own bitmap. A hidden, locked, screen-off or surface-destroyed engine cancels its pending frame and minute callback, unregisters time/format observers, cancels its data and decode jobs, and releases its bitmap. A small screen-state receiver remains only while that engine is reported visible with a surface, to recover from delayed OEM screen/visibility signals. The system preview is subject to the same visibility gate as the applied engine.
- With no effects, drawing is event-driven: one coalesced Choreographer frame for visible changes, plus minute-boundary ticks and time/time-zone/date/locale/12–24-hour/settings/surface changes. There is no continuous frame loop. Colors are cached; system notification is sent only for a materially different background palette. Normal Android process death and battery optimization are respected; no watchdog or exemption request is used.

## Font and asset licenses

Only two font files are bundled; system sans is a third option. Both are SIL Open Font License 1.1, downloaded from the official Google Fonts repository at commit `6c1dbe27dcf7d817d4b62cec033ba77fa8e2a5ec`, not from DepthX. License texts are committed alongside this notice:

| Family / bundled file | Upstream path | License | SHA-256 |
| --- | --- | --- | --- |
| Outfit / `assets/fonts/outfit.ttf` | `ofl/outfit/Outfit[wght].ttf` | [OFL](licenses/fonts/Outfit/OFL.txt) | `fc7287273e66929776e2ba54f144fe699080bec29f61bf649d70d871468aeade` |
| DM Sans / `assets/fonts/dm_sans.ttf` | `ofl/dmsans/DMSans[opsz,wght].ttf` | [OFL](licenses/fonts/DMSans/OFL.txt) | `8cd08d97e89c24d0aa92edd2f0f4c8ee6195eee9b7c9f154865a58b02f0c1c0d` |

The default backdrop and wallpaper thumbnail are generated vector/Canvas artwork, not personal photos. Tests contain no real user images.

## F54 verification checklist — **not yet run**

Keep these results separate from CI; record One UI and Android versions, APK SHA/run and observations. Do not upload personal photos, screenshots containing personal data, or logs with private image paths.

- Install the CI APK. If an older APK has a different debug signature, uninstall first (this clears Wallpep data). Open the app, save settings, open the system chooser, choose **Home screen only** and verify the applied home wallpaper. If home-only isn't offered, stop and record it.
- Pick a photo and verify orientation, private copy after the original is removed/moved, and automatic center crop at the F54's aspect ratio. Check invalid/oversized input is handled without losing the saved background.
- With the wallpaper visible, watch a minute boundary, midnight/date rollover, a time-zone change, locale change, and system 12/24-hour setting in **Follow system** mode. Verify explicit 12/24 options too.
- Change font, color, size, position and date style; save, leave the app, reopen and verify persistence and active update. Change settings, save, open the chooser, cancel it and confirm the save remains. Unsaved edits must not change the live configuration.
- Close/reopen the app, allow ordinary process death if feasible, and reboot; verify saved settings and copied image return. A system force-stop intentionally prevents background restarts until the user starts the app again.
- Open and close the system chooser preview repeatedly while the home wallpaper is applied; confirm neither a hidden home engine nor a hidden preview keeps visibly updating. Lock/unlock, turn screen off/on, and check the wallpaper resumes correctly. Check that the lock screen is not intentionally selected and record any OEM snapshot behavior.
- Repeat under Battery Saver and normal Samsung battery optimization. Observe responsiveness and battery use over time; do not disable optimization or infer battery life from CI.

[Scope and fixed rules](SCOPE.md) · [Current status](STATUS.md)
