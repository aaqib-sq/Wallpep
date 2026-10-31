# Status

## Current phase

Phase 2 implements a one-shot, on-device portrait cutout and depth-sandwich compositing on top of the Phase 1 photo picker, settings, save/apply flow and visibility-gated, minute-driven live wallpaper. CI for the specific commit is the authority for Android assembly, unit tests, lint, privacy checks and the debug APK; no local Android build is claimed.

## Model, licensing and privacy

- **Model:** `modnet_photographic_portrait_matting.onnx`, photographic portrait weights, 25,888,640 bytes (24.7 MiB), SHA-256 `07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9`. It is committed as an app asset, not downloaded at runtime. The graph was checked and run with synthetic inputs on host CPU; that is not an Android/device test.
- **License:** treat the pretrained weights as **CC BY-NC-SA 4.0, non-commercial**. This is a **personal-use-only app that will never be published**; the model must be reconsidered/replaced if that changes. The upstream repository's general Apache-2.0 statement and a conversion guide's CC BY-NC-SA statement conflict, so this is a conservative restriction, not a claim that the separate weights' provenance has been legally resolved. See [attribution, sources, conflict and checksum](licenses/models/MODNet/NOTICE.md). ONNX Runtime is MIT; its [license](licenses/onnxruntime/LICENSE.txt) is included.
- **Runtime:** `com.microsoft.onnxruntime:onnxruntime-android:1.22.0`, XNNPACK/CPU only. No ML Kit, MediaPipe Tasks, LiteRT fallback, Play Services segmentation, analytics or network dependency is intentionally included. CI checks the resolved runtime dependency graph, APK model and merged permissions. There is no internet permission. Inference and the model are on-device and designed to work in airplane mode; real airplane-mode operation is still to be checked on the F54.
- **Fallback:** failed/unavailable inference, unsupported native runtime, missing/corrupt foreground or memory pressure leaves the Phase 1 clock/date-over-photo rendering. A completed failure is remembered beside the photo to avoid repeated attempts; choosing the photo again creates a fresh attempt. Interrupted work can be retried on a later start. No personal photos are committed to Git or used in CI.

## Lifecycle and settings

- Each newly picked photo is privately copied and queued for one cutout. An existing saved Phase 1 photo with no sidecar is queued once, non-blocking, when the settings screen opens or a visible wallpaper engine starts. Concurrent engines/activity coalesce the request and one worker bounds concurrent inference; cutouts are never calculated during drawing. The settings screen displays a processing state while work is queued/running.
- Successful transparent PNG cutouts and failure markers live next to their background in `noBackupFilesDir`. Publication is atomic and cleanup removes abandoned sidecars. Normal process death is respected; there is no wake lock, persistent background service, retry timer or sensor.
- Default depth order with a cutout is background, time/date, foreground. The whole-clock switch sets hour and minute together; separate hour/minute controls can differ, and separators/AM–PM follow the minute. The date control is independent. Without a cutout all text stays on top. New preferences are additive to schema 1 and default off for existing installs.
- Independent View + SurfaceHolder/Choreographer engines retain visibility/screen/keyguard guards, minute/event-driven rendering, engine-local bitmap release, cached color notifications and no continuous loop. Existing photo picker, formats, fonts, colors, positions and save/apply semantics remain in place; home-only remains an instructed choice that the public chooser cannot enforce everywhere.
- minSdk 33; compile/target SDK 37.0/37. No stable signing key is present; CI debug signing may require uninstall/reinstall between builds, losing local data.

## Pending / unverified

- **Samsung Galaxy F54 5G:** MODNet latency, peak memory, photographic edge quality, one-shot behavior and airplane-mode operation have **not** been measured. Also verify chooser destination/preview, migration from Phase 1, crop/orientation, mixed depth toggles, failure behavior, persistence, process death, screen-off/lock, Battery Saver and Samsung power management. See the README checklist. CI and host inference are not device evidence.
- Platform/OEM limitations: home+lock selection or cached home frames may appear on the lock screen; API 33 lacks engine destination flags. If Home screen only is not offered, do not apply.
- No emulator, instrumentation, visual-regression, long-duration battery or physical-device test is included. Unit tests exercise model input/cache/layer policy, clock field parsing, visibility policy and value validation, not the Android system lifecycle or actual F54 rendering.
