# Status

## Current phase

Phase 1 core implementation is ready for CI and human/device verification. This repository started with only a README; the Android app, documentation and CI were introduced in this slice. Check the commit-specific Actions run for actual compile, test, lint and APK-artifact status. Local Android build is not asserted here.

## Decisions

- minSdk 33; compile/target SDK 37 (Android 17 stable). Exact toolchain pins and build commands are in the README.
- Independent View + SurfaceHolder/Choreographer live-wallpaper engine. Minute/event-driven rendering, visibility/screen/keyguard guards, engine-local bitmap release, and cached color notifications; no effects or continuous frame loop.
- Local versioned Preferences DataStore. Selected image is copied into no-backup private storage; the original is unchanged. Photo picker has no broad permission; center crop is automatic.
- Two Google Fonts OFL families, Outfit and DM Sans, are bundled with license texts and provenance; system sans is also available. Default art is synthetic.
- Save is separate from application; chooser cancellation does not undo a completed save. Home-only is an instructed choice, not something the public chooser API can enforce on every OEM; API 34+ lock-only engines are gated.
- No stable signing key is present. CI debug signing may require uninstall/reinstall between builds, losing local data.

## Pending / unverified

- Real Samsung Galaxy F54 5G testing: chooser destination and preview behavior, photo/crop, minute/date/locale/12–24-hour updates, settings persistence, process death/reboot, lock/screen-off, Battery Saver and Samsung power management. See the README checklist; nothing there has been claimed as passed.
- Platform/OEM limitations: home+lock selection or cached home frames may still appear on the lock screen; API 33 lacks engine destination flags. If Home screen only is not offered, do not apply.
- No emulator, instrumentation, visual-regression, long-duration battery or physical-device test is included in this phase. Unit tests exercise policy and value validation, not the Android system lifecycle itself.
