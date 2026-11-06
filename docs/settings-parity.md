# Settings parity work

Base: `origin/main` at `ce85b4256e90a7c70800f926dcc7f793db71c2ab`, verified with the GitHub CLI
(`gh auth status`, `gh repo view aaqib-sq/Wallpep`, `gh pr list --state merged`, `gh pr view 10`)
and freshly fetched (`git fetch origin`) before branching. PR #10 (the 149/159 audit slice) is
merged into that commit; this branch is off that fresh main. No push to main, no merge.

## Audit before counting

The previous version of this document (on main) audited the codebase at **149 / 159** and listed
the remaining ten items with reasons. That audit is the starting point of this slice; it was
re-verified against the fresh main before writing any code:

- `WallpaperScene` is the single render path for the editor preview, collection thumbnails and
  the applied wallpaper; every counted item is consumed there or in `WallpepService`.
- The ten remaining items were exactly: #45 (Clock style vocabulary never recovered),
  #46/#47/#88/#89 (per-layer clock/date parallax), #145/#146 (surface content gating),
  #147 (battery % while charging) and #150/#151 (the 18 clock animation transitions).

This slice implements two of those groups — the transition engine (#150, #151) and the
per-layer parallax controls (#46, #47, #88, #89) — and honestly closes #45 as unrecoverable.
#145/#146/#147 are deliberately untouched per the slice brief.

## Counting rule

An item is counted **only** when all three exist: a usable control, persistence (or an in-code
default with no UI state to persist), and actual output behaviour in the renderer/service — not
merely a stored value. A visual/device check is **not** available in this environment;
source-level consumption is what is counted, with the caveats listed below. Inventory numbers
refer to the supplied `depthx-settings-inventory.md`.

## Coverage: 155 / 159 (4 remaining)

| Group | Counted | Numbers |
|---|---|---|
| Clock | 46 | #1–44, #46–47 |
| Date | 42 | #48–89 |
| Text layers | 39 | #90–128 |
| Colorize | 5 | #129–133 |
| Effects / particles | 7 | #134–140 |
| Parallax (global pairs) | 4 | #141–144 |
| App settings, guides, library | 8 | #148–155 |
| Custom photo flow | 4 | #156–159 |
| **Total** | **155** | |

Groups unchanged from the 149/159 audit on main (Clock #1–44, Date #48–87, Text #90–128,
Colorize, Effects, global Parallax #141–144, Flow #156–159) are not re-claimed here; their
verification notes stand as recorded in the previous revision of this document. The deltas:

### #150 Enable clock animations / #151 Animation Style — now real output (was save-only)

- **Control + persistence** already shipped: the Settings switch and the 18-style picker write
  `clockAnimations` / `animationStyle` into the settings document (`SettingsCodec` keys
  `clock_animations`, `animation_style`); all 18 reference style names match inventory #151
  exactly (asserted by `ClockTransitionsTest`).
- **Output behaviour, new in this slice:** `ClockTransitions` (pure Kotlin, unit-tested) defines
  all 18 entrance curves — Fade & Scale (default), Slide Up/Down/Left/Right Fade, Zoom In/Out,
  Pop In, Spin In, Rotate In, Swing, Unfold, Glitch, Heartbeat, Drop Bounce, Blur In, Stretch
  Horizontal/Vertical — as `TransitionFrame` values (alpha, scale, translate, rotation, pivot,
  blur, glitch intensity) over wall-clock progress. `WallpaperScene` applies them to the cached
  clock (hour + minute) and date layer bitmaps during compositing: the transition matrix sits
  outside the user's Transform warp, Blur In uses `Paint.setRenderEffect` (API 31+; minSdk 33),
  Glitch slices the layer into deterministic jittered bands. The date trails the clock by 90 ms.
- **Play moments:** (a) *wallpaper apply / return-to-home* — `WallpepService` arms the entrance
  whenever a visible engine receives a **new** effective settings document (first load after
  becoming visible, an editor save while applied, an auto-rotate design switch); (b) *minute
  rollover* — the scene detects the minute tick changing inside `onDraw` and auto-starts, in the
  applied wallpaper and the editor preview alike. The first drawn minute after a (re)start never
  auto-animates, so thumbnails and fresh surfaces do not flash a half-started transition.
- **Battery sanity:** transitions are bounded bursts (450–950 ms per style). The engine
  re-requests vsync frames only while `scene.transitionActive`; the attached preview pumps
  itself with `postOnAnimation` only while running; every curve ends exactly at rest, and
  `release()` cancels all transition state when an engine hides. With the switch off (default),
  behaviour is byte-identical to the previous slice: minute/second/particle cadence only, no
  always-on animation loop, no new sensor, no new permission, no new dependency.
- **Honest caveats:** the **text layer does not animate** (the slice brief scopes the engine to
  the clock and date layers; inventory #150's DepthX description includes text). Legacy enum
  names kept for old documents (Cross Fade, Flip, …) fall back to plain curves and NONE never
  animates. No device/emulator visual check was possible; the curves are verified by unit tests
  (endpoints exactly at rest, bounded ranges, drop-bounce continuity) and by source-level
  consumption in the single render path.

### #46, #47, #88, #89 per-layer clock/date parallax — new controls + renderer wiring

- **Controls:** the Parallax tab's "Clock, Date & Text" sub-tab now carries **Clock parallax**
  and **Date parallax** on/off switches and 0–100 % strength sliders beside the existing global
  pair (#141/#142), in the same Obsidian card style, all unlocked.
- **Persistence:** four additive schema-1 keys (`parallax_clock`, `parallax_clock_strength`,
  `parallax_date`, `parallax_date_strength`); defaults on/100 % and `sanitized()` clamps, so
  documents saved before this slice decode to exactly the old behaviour — no migration.
- **Output behaviour:** `WallpaperScene.onDraw` computes a separate shift for the clock parts,
  the date and the text layer through the pure helper `layerParallaxScale` (unit-tested): a
  layer tilts only when the global switch **and** its own switch are on, and its strength is a
  percentage of the global travel. The text layer keeps the global pair (the inventory defines
  no per-text fields). The existing gyroscope registration in `WallpepService` is unchanged —
  still gated on the global toggles, still visible-only, still no permission — so the §F sensor
  decision taken by PR #9 is neither extended nor silently dropped.

### #45 Clock style — vocabulary unrecoverable; reported plainly, nothing invented

The style-selector field exists in the reference code, but its **value vocabulary was never
recovered**, and this slice could not recover it either. What was checked, in order:

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
   and review copy ("fully customisable clock (style, size, …)") from unrelated depth-wallpaper
   apps — no value vocabulary for this field.

Inventing a selector with guessed values would create a fake parity claim, so **#45 stays not
counted and not built**. Wallpep already ships both rendering paths the leading hypothesis
points at — inline formats and stacked ("vertical") formats — selectable through the clock
Format list (#2), which is the honest equivalent on record.

## Remaining (4)

- **#45 Clock style** — value vocabulary unrecoverable from the reference material (report
  above); no control, no persisted field, nothing guessed.
- **#145 Show content on lockscreen / #146 Show content on homescreen** — controls and
  persistence exist; the engine still does not gate clock/date/text by target surface.
  **Untouched, deliberately, per the slice brief.**
- **#147 Show battery % while charging** — control and persistence only; **no render path
  exists** (no battery receiver feeds a value into the scene). **Not started, deliberately, per
  the slice brief** (needs a product decision: optional battery element vs status text).

**Still save-only, with reasons:** #145, #146 (surface gating untouched this slice) and #147
(no render path; faking it is forbidden by the brief). #45 is not "save-only" — it has neither
a control nor a persisted value, by honest choice.

## What this slice actually changes

No new dependency, permission, workflow, catalogue, paywall or second render path; the schema
stays 1 and every key is additive:

1. **`render/ClockTransitions.kt` (new, pure).** The 18 style curves as `TransitionFrame`
   progress values, per-style durations, easing helpers, the keyframed heartbeat, the
   piecewise-parabolic drop bounce and a deterministic glitch hash. No Android types, so the
   whole vocabulary is unit-testable off device.
2. **`render/LayerParallax.kt` (new, pure).** `layerParallaxScale` — the per-layer composition
   rule (global AND layer switch; layer strength = percent of global travel; clamped).
3. **`render/WallpaperScene.kt`.** The transition state machine (`playEntranceAnimation`,
   minute-rollover auto-start, `transitionActive`, bounded self-pumping for attached views,
   cancellation in `release()`/detach), per-part frames in `compositeLayer` (translate/scale/
   rotate around per-style pivots outside the user warp, alpha, `RenderEffect` blur, glitch
   bands), and per-layer clock/date/text parallax shifts.
4. **`wallpaper/WallpepService.kt`.** Arms the entrance on a new effective settings document
   (`lastAppliedSettings` identity, reset on start/stop) and re-requests frames after a draw
   only while `scene.transitionActive` — the burst ends itself and the engine idles again.
5. **`model/WallpaperSettings.kt` + `storage/SettingsCodec.kt`.** The four per-layer parallax
   fields/keys with behaviour-preserving defaults and clamps.
6. **`ui/EditorScreen.kt`.** The per-layer CLOCK/DATE controls in the Parallax "Clock, Date &
   Text" sub-tab, wired through the existing switch/slider plumbing into the one settings
   document; the seven tabs and their order are untouched.
7. **`MainActivity.kt`.** Thumbnail scenes set `animationsEnabled = false`: cards always show
   the resting scene (matching the existing "thumbnails never animate" rule for particles).
8. **Tests.** `ClockTransitionsTest` (18-style label list vs inventory #151, every curve rests
   exactly at p = 1 and starts hidden, bounded ranges across all styles, clamped progress,
   monotonic fade, deterministic bounded glitch hash, drop-bounce continuity and settle,
   top-edge pivots for Swing/Unfold, NONE never animates), `LayerParallaxTest` (composition,
   defaults reproduce old behaviour, clamping), and `WallpaperSettingsTest` additions
   (per-layer defaults, clamps, animation defaults).

## Constraints re-checked

Obsidian black + electric blue #2563EB, no gold; seven tabs in order Clock, Date, Text,
Transform, Colorize, Parallax, Effects; all settings unlocked (no paywall/Pro/login/ads/
analytics/server/catalogue); Home shows only the user's own saved wallpapers; no DepthX code,
wallpapers or fonts; minSdk 33 / targetSdk 37; manifest requests `BIND_WALLPAPER` only and no
new permission was added; no new dependencies; no workflow changes; `WallpaperScene` remains
the only render path (preview, thumbnails, applied wallpaper agree — thumbnails deliberately
draw the resting scene, as they already did for particles).

## Verification limits (stated plainly)

**No device or emulator verification is possible in this environment.** CI (Compile debug APK +
Unit tests + Android lint) is the only automated check; the animated transitions, the blur and
glitch rendering, the stagger timing, the gyro-driven per-layer parallax and the applied-
wallpaper output have not been visually confirmed on a device. Coverage above is a source audit
under the stated counting rule, not a device claim.
