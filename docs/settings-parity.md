# Settings parity work

Base: `origin/main` at `4394c23a43d0647af0b3cd4dc08912302cb35322`, verified with the GitHub CLI
(`gh api repos/aaqib-sq/Wallpep/commits/main`, `gh pr list --state merged`) and freshly fetched
before branching. PR #8 (settings parity) and PR #9 (live preview + Auto Rotate) are both merged
into that commit; PR #9's merge commit `2e8caff5` is an ancestor of main. This branch is off that
fresh main. No push to main, no merge.

## Audit before counting (important: the brief's 128/159 is stale)

The brief for this slice starts from the historical "31 / 159 done, 128 remaining" figure. That
number was recorded **before** main merged PR #9, and the previous version of this document already
flagged it as historical context, not a current coverage claim. We re-audited the fresh main from
source before writing anything:

- `WallpaperScene` is the single render path for the editor preview (`EditorScreen.scene`),
  collection thumbnails (`MainActivity.renderThumbnail`) and the applied wallpaper
  (`WallpepService` → `scene.draw(canvas)`). It draws the custom text layer, gradients, strokes,
  shadows, 2D/projective transforms, colorize, particles, parallax tilt and the depth sandwich.
- `EditorScreen` has all seven tabs in the required order and writes every control into the single
  settings document; `SettingsCodec` round-trips every field; `WallpepService` observes that
  document.
- **Therefore scope items 1–3 of the brief (text layer #90–128, clock/date renderer consumption,
  colorize/particles/parallax) are already implemented on main.** This slice does **not** re-claim
  or re-implement them. Claiming them again would be dishonest.

The honest remaining work on this base is: (a) a full per-item audit, (b) a small set of real
defects found during that audit (below), and (c) the deliberately deferred items that the brief
says not to start.

## Counting rule

An item is counted **only** when all three exist: a usable control, persistence (or an in-code
default with no UI state to persist), and actual output behaviour in the renderer/service — not
merely a stored value. A visual/device check is **not** available in this environment; source-level
consumption is what is counted, with the caveats listed below. Inventory numbers refer to the
supplied `depthx-settings-inventory.md`.

## Coverage: 149 / 159 (10 remaining)

| Group | Counted | Numbers |
|---|---|---|
| Clock | 44 | #1–44 |
| Date | 40 | #48–87 |
| Text layers | 39 | #90–128 |
| Colorize | 5 | #129–133 |
| Effects / particles | 7 | #134–140 |
| Parallax | 4 | #141–144 |
| App settings, guides, library | 6 | #148, #149, #152–155 |
| Custom photo flow | 4 | #156–159 |
| **Total** | **149** | |

Per-group verification notes (control → persistence → output):

- **Clock #1–44.** `showClock`, all 17 `ClockFormats` entries (including the 12 listed formats,
  seconds variants, AM/PM marker and stacked rows), font family/size/letter spacing/alignment/
  vertical spacing, ARGB primary + secondary + seconds colours, gradient + angle, opacity, X/Y,
  the three over-foreground flips, all eight stroke fields (including seconds stroke colour and
  dashed/dotted styles), all eight shadow fields (including flip H/V), and all ten transform
  fields are consumed by `WallpaperScene.prepareClockParts` / `stackedClockParts` / `ink` /
  `inkStroke` / `centerWarp`, with the plan from `DepthPlan`. #2 gains a real per-second redraw in
  this slice (see below). #7's vertical spacing applies to the stacked formats, which are the
  layouts that have separate rows.
- **Date #48–87.** `prepareDateLayer` consumes the same family (no seconds colours, matching the
  inventory), plus date-specific format/uppercase/follow-clock colour. #52 is counted because the
  font-size control changes the rendered date; the reference screenshot's `6.6` value is **not**
  claimed as a converted Wallpep default — Wallpep's unit is sp.
- **Text #90–128.** `prepareTextLayer` plus `depthPlan` consume the enabled flag, content, font,
  size, letter spacing, alignment, gradient + angle, opacity, X/Y, over-foreground, stroke family,
  shadow family and transform family. Caveat: **one** editable text layer exists; several
  independent layers (each with its own id, as the inventory describes) do not.
- **Colorize #129–133.** `applyColorize` folds hue rotation + hue + saturation + grayscale +
  brightness into one `ColorMatrix` applied to the photo, the foreground cutout and (after this
  slice) the built-in backdrop on every frame.
- **Effects #134–140.** `drawParticles` consumes type, speed, angle, density, size, colour and
  opacity; the engine's visible-only particle loop drives the frames.
- **Parallax #141–144.** Scene tilt shifts layers and wallpaper with independent strengths; the
  service's gyroscope listener feeds `setTilt`, the editor drag simulates it. Sensor note below.
- **App/guides #148, #149, #152–155.** 24-hour clock switches the rendered digits via
  `use24Hour()`; the hide-system-clock and wallpaper-reset help rows, clear cache, the 185-face
  Google Fonts picker and the minimal About row are all real. Caveat for #148: the value lives in
  the per-design settings document rather than a separate global app preference, so under Auto
  Rotate the currently rotating design's own `timeStyle` wins.
- **Flow #156–159.** Photo picker → private copy → on-device MODNet cutout → depth sandwich with
  over-foreground flips → seven-tab editor → save/apply. The two DataStore writes are separate,
  not a transaction; end-to-end completion on a device is not verified.

## Remaining (10)

- **#45 Clock style** — no style-selector control; the field's value vocabulary was never
  recovered. Wallpep offers inline and stacked formats instead. Unclear item, not guessed.
- **#46, #47, #88, #89 per-layer clock/date parallax on/off + strength** — no per-layer controls or
  persisted fields. Main ships the global "Clock, Date & Text" and "Wallpaper" parallax pairs
  (#141–144) instead. The inventory's §F note says the layer-side fields need a fresh product
  decision; this slice adds no new sensor and makes no new decision (see note below).
- **#145 Show content on lockscreen / #146 Show content on homescreen** — controls and persistence
  exist, but the engine does not gate clock/date/text by target surface. **Untouched**, per the
  brief. (The existing `RenderGate.lockOnly` rule is a Phase 1 "never draw a lock-only engine"
  safety rule, not this content gate.)
- **#147 Show battery % while charging** — control and persistence only; **no render path exists**
  (no battery receiver feeds a value into the scene). Deliberately not started, per the brief.
- **#150 Enable clock animations / #151 Animation Style (18 styles)** — all 18 names are
  selectable and persisted, but the **transition engine is deliberately deferred**; no entrance
  animation runs. Deliberately not started, per the brief.

**Still save-only, with reasons:** #145, #146 (surface gating untouched), #147 (no render path),
#150, #151 (transition engine deferred). #45 and #46/#47/#88/#89 are not "save-only" — they have
neither controls nor persisted values yet.

## What this slice actually changes

Code-level fixes found by the audit; none add a dependency, permission, workflow, catalogue,
paywall or second render path:

1. **#2 seconds formats now update.** `ClockFormats.needsSeconds` drives a wall-clock-aligned
   per-second frame loop in `WallpepService` and the editor preview tick (`MainActivity`), while
   settings/rotation work stays on the minute tick. `WallpaperScene`'s layer cache already keyed
   seconds formats per second, so their seconds were computed but never redrawn without particles.
   New pure helpers `millisUntilNextSecond` / `nextClockTickDelayMillis` are unit tested.
2. **Inline clock gradient alignment (#9–12, #25–26).** `drawStaticLine` translates the canvas
   before drawing the `StaticLayout`, but gradients are built in scene coordinates; the shader's
   local matrix is now offset by that translation so fill and stroke gradients stay over the glyphs
   instead of clamping to one end colour. Date, text and stacked clock paths draw at absolute
   coordinates and were already correct.
3. **Colorize on the built-in backdrop (#129–133).** `drawBackground` cleared `fillPaint`'s colour
   filter at the end of each frame while `applyColorize` caches by value, so the synthetic backdrop
   lost the adjustment after the first frame. The filter is now re-read from `imagePaint` each frame
   and never cleared. The photo path was already correct.
4. **#99 duplicate control removed.** The Text → Colors card had two identical "Gradient angle"
   sliders bound to the same field; it now has one.
5. Unit tests added for the seconds tick helpers and for `ClockFormats.needsSeconds`.

## Parallax / sensor note (not silent)

The inventory §F note asks for a fresh decision before a gyroscope-driven slice is built. That
decision was already taken on main by PR #9: `WallpepService` registers a rotation/gravity sensor
only while visible and only when a parallax toggle is on, with no manifest permission and no new
dependency. This slice adds **no** sensor and does **not** silently drop the feature: global
parallax renders in preview, thumbnails and the applied wallpaper. The four per-layer fields
(#46/#47/#88/#89) remain for a future decision.

## Constraints re-checked

Obsidian black + electric blue #2563EB, no gold; seven tabs in order Clock, Date, Text, Transform,
Colorize, Parallax, Effects; all settings unlocked (no paywall/Pro/login/ads/analytics/server/
catalogue); Home shows only the user's own saved wallpapers; minSdk 33 / targetSdk 37; manifest
requests `BIND_WALLPAPER` only; no new dependencies; no workflow changes; `WallpaperScene` remains
the only render path.

## Verification limits (stated plainly)

**No device or emulator verification is possible in this environment.** CI (Compile debug APK +
Unit tests + Android lint) is the only automated check; the shader alignment, colour matrices,
glyph metrics, gyro tilt and applied-wallpaper output have not been visually confirmed on a device.
The full photo→cutout→edit→save→apply flow, the system chooser result and Auto Rotate timing are
likewise not exercised here. Coverage above is a source audit under the stated counting rule, not
a device claim.
