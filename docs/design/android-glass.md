# Android glass: `GlassSurface`, tier selection and the component kit

Status: v1, refined 2026-10-07 (changes: `docs/design/CHANGELOG.md`). Binding inputs: `docs/32-design-system.md` (Calm Glass, section 2a is the outdoor-first gate), `docs/31` s1 (speed, battery, size, smoothness gates). Values come from `docs/design/tokens.md` (names like `surface.glass.field`, `elev.card`, `motion.fast` are token names; Kotlin names are in tokens s9). Code is for the `core-ui` kit (`android/core-ui`, task N-023); snippets are illustrative. Layout reference 360 x 800 dp (Galaxy A06, A07, Honor X5c Plus); **budget gates also run on the D-12 class** (Redmi 9A class, 2 GB; A03 Core class; Android 8.x with 2 GB) at the D-11 baseline 360 x 640. Reference labels are quoted from `docs/ui-reference/sr/*.md`; real code reads string resources.

## 1. Scope and hard rules

1. **One painter.** `GlassSurface` is the only code that paints glass. Screens never call `Modifier.blur`, `RenderEffect`, `Modifier.shadow` or `graphicsLayer` for look.
2. **No runtime blur on tier B or C.** Tier A blur exists only on the top bar of the apps (tokens s3) and never in the SR flavour.
3. **No container alpha, no offscreen fades.** Never `Modifier.alpha`, and never `graphicsLayer { alpha }` with the default compositing on a surface that has children: `CompositingStrategy.Auto` renders an offscreen layer once alpha is below 1. Fades of screens, sheets, dialogs and banners use `compositingStrategy = CompositingStrategy.ModulateAlpha` (alpha per draw op, invisible difference at 220 ms) or a translation only. A Detekt rule fails `graphicsLayer` with `alpha` and no strategy; `fadeIn`, `fadeOut` and `Crossfade` are not used for whole surfaces. Verify with a Perfetto trace that a screen change shows no `saveLayer` or offscreen `RenderNode`.
4. **No offscreen anything on B and C.** No `CompositingStrategy.Offscreen`, no `BlendMode` other than SrcOver, no noise textures, no `RenderEffect`.
5. **No shadow inside a lazy item.** Long lists use flat rows (s6.3).
6. **Same layout in every tier.** Tier changes fills, strokes and shadows only; sizes, radii, type and hit areas never change.
7. **Offline is a chip.** Never a dialog, never a blocking state (docs/32 principle 6).
8. **Every colour, radius, type style, duration comes from tokens.** A screen that needs a new value asks for a token.
9. **Content is solid, chrome is glass** (docs/32 2a.1). Cards, rows, the action bar and inputs are `surface.glass.field` (96 percent); sheets and dialogs are `surface.solid`; only Home tiles (with an opaque label plate), the top bar, scrims and the status halo are translucent. A text-bearing control inside a bar, sheet or container is opaque (`surface.solid.raised` plus a 1 dp divider), never glass on glass.
10. **Kit parts used in lists are light.** They do not wrap Material3 `Button`, `Surface` or `Card` (each is about ten nodes plus an interaction source, elevation animation and ripple): they draw with `drawBehind` and one `pointerInput`, and use the kit's instant `Indication` (s6.5).

## 2. `GlassSurface`: behaviour per kind and tier

```kotlin
enum class GlassKind { Field, Tile, Row, TopBar, ActionBar, Sheet, Dialog }
@Composable fun GlassSurface(
    modifier: Modifier = Modifier,
    kind: GlassKind = GlassKind.Field,
    shape: Shape = AronTheme.shapes.forKind(kind),
    shadow: AronShadow = AronTheme.elevation.forKind(kind),   // Page for tiles, rows, bars
    edge: GlassEdge = GlassEdge.Hairline,                     // Hairline, Top (action bar), Bottom (top bar), None
    content: @Composable BoxScope.() -> Unit,
)
```

`LocalGlassTier` (A, B, C) is read once; the surface never decides the tier itself (s4).

| Kind | Tier A (apps: AMO, TSO only) | Tier B | Tier C and sunlight | Draw ops (B) |
|---|---|---|---|---|
| Field (card) | as B | `surface.glass.field` body gradient, lit edge, `elev.card` shadow | `surface.solid`, `border.solid`, no shadow | 4 |
| Tile | as B | glass 66 to 58 percent plus sheen, edge, no shadow, opaque plate | `surface.solid`, `border.solid` | 4 |
| Row | as B | flat `surface.glass.field`, no stroke, no shadow | `surface.solid` plus `border.divider` | 1 |
| TopBar | `surface.glass.strong` 86 percent, **blur 16 dp**, 4 percent tint | `surface.glass.strong` 96 percent, no blur | `surface.solid`, 1 dp edge | 2 |
| ActionBar | as B, **no blur** | `surface.glass.field` 96 percent, top edge | `surface.solid`, `border.solid` | 2 |
| Sheet, Dialog | as B | `surface.solid`, scrim, `elev.sheet` or `elev.dialog` | `surface.solid`, scrim, **no shadow**, `border.solid` | 3 |

Implementation rules:
- One `Modifier.drawWithCache` per surface for A and B: brushes and the shape are built once per (size, theme, tier); the draw block only issues `drawRoundRect` calls. **No `clip`** (a rounded-clip shader or stencil on API 28 and below): children are drawn unclipped; a child that needs rounding (a pack thumbnail) carries its own `radius.chip` and an 8 dp inset. Tier C and sunlight use a plain `drawRoundRect` fill plus stroke, no `drawWithCache`. Non-uniform shapes (sheet top corners) cache one `Path` per size.
- **Fade-out colour stops are the same RGB at alpha 0** (`Color.White.copy(alpha = 0f)`), never `Color.Transparent`: Skia interpolates unpremultiplied and the v0 sheen showed a grey band on light cards.
- On API below 28 the gradient edge stroke is replaced by a flat `border.hairline.bottom`; coloured shadows are not available, use half the elevation (tokens s5).
- Tier A blur: `LocalGlassBackdrop` holds a blur state only when the tier is A. Scrolling content calls `Modifier.glassSource()`, which **returns the modifier unchanged on B and C**. The blur class lives in its own file reached only from `if (tier == A)`, so it is never class-loaded on B and C (a unit test asserts it, s10 item 16). Below API 31 tier A is not offered. The SR flavour has no blur dependency; AMO and TSO may enable it, and the library needs a request file.
- A tier change never relayouts: fills cross-fade over `motion.fast` with `ModulateAlpha`; downgrades at once, upgrades at the next navigation (s4).
- One-shot highlight (tier A only): when a primary button turns from disabled to enabled, a single 600 ms white 25 percent sheen sweeps once. No looping highlight anywhere.

## 3. Glass-lite without runtime blur (tier B recipe)

Why it can look like glass without blur: (1) cards sit on the page gradient plus two soft glows, which is smooth, so blurring it would change nothing; (2) a 96 percent field fill lets 4 percent of the glows tint the card, which reads as light through frosted paper; (3) a lit top edge, a faint bottom edge and a cool soft shadow give depth; (4) the Home tiles are truly translucent (62 percent) so the colour of the glows shows through them, and their labels sit on an opaque plate; (5) the top bar is glass. Translucency never sits under a number.

Paint stack of a tier B **field card** (all SrcOver, values from tokens s3):

| Layer | Light | Dark |
|---|---|---|
| L0 shadow | `elev.card`: 4 dp, ambient 6% / spot 10% of `#0B1B33` | 4 dp, 25% / 35% black |
| L1 body | vertical gradient `#FFFFFF` 96% to `#F6F9FE` 96% (a sheen over white would be invisible, so depth is tonal) | `#141D30` 96% flat |
| L2 shade | none | `#000000` 0% to 14% over the bottom 55% |
| L3 edge | 1 dp stroke inset 0.5 dp, vertical gradient `#FFFFFF` 75% to `#0B1B33` 10% | `#FFFFFF` 22% to 6% |

A **tile** has a glass body (light `#FFFFFF` 66% to 58% plus sheen `#FFFFFF` 30% to 0% over the top 45%; dark `#FFFFFF` 12% plus the bottom shade), the same edge, no shadow, and an inset plate (`surface.solid`, `radius.chip`, 6 dp inset) holding the label. A selected or target tile adds a 2 dp `accent` ring.

```kotlin
fun Modifier.fieldCard(spec: GlassSpec, r: Float) = drawWithCache {       // pseudocode
    val body = spec.bodyBrush(size); val shade = spec.shadeBrush(size); val edge = spec.edgeBrush(size)
    val half = 0.5.dp.toPx(); val corner = CornerRadius(r)
    onDrawBehind {
        drawRoundRect(body, cornerRadius = corner)
        shade?.let { drawRoundRect(it, cornerRadius = corner) }
        drawRoundRect(edge, Offset(half, half), Size(size.width - 2 * half, size.height - 2 * half), CornerRadius(r - half), style = Stroke(1.dp.toPx()))
    }
}   // applied after Modifier.shadow(..., clip = false)
```

**The page is the window.** `Theme.Aron` sets `android:windowBackground` to the pre-baked, dithered gradient-plus-glows WebP of each theme (tokens s2.1: `drawable-nodpi`, `drawable-night-nodpi`, about 33 KB each, measured), the Compose root is transparent, and the background is never nulled (nulling risks black or garbage flashes during IME resize, configuration change, task-switch thumbnails and insets animation). The same theme sets `windowSplashScreenBackground`, `android:isLightTheme`, **`android:forceDarkAllowed = false`** (MIUI and ColorOS force-dark would invert the glass and break the ledger), and the status and navigation bar styles; the platform light theme of v0 showed a white window to dark-mode users before Compose drew. Tier C and sunlight call `window.setBackgroundDrawable(ColorDrawable(bg.solid))` at the tier change. Fallback only if the A06 shows banding on the baked image: `AronBackground` draws the gradient and two radial fills live (no 288 k-pixel dither loop on the main thread: that loop costs an estimated 5 to 15 ms on A53 cores in the cold-start path).

Budget on tier B: at most 8 shadowed field cards on screen; at most 16 shadowless glass surfaces; paint depth at most 3 over the main content region; glass never nests.

## 4. Choosing the tier at runtime

`tier = min(all caps below)`, ordered A above B above C. **Static ceilings run first and never need a measurement**; a phone that passes them renders B while the probe is `Unknown` (at most two cold starts), a failed probe means C ("prove B, then use it").

| # | Input | Source | Effect |
|---|---|---|---|
| 1 | Low-RAM class | `ActivityManager.isLowRamDevice()` | C |
| 2 | Memory floor | `MemoryInfo.totalMem` below `cfg.app.ui_glass_min_ram_mb` (default 3,000). Phones report less than the marketed size: 2 GB about 1.8 to 1.9, 3 GB about 2.7 to 2.9, 4 GB about 3.6 to 3.8, 6 GB about 5.6, 8 GB about 7.7 | C. Tunable from the fleet census (Q31) without an update |
| 3 | `cfg.app.ui_glass` | config bundle: `auto`, `lite`, `off` | `off` gives C; `lite` caps at B. Config only lowers the tier |
| 4 | User setting "Look" | Settings: Auto, Light glass, Simple | Simple gives C, Light glass caps at B; never above the config cap |
| 5 | Sunlight theme | user switch | C |
| 6 | Battery saver | `PowerManager.isPowerSaveMode` and its broadcast | C |
| 7 | Thermal | `currentThermalStatus` and listener (API 29+) | `SEVERE` or worse gives C; `MODERATE` caps at B |
| 8 | Accessibility contrast | `UiModeManager.contrast` at 0.5 or more (API 34+); `Settings.Secure` `high_text_contrast_enabled` and `accessibility_display_inversion_enabled` | C |
| 9 | Device class (ceiling for A) | `SDK_INT >= 31`, `Build.VERSION.MEDIA_PERFORMANCE_CLASS >= 33` (read it on the three phones) and total RAM 4 GB or more | without all three the ceiling is B |
| 10 | Frame-time probe | s5, `ProbeState` | `Unknown` renders B (if rows 1 and 2 pass); `Failed` gives C and retries; A needs a `Passed` A probe |
| 11 | Passive jank monitor | s5 | one step down, sticky until the next passing probe |
| 12 | Build flavour | SR: A not compiled in | SR maximum is B |

Android has no cross-OEM "reduce transparency" flag, so rows 4, 6, 7 and 8 are how it is honoured. Remove-animations (`ANIMATOR_DURATION_SCALE == 0`) does not change the tier; it changes motion only (s9). **Tier C is also a performance profile** (s9): it forces reduced motion unless the user chose Simple on a fast phone.

```kotlin
sealed interface ProbeState { data object Unknown : ProbeState; data object Passed : ProbeState; data class Failed(val attempts: Int) : ProbeState }
fun resolveTier(i: TierInputs): GlassTier {                              // pure function, unit-tested with realistic totalMem values
    if (i.cfg == OFF || i.user == SIMPLE || i.sunlight || i.batterySaver || i.contrastHigh || i.thermal >= SEVERE) return C
    if (i.lowRam || i.totalMemMb < i.minRamMb || i.probe is Failed) return C        // static ceilings, then the measured result
    var t = if (i.flavourHasBlur && i.deviceClassHigh && i.sdk >= 31 && i.probeA is Passed) A else B
    if (i.cfg == LITE || i.user == LIGHT_GLASS || i.thermal >= MODERATE) t = minOf(t, B)
    return t.stepDown(i.passiveSteps)
}
```
`GlassPolicyTest` uses totalMem of about 1,800, 2,800, 3,700, 5,600 and 7,700 MB (no phone reports 2,048), and a test that the blur class is not initialised on B and C.

Applying a change: the splash and first frame are tier-neutral (the baked window background); the tier is resolved off the main thread during the splash (at most 100 ms). Re-evaluate on process start, power-save, thermal, `ACTION_CONFIGURATION_CHANGED`, a new config bundle and a user setting change. **Downgrades apply at once** (`motion.fast` cross-fade); **upgrades apply only at the next navigation** and only after 10 minutes with thermal below `MODERATE`; at most 2 tier changes per day (no oscillation). The tier is a one-byte attribute on the sync heartbeat so the admin portal shows the fleet split.

## 5. Frame-time probe and passive monitor

Purpose: measure our own surfaces on this phone instead of trusting a device list, without punishing a phone for its install state.

- **When.** After the first bundle has finished (or at the second cold start), when no sync worker runs, battery is 20 percent or more or charging, and thermal is `NONE` or `LIGHT`. Never during the download, the database inserts or the first launch without a profile (that measures the install, not the steady state).
- **Scene (the real kit, not synthetic).** Warm-up: draw one of each recipe (card, tile, bar, sheet, gradient button, gradient stroke) and discard the first 30 frames. Then two passes of the Home mock (3 shadowed cards, 11 tiles), 60 flat Stock rows with real Bangla strings and steppers scrolled programmatically for 1.5 s, and one sheet opened and closed. **Judge the second pass.** Total at most 3 s, no network, no input. The scene respects the app's own budget (8 shadowed cards), because a 40-card scene would be pessimistic about paint and blind to shaping and composition.
- **Frame interval.** `T = max(1000 / refreshRate, 16.7)` ms: a phone that is smooth at 60 Hz must not fail on a 90 Hz panel (0.85 x 11.1 ms). List screens set `WindowManager.LayoutParams.preferredRefreshRate = 60f` (a hint that also cuts frames and battery on 90 and 120 Hz panels).
- **Metrics.** `Window.addOnFrameMetricsAvailableListener` on a dedicated `HandlerThread`, `TOTAL_DURATION` per frame; on API 31 and later also store `GPU_DURATION`. On API 30 and below `TOTAL_DURATION` may not include GPU completion, which is exactly the cost glass adds: verify on the lab phones, else add `dumpsys gfxinfo framestats` to the device check (s10 item 1).
- **Pass thresholds** (lead-set, calibrate on the three phones and the D-12 class):

| Candidate | p95 | Janky frames (more than 1.5 T) | Worst frame |
|---|---|---|---|
| B | at most 0.85 T (14.2 ms at 60 Hz) | at most 3% | at most 4 T |
| A (blur active) | at most 0.65 T (10.8 ms at 60 Hz) | at most 1% | at most 2 T |

- **State and retry.** `ProbeState`: `Unknown` until the first run; a pass is `Passed`; a fail is `Failed(1)` and the probe **retries automatically at the next two cold starts on different days**; the result is `Passed` if 2 of the last 3 pass. Skip a run when battery saver is on, thermal is `MODERATE` or worse, or battery is under 20 percent and unplugged. Re-probe on app update, a new `Build.FINGERPRINT` and from Settings ("Check display speed"). Store `{tier, p95, jankyPct, fingerprint, versionCode, refreshHz, time, attempts}`.
- **Passive monitor (production).** Same listener on its own thread, attached on list screens for a 60 s window, at most 3 windows per day. **Excluded frames:** the first 10 s after process start, any time a sync worker or WorkManager job runs, IME animation, and the first pass over a new screen (shader compile). Janky frames above 8 percent in two windows on two days, or above 15 percent once, lower the tier one step. **A downgrade is then checked, not trusted:** measure two more windows; if janky frames fell by less than 30 percent relative, revert the tier and report the counter as **non-glass jank** on the heartbeat (a developer fix list: Bangla shaping, composition, GC, a sync worker, thermal), not a tier change. Counters (a dozen integers) ride the sync heartbeat. In debug and pilot builds a tiny overlay shows p95.
- **Negative control.** The A06 is expected to fail the A probe and pass B; the D-12 class is expected to be C by the static ceiling, and its B probe is still run in the lab so the census can move the RAM floor. Record all numbers in `docs/status/device-checks.md`.

