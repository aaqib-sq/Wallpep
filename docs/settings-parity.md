# Settings parity work

Base: `origin/main` at `28761f97d2903db02ca9eb63d29c0ef4dd2e6001`, verified against GitHub and fetched before starting this slice.

## Coverage (conservative: 28 / 159)

This count requires a control and the relevant output behavior to exist in this branch, not merely a persisted value. Device behavior remains unverified. Inventory numbers refer to the supplied DepthX inventory. Existing working features counted: clock #1, #3–7, #13–18; date #48–51, #54, #59–60, #62; app/flow #149, #152–153, #155–159. Date #52–53 and #61 are not counted because the reference screenshot defaults do not match the current units/defaults; #2 is not counted because some seconds formats don't format or update correctly. No undocumented values are asserted as DepthX defaults.

Remaining (131):

- Clock #2, #8–12, #19–47: full format behavior, ARGB alpha/secondary/seconds/gradients, stroke, shadow, transforms, unknown clock-style vocabulary, and tilt. Color controls and model values exist, but the renderer does not consume these features.
- Date #52–53, #55–58, #61, #63–89: reference defaults/units, color alpha/gradient, stroke, shadow, transforms and parallax. Controls and model values mostly exist; output is not complete.
- Text #90–128: controls and model values exist, but the text layer and its styling are not drawn. One editable text layer exists; multiple independent layers do not.
- Colorize #129–133, particles #134–140 and parallax #141–144: controls and model values exist, but the engine does not apply them. The inventory also flags that a fresh sensor decision is required; no sensor was added here.
- App #145–148, #150–151, #154: lock/home visibility, charging battery display and animation have controls but are not implemented in the engine; 24-hour switch has an existing SYSTEM-default ambiguity; font picker exposes 112 bundled families (185 font files), not 185 individually selectable fonts. Animation choices now match the 18 named inventory styles, with old stored enum names kept readable.

The full color dialog now stores any ARGB value for all current fill, stroke, shadow and particle color fields; it has hue, saturation, brightness, opacity and RGB/ARGB hex entry. Text gradient angle is also persisted. This is settings-surface coverage, **not** evidence that alpha, gradients or every color field render: the existing scene ignores most of them. No preview rendering was duplicated in this slice.

## Flow review

Source review: the photo picker imports a private image and schedules a cutout; editor changes feed the shared settings document and collection record; the wallpaper service observes the settings and loads the photo/cutout. Apply now waits for both writes before opening the Android chooser and does not open it after a write failure. The scene draws the photo, clock and date with the cutout depth plan. It does **not** draw the custom text or particle effects in this branch, so the requested photo + clock + date + text + effects flow is not end-to-end complete. The service chooser and actual output were not exercised on a device or emulator. The two DataStores are separate writes, not an atomic transaction.
