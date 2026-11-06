# Wallpep

A personal-use Android live wallpaper: a clock and date with an optional on-device portrait cutout for a depth-sandwich effect, styled in the near-black **Obsidian Pulse** studio (electric-blue accent `#2563EB`, no gold) with a My Collection library of your own saved designs and a seven-tab editor. There are no accounts, network services, ads, payments or locked features. The implementation uses Android's public wallpaper, View, SurfaceHolder and Choreographer APIs; it does not use DepthX code or assets. This app is intended for personal use only and will not be published as a product or store release. This GitHub repository and its CI debug artifacts are public; see the [license/status caveat](STATUS.md).

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

1. Open Wallpep on **My Collection**. It lists only the wallpapers you saved yourself, with a local-only search, the four category chips (All Wallpapers, Favorited Designs, Hot Trending, Auto Rotate Cycle) and a two-column grid whose cards show your photo with the clock and date already composed. With nothing saved, the empty state offers **Create your first wallpaper**. Tap **+** to start a new design, tap a card to edit it, long-press one to rename, favourite, add to the rotate cycle or delete it. The bottom bar has exactly two destinations: My Collection and Settings.
2. In the editor, choose a system photo-picker image (or keep the synthetic built-in backdrop) with **Choose photo**; the app privately copies the selected image while its temporary picker grant is available. The source photo is not modified, images up to 40 MB are decoded with orientation respected and **center-cropped** to fill the screen, and no broad media permission is requested. A status pill over the phone-frame preview reports the on-device cutout state while the bundled portrait model works; other edits stay available and processing happens once per photo, not per frame.
3. Style it with the seven tabs — **Clock, Date, Text, Transform, Colorize, Parallax, Effects** — all unlocked. Clock and Date hold Format (17 time formats including the vertical/stacked rows, 8 date patterns), Typography (face cards, the searchable 112-family picker, size, letter spacing, alignment, vertical spacing), Colors (swatches, gradient toggle and angle, opacity), Position (X/Y and the over-foreground switches) and Effects (the full stroke and shadow families) and Transform (perspective, skews, rotation and stretch). The **Text** layer (custom string, saved face, size, spacing, colour/secondary/gradient + angle, opacity, X/Y position and the over-foreground switch), per-layer **Transform** (perspective, six skews, rotation, stretch), **Colorize** (Hue Rotation/Hue/Saturation/Grayscale/Brightness on the photo), **Parallax** (drags in the preview; the live wallpaper reads the gyroscope — no permission, works offline) and the **Effects** particle styles (Rain/Snow/Sakura with speed, angle, count, size, colour, opacity) all render in the live preview, in thumbnails and in the applied wallpaper. With a cutout, the clock and date start behind the foreground: the whole-clock switch puts both hour and minute in front, the individual hour/minute switches can split them, separators/AM–PM follow the minute, and the date switch is independent. If a cutout is unavailable, all text stays above the photo as in Phase 1.
4. Every change is persisted automatically (a short debounce while you drag, and immediately when you leave or apply), so the editor's `SAVED` chip reflects the stored state. Tap **Apply** in the header (or **Set wallpaper** in the full-screen preview) to open Android's live-wallpaper chooser.
5. In Android's live-wallpaper preview, choose **Home screen only** if the system offers destination choices. The exact system UI varies by device. No root, overlay, battery-optimization exemption or other special setup is needed.

### Home-screen destination

The public `ACTION_CHANGE_LIVE_WALLPAPER` API opens an OEM-controlled chooser and does not let this app force or filter its destination options. If the user selects both home and lock, the OS can install a shared wallpaper; a cached home frame may also be visible on the lock screen. On API 34+ Wallpep refuses to render an engine identified as lock-only, and all engines stop work when reported hidden, screen-off or locked. API 33 has no public engine destination flags. This is defense-in-depth, **not a guarantee that an OEM chooser cannot apply to the lock screen**. Use only the Home screen option; if the F54 does not offer it, do not complete application and report that limitation.

## Privacy and lifecycle

- No internet, gallery-wide, storage, overlay, notification, wake-lock or exact-alarm permission is requested. No network or analytics SDK is included. The model and ONNX Runtime are bundled; inference requires no download or server and is designed to work in airplane mode (device verification is pending). The pinned ORT 1.22 Android telemetry provider is a no-op, and telemetry is explicitly disabled; [newer runtimes need a privacy review](STATUS.md). No ML Kit, MediaPipe Tasks SDK or Play Services segmentation is used. The only URI received is the single selected image; it is not persisted as a URI.
- Photo copies, transparent foreground PNGs and failure markers live under `noBackupFilesDir`; configuration is local Preferences DataStore (schema version 1, with additive depth keys). Backup is disabled and explicit cloud/device-transfer exclusions are included. Abandoned drafts, old copies and their sidecars are cleaned on the next successful app start/save. Uninstall deletes the local copies. Android's own picker/provider may have its own behavior; Wallpep does not upload files.
- New photos are processed once; an existing Phase 1 photo with no cutout is queued once when settings open or a visible wallpaper engine starts. Work is off the UI thread, requests coalesce, and completed failures are remembered instead of retried on every redraw. Interrupted work may retry later. If the model or cutout fails, the wallpaper quietly falls back to its normal clock-over-photo rendering. Picking the photo again permits a fresh attempt.
- Each engine owns its own background/foreground bitmaps. A hidden, locked, screen-off or surface-destroyed engine cancels its pending frame and minute callback, unregisters time/format observers, cancels its data and decode jobs, and releases its bitmaps. A small screen-state receiver remains only while that engine is reported visible with a surface, to recover from delayed OEM screen/visibility signals. The system preview is subject to the same visibility gate as the applied engine.
- With no effects, drawing is event-driven: one coalesced Choreographer frame for visible changes, plus minute-boundary ticks and time/time-zone/date/locale/12–24-hour/settings/surface changes. While a particle effect is enabled, the visible preview and visible engine render at up to ~30 fps (never while hidden). Colors are cached; system notification is sent only for a materially different background palette. Normal Android process death and battery optimization are respected; no watchdog or exemption request is used. The gyroscope used for parallax is a normal rotation/gravity sensor — it needs no permission, works offline and stops when the engine is hidden.

## Font and asset licenses

The app bundles its own typeface library: **112 families / 185 files**, all taken from the official Google Fonts repository (`github.com/google/fonts`, commit `6c1dbe27dcf7d817d4b62cec033ba77fa8e2a5ec`) — the `ofl/` directory plus the five families that live in `apache/` (Luckiest Guy, Permanent Marker, Roboto Slab, Satisfy, Syncopate, the last as two files). No font, image or code came from DepthX, from any APK, or from another app.

- The per-file inventory (family, category, upstream path, license, source URL) is [`licenses/fonts/FONTS.md`](licenses/fonts/FONTS.md).
- Files live under `app/src/main/assets/fonts/<category>/<family>/`; each family folder also holds its own license text (`OFL.txt`, or `LICENSE-Apache-2.0.txt` for the Apache-licensed families), so the license text is bundled inside the APK next to the font it covers.
- The picker lists families, not files: a family uses its static Regular face, else its upright variable face, as the representative. The date can follow the clock's face or use its own.
- Provenance tooling: `tools/fonts/import-fonts.py` copies files out of a local `google/fonts` checkout, and `tools/fonts/generate-catalog.py` regenerates `FontCatalog.kt` from the inventory. Neither script downloads anything, and the committed files are the ones the build bundles.
- The library is 65 MB of font files; the CI debug APK artifact grows from 56,575,497 bytes on `main` to 94,358,940 bytes with the library bundled (about 38 MB more, since the packaged files are compressed). That growth is accepted for this personal, never-published app.

The app also bundles the photographic MODNet ONNX model (25,888,640 bytes / 24.7 MiB) for local one-shot inference. Treat its weights as **CC BY-NC-SA 4.0, non-commercial**; this is a personal-use-only app that will never be published. The [model notice](licenses/models/MODNet/NOTICE.md) credits the authors, identifies its exact SHA-256 and explains conflicting upstream license statements. ONNX Runtime is MIT; see its [license](licenses/onnxruntime/LICENSE.txt). If publication or commercial use ever becomes a goal, review the license and replace the model as necessary.

The built-in backdrop and wallpaper thumbnail are generated Canvas/vector artwork, not personal photos. Unit tests read only the bundled assets; no real user images are used.

## F54 verification checklist — **not yet run**

Keep these results separate from CI; record One UI and Android versions, APK SHA/run and observations. Do not upload personal photos, screenshots containing personal data, or logs with private image paths.

- Install the CI APK. If an older APK has a different debug signature, uninstall first (this clears Wallpep data). Open the app, save settings, open the system chooser, choose **Home screen only** and verify the applied home wallpaper. If home-only isn't offered, stop and record it.
- Pick a portrait and verify the visible processing state, responsiveness, orientation, private copy after the original is removed/moved, and automatic center crop at the F54's aspect ratio. Check invalid/oversized input is handled without losing the saved background. Try airplane mode; record inference time, peak memory if practical, and hair/shoulder edge quality rather than assuming performance from CI.
- Verify default subject-over-clock/date and every whole-clock, hour, minute and date combination, including 12-hour AM/PM and a non-English/RTL locale. Check that preview and home wallpaper use the same crop. Try a non-portrait and verify that an unavailable or failed cutout never blanks the photo. Confirm reselecting a photo can retry.
- Upgrade a compatible Phase 1 installation with a saved photo and verify the one-time non-blocking cutout, then reopen settings and check it is cached. Rapidly replace/remove a draft while processing and verify no old foreground is applied to the new photo.
- With the wallpaper visible, watch a minute boundary, midnight/date rollover, a time-zone change, locale change, and system 12/24-hour setting in **Follow system** mode. Verify explicit 12/24 options too.
- Verify the drag-to-tilt parallax in the editor preview and on-device with the gyroscope (tilt the phone; depth should shift, capped and smoothed). Verify Rain/Snow/Sakura animate in the preview, that the animation stops when the preview is hidden, and that the live wallpaper renders the same effect.
- With two or more saved designs and Auto Rotate set to a short interval (for verification set 1 hour, or temporarily inspect after the first due tick), verify the applied wallpaper cycles through the collection in saved order, survives a reboot, and does nothing — with no crash or placeholder — when the collection is empty.
- Change font, color, size, position and date style; save, leave the app, reopen and verify persistence and active update. Change settings, save, open the chooser, cancel it and confirm the save remains. Unsaved edits must not change the live configuration.
- Close/reopen the app, allow ordinary process death if feasible, and reboot; verify saved settings and copied image return. A system force-stop intentionally prevents background restarts until the user starts the app again.
- Open and close the system chooser preview repeatedly while the home wallpaper is applied; confirm neither a hidden home engine nor a hidden preview keeps visibly updating. Lock/unlock, turn screen off/on, and check the wallpaper resumes correctly. Check that the lock screen is not intentionally selected and record any OEM snapshot behavior.
- Repeat under Battery Saver and normal Samsung battery optimization. Observe responsiveness and battery use over time; do not disable optimization or infer battery life from CI.

[Scope and fixed rules](SCOPE.md) · [Current status](STATUS.md)
