# Scope and fixed rules

## Phase 1 — core retained

Personal-use Android app (minSdk 33, targetSdk 37, package `dev.wallpep`) for a clock and date over a photo, with a local settings screen, OFL fonts, locally versioned Preferences DataStore, Android system photo picker/private copy, and the system live-wallpaper chooser. The intended destination is **home screen only**. Save updates the active configuration; backing out of the chooser does not undo a save. Center crop is automatic; there is no manual crop editor.

Use a Kotlin `WallpaperService` with independent per-engine View/SurfaceHolder rendering and one-shot Choreographer callbacks. Engines must not render while hidden; they cancel frame/timer work and release their scaled bitmaps on hide/surface destruction. A system preview may render only while visibly open. Update on minute boundaries and relevant time, time-zone, date, locale, 12/24-hour, settings, cutout-completion and surface events, not a continuous frame loop. Return stable cached wallpaper colors with a safe fallback and notify only for material background color changes. Follow normal Android battery management and process lifecycle; never ask for optimization exemptions.

**Platform limitation:** the public live-wallpaper chooser cannot be restricted by the app to one destination. Instruct the user to select Home screen only; if the device offers no such option, do not apply. API 34+ lock-only engine flags and screen/keyguard gating are defensive, but cannot prevent every shared home+lock or cached-frame scenario. API 33 cannot publicly identify a lock-only engine. F54 behavior remains unverified.

## Phase 2 — offline depth sandwich

- Bundle MODNet's photographic portrait-matting ONNX weights (25,888,640 bytes, approximately 24.7 MiB) in the APK and run locally using ONNX Runtime Android 1.22.0 (MIT); prefer CPU-based XNNPACK and fall back to ORT's CPU provider if initialization is unavailable. Disable runtime telemetry explicitly and re-audit any runtime upgrade: newer ORT releases added POSIX telemetry. The exact model, hash, provenance and conservative license treatment are in [STATUS.md](STATUS.md) and [the model notice](licenses/models/MODNet/NOTICE.md).
- The weights are treated as **CC BY-NC-SA 4.0**. This model route is acceptable only for this **personal-use-only, never-published app**; reevaluate/replace it before any commercial use or app publication. Upstream license statements conflict, as recorded in the notice.
- After a photo is picked, run matting once, off the UI thread, and cache its transparent foreground privately beside the background. A saved Phase 1 photo with no cutout receives one non-blocking attempt when settings open or a visible wallpaper engine starts. Cached results and completed failures are not recomputed on redraw; a process interrupted before completion may try again. Settings show processing without blocking other edits. No model download, account or server is needed; the design works in airplane mode, subject to unverified device testing.
- With a usable cutout, draw background → clock/date → foreground by default; the whole clock, hour, minute and date controls can bring their respective text in front. The whole-clock control sets both hour and minute; separators and AM/PM follow the minute. If the cutout or runtime is unavailable, use the Phase 1 photo → clock/date order silently.
- Cache files are app-private, excluded from backup, and removed with their corresponding abandoned/deleted photo. Processing is one-shot and separate from rendering; never perform inference per frame.

## Non-negotiable exclusions

- No ML Kit, MediaPipe Tasks SDK, or Play Services segmentation; no ads, analytics, network services/catalog, login, chat, orders, gifts, unlocks, plans, push notifications, billing, subscription or paywall. Everything is free and unlocked.
- No DepthX code, wallpapers, fonts or branding; only general techniques and public Android APIs.
- No new permissions, root, overlays, wake locks, exact alarms, foreground-service notifications, battery-optimization exemption prompts, sensors or watchdogs fighting Samsung power management.
- No video wallpapers, cloud sync, mask editor, manual crop editor or catalog.
- No broad gallery/storage permissions, personal images in Git/CI, cloud backup of selected photos, secrets in code or chat, or untrusted URL fetching.
- No claims about device behavior without an actual device test. No Play Store publication or deadline.

## Deferred backlog (not implemented)

Manual crop controls and other enhancements require separate scope, privacy, battery and licensing decisions.
