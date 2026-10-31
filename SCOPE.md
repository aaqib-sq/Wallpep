# Scope and fixed rules

## Phase 1 — core

Personal-use Android app (minSdk 33) for a clock and date over a photo, with a small local settings screen, OFL fonts, locally versioned Preferences DataStore, Android system photo picker/private copy, and the system live-wallpaper chooser. The intended destination is **home screen only**. Save updates the active configuration; backing out of the chooser does not undo a save. Center crop is automatic; no manual crop editor is included.

Use a Kotlin `WallpaperService` with independent per-engine View/SurfaceHolder rendering and one-shot Choreographer callbacks. Engines must not render while hidden; they must cancel frame/timer work and release their scaled bitmap on hide/surface destruction. A system preview is allowed to render only while it is visibly open. With effects off, update on minute boundaries and relevant time, time-zone, date, locale, 12/24-hour, settings and surface events, not a continuous frame loop. Return stable cached wallpaper colors with a safe fallback and notify only for material background color changes. Follow standard Android battery management and process lifecycle; never ask for optimization exemptions.

**Platform limitation:** the public live-wallpaper chooser cannot be restricted by the app to only one destination. Instruct the user to select Home screen only; if the device offers no such option, do not apply. API 34+ lock-only engine flags and screen/keyguard gating are defensive, but cannot prevent every shared home+lock or cached-frame scenario. API 33 cannot publicly identify a lock-only engine. F54 behavior is unverified.

## Non-negotiable exclusions

- No ads, analytics, servers/catalog, login, chat, orders, gifts, unlocks, plans, push notifications, billing, subscription or paywall. Everything is free and unlocked.
- No DepthX code, wallpapers, fonts or branding; only general techniques and public Android APIs.
- No ML Kit in Phase 1. A possible Phase 2 cutout is a separate, later decision.
- No root, overlay permissions, wake locks, exact alarms, foreground-service notifications, battery-optimization exemption prompts, or watchdogs fighting Samsung power management.
- No video wallpapers, sensors, cloud sync, mask editor or catalog. Ideas belong in a later backlog, not this phase.
- No broad gallery/storage permissions, personal images in Git/CI, cloud backup of selected photos, secrets in code or chat, or untrusted URL fetching.
- No claims about device behavior without an actual device test. No Play Store publication or deadline.

## Deferred backlog (not implemented)

Possible cutout/depth effects (including whether to use ML Kit), manual crop controls and other enhancements require separate scope, privacy, battery and licensing decisions. None are included in this PR.
