# Settings parity work

Base: `origin/main` at `cc492de521b8f82400e2419bbc9c642fef1ef6c4` (the merge of PR #11), verified
with the GitHub CLI (`gh auth status`, `gh repo view aaqib-sq/Wallpep`, `git fetch origin main`)
before branching. This branch is off that fresh main. **No push to main, no merge.**

## Audit before counting

The previous version of this document (on main) audited the codebase at **155 / 159** and listed
the remaining four items with reasons. That audit is the starting point of this slice; it was
re-verified against the fresh main before writing any code:

- `WallpaperScene` is the single render path for the editor preview, collection thumbnails and
  the applied wallpaper; every counted item is consumed there or in `WallpepService`.
- The four remaining items were exactly: #45 (Clock style vocabulary never recovered),
  #145/#146 (surface content gating) and #147 (battery % while charging).

This slice implements **#147** for real and implements **#145/#146** on the public-API
mechanism the research actually found. **#45 stays unrecoverable and is not built.**

## Counting rule

An item is counted **only** when all three exist: a usable control, persistence (or an in-code
default with no UI state to persist), and actual output behaviour in the renderer/service — not
merely a stored value. A visual/device check is **not** available in this environment;
source-level consumption is what is counted, with the caveats listed below. Inventory numbers
refer to the supplied `depthx-settings-inventory.md`.

## Coverage: 158 / 159 (1 remaining)

| Group | Counted | Numbers |
|---|---|---|
| Clock | 46 | #1–44, #46–47 |
| Date | 42 | #48–89 |
| Text layers | 39 | #90–128 |
| Colorize | 5 | #129–133 |
| Effects / particles | 7 | #134–140 |
| Parallax (global pairs) | 4 | #141–144 |
| App settings, guides, library | 9 | #145–149, #151–155 |
| Custom photo flow | 4 | #156–159 |
| Battery element | 1 | #147 |
| **Total** | **158** | |

Groups unchanged from the 155/159 audit on main (Clock #1–44, Date #48–89, Text #90–128,
Colorize, Effects, global Parallax #141–144, #148–#155, Flow #156–159) are not re-claimed here;
their verification notes stand as recorded in the previous revision of this document. The
deltas are #147, #145 and #146.

---

### #147 Show battery % while charging — real output (was save-only)

- **Control + persistence (already shipped):** the Settings switch and the Clock tab's
  "Show battery % while charging" write `batteryWhileCharging` into the settings document
  (`SettingsCodec` key `battery_while_charging`). It defaults to **off**, so the clock is
  unchanged until the user asks for the element.
- **The value is real, and it is the system's:** `system/BatteryReader` reads the level with
  `BatteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)` and the charging
  state with `BatteryManager.isCharging()`, with the sticky `ACTION_BATTERY_CHANGED` broadcast
  as the fallback for both. Both are public system APIs that need **no permission**, and the
  app requests none. `BATTERY_PROPERTY_CAPACITY` returns `Integer.MIN_VALUE` when the platform
  cannot answer; anything outside 0..100 (and a missing level) is treated as *"no answer"*, and
  **no value is ever faked or hardcoded** — with no real level the element simply does not
  draw.
- **Live updates:** `WallpepService` subscribes to `ACTION_BATTERY_CHANGED`,
  `ACTION_POWER_CONNECTED` and `ACTION_POWER_DISCONNECTED` **only while the engine is visible**,
  and the editor preview runs the same feed through `system/BatteryFeed` only while the app is
  in the foreground. A changed reading is pushed into the scene and a frame is requested, so
  the percentage updates the moment the level or the charging state changes.
- **Output behaviour:** while the phone is on power the element **takes the clock's place**,
  which is what inventory #147 describes ("Replaces the clock or date with the battery
  percentage while the phone charges"). It is drawn with the clock's own typography — face,
  size, letter spacing, colours, gradient, stroke, shadow, transform and position — so it
  reads as the clock it replaces rather than as a foreign label, and it follows the clock's
  own over-foreground flip and parallax. The date layer is untouched, matching the reference
  wording ("the clock **or** date").
- **Pure, unit-tested logic:** `render/BatteryElement.kt` holds the reading type, the charge
  states, the formatting and the replace-the-clock rule with no Android types;
  `BatteryElementTest` pins that a missing level is never rendered as a number, that the
  element only draws while the phone is on power, and that the toggle-off case never replaces
  the clock.

### #145 Show content on lockscreen / #146 Show content on homescreen — real output (was save-only)

The brief asked for research first and an implementation only if a **real public-API mechanism**
exists. It does, in two parts, and the two parts cover different situations. The honest
summary: **the surface can be identified, but not per frame on a shared engine.**

**What was checked, and what each check found**

1. **`WallpaperService.Engine.getWallpaperFlags()` / `onWallpaperFlagsChanged(int)` — API 34+,
   public.** The engine's flags are `FLAG_SYSTEM` (home), `FLAG_LOCK` (lock) or both. In AOSP
   the value is the wallpaper data's `which` flag, handed to the engine when the system
   attaches it, and `Engine.getWallpaperFlags()` returns exactly that value. **This is a real
   mechanism, and it is exact** — but only when the platform gives the engine one surface.
2. **"Independent lock screen live wallpaper" (Android 14 QPR1 and later on some builds).**
   The platform's own feature that lets the lock screen have its own live wallpaper. When it
   is enabled *and* the user picks one screen only, the lock screen and the home screen get
   **separate engine instances**, so each engine's flags name its own surface exactly. This is
   the case where #145/#146 are implemented with total precision.
3. **`KeyguardManager.isKeyguardLocked()` — public since API 16, no permission.** It says
   whether the keyguard is showing *right now*. For an engine that serves **both** screens this
   is the only signal that names the visible surface.
4. **The shared-engine limit, stated plainly.** When the user picks "Home screen and lock
   screen" — the default, and the only choice on Android 13 and earlier — **one engine and one
   surface serve both screens** and the same pixels are composited onto both. No public API can
   tell the engine "this frame is going to the lock screen", and none was found: the wallpaper
   flags are `FLAG_SYSTEM | FLAG_LOCK` for that engine, `WallpaperManager.getWallpaperId()` and
   `getWallpaperInfo()` describe *which wallpaper* is set, not *which surface this frame is
   for*, and `onApplyWallpaper(int)` is a preview-apply callback, not a per-frame signal.
   The keyguard state is therefore used for the shared engine, which is sound because the home
   screen is only visible while the device is unlocked and the lock screen only while it is
   locked.
5. **`WindowManager`/`WindowInsets`/display APIs** were considered and rejected: they report
   the wallpaper window's insets and the display, not which system surface is compositing the
   frame. `SurfaceControl`/`ViewRootImpl` are not public APIs.

**The implementation**

- `wallpaper/SurfacePolicy.kt` (pure, unit-tested) turns the platform's answer into a decision:
  `surfaceTargetFromFlags` maps the flags to `HOME` / `LOCK` / `SHARED`, and
  `contentVisibleFor` applies the two switches:
  - a **home-only** engine follows "Show content on homescreen";
  - a **lock-only** engine follows "Show content on lockscreen";
  - a **shared** engine follows the switch that matches the keyguard state at that moment.
- `WallpepService` reads the engine's own flags on API 34+ (`onWallpaperFlagsChanged` and
  `onCreate`), and re-reads the keyguard state before every drawn frame, because a device can
  lock without a broadcast. It then sets `WallpaperScene.contentVisible`.
- `WallpaperScene` gates **only the content layers** — the clock, the date and the text layer —
  exactly as the inventory words it ("clock/date/text hide on the lock screen only"). The
  wallpaper image and the particle effects are never gated.
- The editor preview and the collection thumbnails leave `contentVisible` on, so they always
  show the full scene; the gate is an engine decision about a real system surface.

**The behaviour change this required, stated plainly**

Until now Wallpep **refused to draw while the keyguard was locked** (Phase 1's "home screen
only" stance, recorded in `SCOPE.md`) and refused to draw a lock-only engine at all. Both rules
had to go: a live wallpaper that stops rendering when the phone is locked cannot show content
on the lock screen at all, so "Show content on lockscreen" would have been a control with no
effect — a fake. The engine now draws whenever **its own surface is visible** and the screen is
awake, which is the normal Android rule; the keyguard state only chooses which content that
surface may show. `SCOPE.md` and `STATUS.md` are updated on this branch to record the change.
The engine is still fully event-driven: it draws on minute boundaries (and on the second for
seconds formats), on transitions, on settings changes and on battery changes, and it releases
its bitmaps and cancels its work when the surface goes away.

---

### #45 Clock style — vocabulary unrecoverable; reported plainly, nothing invented

Unchanged from the previous revision, and deliberately **not** re-attempted. The style-selector
field exists in the reference code, but its **value vocabulary was never recovered**. What was
checked, in order:

1. **The reference inventory itself** (attached `depthx-settings-inventory.md`): item #45 states
   the vocabulary "was not recovered"; §K ("Unclear items") repeats that only the *hypothesis*
   exists — that the field selects between the engine's separate inline and stacked clock draw
   paths (§I) — explicitly "not confirmed"; no value list appears anywhere in the document.
2. **DepthX screenshots**: the inventory cites user-sent screenshots as its source, but no
   screenshot showing a Clock-style selector with values exists in this environment (checked the
   session uploads, the repository tree and the full git history — no research/screenshot
   artifacts were ever committed).
3. **APK/DEX artifacts**: none are present in this environment; the inventory's own field-level
   analysis is the only DEX-derived source and it records the values as unreadable (the field
   name list even includes R8-obfuscated config fields treated as unreadable).
4. **Public web**: a search for the app's clock-style setting returned only generic marketing
   and review copy from unrelated depth-wallpaper apps — no value vocabulary for this field.

Inventing a selector with guessed values would create a fake parity claim, so **#45 stays not
counted and not built**. Wallpep already ships both rendering paths the leading hypothesis
points at — inline formats and stacked ("vertical") formats — selectable through the clock
Format list (#2), which is the honest equivalent on record.

## Remaining (1)

- **#45 Clock style** — value vocabulary unrecoverable from the reference material (report
  above); no control, no persisted field, nothing guessed.

**Still save-only, with reasons:** none. #145, #146 and #147 were the last save-only items and
all three now have real output behaviour. #45 is not "save-only" — it has neither a control nor
a persisted value, by honest choice.

## What this slice actually changes

No new dependency, permission, workflow, catalogue, paywall or second render path; the schema
stays 1 and every key is additive:

1. **`render/BatteryElement.kt` (new, pure).** The battery reading, the charge states, the
   percentage formatting and the replace-the-clock rule, with no Android types.
2. **`system/BatteryReader.kt` (new).** The real `BatteryManager` read, with the sticky
   `ACTION_BATTERY_CHANGED` broadcast as the fallback and the "no answer" sentinel handled.
3. **`system/BatteryFeed.kt` (new).** A visible-only subscription to the battery broadcasts
   that pushes each new reading to the scene.
4. **`wallpaper/SurfacePolicy.kt` (new, pure).** The surface target and the content-visibility
   rule, with the research written down next to the code.
5. **`render/WallpaperScene.kt`.** The `batteryReading` and `contentVisible` inputs, the
   battery element layer (own typography, own transform, own parallax, cached like every other
   layer), and the content gating that skips the clock, the date and the text layer.
6. **`wallpaper/WallpepService.kt`.** The battery receiver (visible-only), the surface target
   from the engine's own wallpaper flags, the live keyguard read before each frame, and the
   engine now drawing on a locked device.
7. **`wallpaper/RenderPolicy.kt`.** The render gate no longer treats "locked" as "hidden"; the
   keyguard state moved out of the visibility rule and into the content rule.
8. **`ui/EditorScreen.kt` + `ui/SettingsScreen.kt`.** The "Show battery % while charging"
   switch in the Clock tab, the live battery feed behind the preview, and the explanatory note.
9. **`MainActivity.kt`.** The editor's battery feed starts with the app's foreground and stops
   when it leaves.
10. **Tests.** `BatteryElementTest`, `SurfacePolicyTest`, `SettingsCodecTest` (new) and
    additions to `RenderPolicyTest` and `WallpaperSettingsTest`.

## Constraints re-checked

Obsidian black + electric blue #2563EB, no gold; seven tabs in order Clock, Date, Text,
Transform, Colorize, Parallax, Effects; all settings unlocked (no paywall/Pro/login/ads/
analytics/server/catalogue); Home shows only the user's own saved wallpapers; no DepthX code,
wallpapers or fonts; minSdk 33 / targetSdk 37; the manifest still requests `BIND_WALLPAPER`
only and **no new permission was added** (the battery level, the charging state and the keyguard
state are all permission-free public APIs); no new dependencies; no workflow changes;
`WallpaperScene` remains the only render path (preview, thumbnails, applied wallpaper agree —
thumbnails deliberately draw the resting scene and never gate their content).

## Verification limits (stated plainly)

**No device or emulator verification is possible in this environment.** CI (Compile debug APK +
Unit tests + Android lint) is the only automated check. The battery percentage on a real
charging device, the lock-screen presentation on a real lock screen, the shared-engine
behaviour on a device that mirrors the wallpaper to the lock screen, the independent-lock-
screen behaviour on a build that supports it, the staggered clock animations, the blur and
glitch rendering and the gyro-driven parallax have **not** been visually confirmed on a device.
The coverage above is a source audit under the stated counting rule, not a device claim.
