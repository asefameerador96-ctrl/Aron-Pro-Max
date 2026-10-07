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

## 6. Components and states

Sizes in dp, text styles and colours by token. Every interactive component: touch target at least `size.touch` 48 in both axes (the visual may be smaller; `Modifier.minimumInteractiveComponentSize`; neighbouring targets keep a 48 dp pitch), pressed feedback within 100 ms (`motion.press`), a visible focus ring (`border.focus`), a `Role` and a TalkBack name from string resources. **A control's accessible name starts with its visible text** (WCAG 2.5.3, Voice Access); the action goes in `onClickLabel`. Tier differences are listed only where they exist.

### 6.1 Primary button (`AronPrimaryButton`)

`heightIn(min = size.primary 56)`, min width 120, full width in a bar, horizontal padding 24, `radius.full`, label `type.bodyStrong`, optional 24 icon with `space.2` gap. The label wraps to 2 lines and never truncates. One primary per screen. Examples: "সংরক্ষণ" (Save), "চেক ইন" (Check in), "বিক্রয় জমা" (Sales submit).

| State | Visual | Behaviour |
|---|---|---|
| Default | A and B: vertical gradient `accent.hi` to `accent.fill` (deep blue, label 13.05:1, glare 4.56), 1 dp top highlight white 35%, shadow spot `accent` 28% at 6 dp. C: flat `accent.hi`, no shadow. Sunlight: flat `#002A73`. Label `text.onAccent` | |
| Pressed | scale 0.97 over 90 ms, fill `accent.pressed` flat, shadow 2 dp; release over `motion.fast` | no haptic on press; success feedback comes from the action |
| Focused | 2 dp `border.focus` ring, 2 dp gap (sunlight 3 dp) | |
| Disabled | `state.disabled.fill`, label `state.disabled.label`, no shadow | **always shows why** in a `type.caption` line (`text.secondary`) above it, for example "ডাটা সিঙ্ক হলে চালু হবে" |
| Busy | label stays, 20 dp arc replaces the icon (static icon when reduced motion), click ignored, `stateDescription` set | UI debounce 400 ms; correctness is the server's idempotency |
| Destructive | flat `danger.fill` (11.01:1, glare 4.51), label `text.onAccent`, same shape | only inside confirm dialogs |

### 6.2 Secondary button (`AronSecondaryButton`) and text button

Height 48 (56 beside a primary in a bar), `radius.full`, label `type.bodyStrong` in `accent.text` (9.77:1 on `raised`, glare 3.85). **The fill is opaque**: `surface.solid.raised` with a 1 dp `border.divider` (the v1 draft's glass fill nested glass on glass and fell to 4.05:1 in dark). Tier C and sunlight: `surface.solid` with a 1.5 dp `accent` border (sunlight 2 dp) so it never reads as disabled grey. The glass recipe is allowed for a secondary button directly on the page gradient only; no SR screen needs it. Examples: "প্রিন্ট" (with the printer icon), "ডাটা সিঙ্ক করুন".

| State | Visual |
|---|---|
| Default | as above |
| Pressed | `state.pressed` overlay, scale 0.97 over 90 ms |
| Focused | `border.focus` ring |
| Disabled | `state.disabled.fill`, `state.disabled.label`, border removed |
| Busy | arc replaces the icon, click ignored |
| Destructive | label `danger.onContainer` (text button) |

Text button (dialogs, banners, links): no fill, `accent.text` label, 48 high (visual 40), `state.pressed` overlay. In a banner it inherits the banner's `*.onContainer` colour and is underlined.

### 6.3 List row (`AronListRow`) and the SKU row

Min height 64, padding 16 horizontal and 12 vertical. Slots: leading (optional) 48 pack thumbnail with `radius.chip`, or 24 icon, or the **reserved empty programme-dot slot** (docs/27); content: title `type.bodyStrong` up to 2 lines, caption `type.caption` `text.secondary`; trailing: a value, chevron, check, or a status chip. Divider `border.divider`, inset 16 (76 with a thumbnail). A short list (20 rows or fewer) sits in one field card with dividers. **A long list (200 outlets, 60 SKUs) uses flat rows**: `surface.glass.field` flat (one `drawRoundRect`, no gradient, stroke or shadow), `radius.card` only on the first and last row (`RowPosition`: First, Middle, Last, Only).

**Cost rules for lists** (composition and text layout, not paint, limit a 200-row fling on 8 x Cortex-A53 cores): `@Immutable` row models built on `Dispatchers.Default` with every string precomputed (localized digits, the `AnnotatedString` of amount plus taka sign, "code · phone · cluster" as one string, the packs badge); `LazyColumn` with `key = id` and `contentType` per row kind; no `animateItem` on flat lists; at most 3 `Text` nodes per outlet row and 5 per SKU row; no `fontFeatureSettings`; pack thumbnails need no image library: 96 x 96 WebP (at most 4 KB each) delivered in the master-data bundle (UI-SR-19), decoded off the main thread into a byte-capped `LruCache` of 3 MB with a fixed placeholder.

**SKU row (Stock and Sale).** *Idle* (quantity 0, Sale): 72 dp, thumbnail, SKU name and a caption "৮.০০ ৳ / স্টিক · স্টকে ৩৯৪", and a trailing 48 dp `+` button (`accent.container`, icon `accent.onContainer`; sunlight `accent.hi` fill). The first tap adds one pack step and the row **morphs to the stepper line** (`animateContentSize`, `motion.fast`); rows with quantity above 0 stay expanded with the `accent.container` fill and show the line total. *Stock rows* always show both lines (every SKU is edited), the stepper at the trailing edge of line 2. **Compact height:** the thumbnail and the unit label go, stock moves into the caption, the stepper stays on the row (112 dp). **TalkBack:** one merged node per row (`mergeDescendants`) reading "Aster, লাইটার, আজ লোড ৪০০ পিস, স্টক ২৫ পিস, ইস্যু ০ পিস, ৬৫০ প্যাক", custom actions "বাড়ান", "কমান", "সংখ্যা লিখুন", `progressBarRangeInfo` so swipe up and down adjusts, and the minus and plus buttons stay touchable but `invisibleToUser`; group heads are `heading()`. That is 1 stop per SKU instead of about 7.

| State | Visual | Behaviour |
|---|---|---|
| Default | transparent on a card, flat `surface.glass.field` in a long list | |
| Pressed | `state.pressed` overlay, instant, no scale, no ripple | |
| Selected | `accent.container` fill, trailing 24 check, title unchanged | never colour alone |
| Done | trailing check and caption in `success.onContainer` | |
| Disabled | title and caption `text.disabled` | |
| Loading | skeleton of the same geometry (s6.11) | no layout shift |
| Long Bangla | title wraps to 2 lines, row grows; never a fixed height | |

### 6.4 Tile (`AronTile`)

Two groups on Home (screens-sr s2): **"আজকের কাজ"** (six flow tiles, 101 x 96 on 360 dp, 44 dp well) and **"অন্যান্য"** (101 x 84, 36 dp well, `type.label`). The tile is the glass recipe of s3 (66 to 58 percent, sheen, lit edge, no shadow); **the label sits on an opaque plate** (`surface.solid`, `radius.chip`, 6 dp inset, 32 dp high for one line). The well is a squircle (`radius.well`) in `accent.container` with a 24 or 20 dp `accent.onContainer` icon. Columns: 3 below font scale 1.3, 2 from 1.3 to below 1.9, 1 from 1.9 (a 64 dp row, well at the start, the plate beside it); horizontal padding 4 dp; labels break at spaces only (no `overflow-wrap: anywhere`, which breaks inside conjuncts). Below 1.3 the row height is fixed (96 or 84, no `IntrinsicSize`); from 1.3 `heightIn(min)` plus `IntrinsicSize.Max`. Order is fixed as in `docs/ui-reference/sr/home.md` (the reference shows 4 columns; 3 is the 360 dp design, announced once by a dismissible tip "মেনু এখন ৩ কলামে"); tiles the user may not use are hidden, not disabled.

| State | Visual | Behaviour |
|---|---|---|
| Default | glass body, well, plate | |
| Pressed | scale 0.96 over 90 ms plus `state.pressed`, release `motion.fast` | opens on release |
| Target | 2 dp `accent` ring | the tile that the Home dock opens, so dock and tile read as one step |
| Badge | top-end pill, at least 24 dp high, 6 dp side padding, `accent.hi` fill, `text.onAccent` `type.label` count, "৯৯+" cap | `contentDescription` states the count; `danger` is not used for counts |
| Disabled | well `state.disabled.fill`, label `text.disabled` | only while data loads, with a skeleton badge |
| Focused | `border.focus` ring outside the tile | |
| C and sunlight | `surface.solid`, `border.solid`, plate invisible | |

### 6.5 Stepper, quantity (`AronStepper`)

Used for Issue on Stock and quantity on Sale. [ minus ] [ value well ] [ plus ] and the unit. Height 48. Buttons 48 x 48, `radius.full`, 24 icon, **drawn as one `Box` with `drawBehind` and one `pointerInput`** (no Material3 `OutlinedButton`; 60 SKU rows would hold 120 of them). Minus: `surface.solid.raised` fill, `text.primary` icon (not red: red is for errors); plus: `accent.container` fill, `accent.onContainer` icon (sunlight: `accent.hi` fill, white icon; minus gets a 2 dp border). The glyph, not the colour, tells them apart. Value well: min width 72, `radius.chip`, `surface.solid.raised`, 1 dp `border.input`, value in `type.numeral`. The unit label (`type.caption`: sticks, pieces, dozens) is always shown (UI-SR-33). The kit provides one **instant `Indication`** (a single overlay `drawRect` matching `state.pressed`) through `LocalIndication` inside the theme and sets `LocalRippleConfiguration` to null, so no row carries a `RippleNode`.

| State | Visual | Behaviour |
|---|---|---|
| Default | as above | tap changes by 1 (or the pack step when the SKU defines one) |
| Pressed | button scale 0.92 over 90 ms | haptic tick (s8) |
| Press and hold | after 400 ms repeat 6 steps/s, from 1.5 s 15, from 3 s 30; haptic at most every 150 ms | releases on lift or at a bound. **While repeating only in-memory state changes**: the draft is written to Room on release, after 750 ms idle and in `onPause`, in one coalesced transaction (still survives kill and relaunch, still inside the 300 ms sale-save gate); the bar total updates at once with no crossfade, and crossfades only when 300 ms have passed since the last change |
| Typing | tap the value: `border.focus` ring, numeric keypad, select all; accepts Bengali and ASCII digits, normalises to ASCII | commit on done or blur |
| At minimum | minus disabled | |
| At maximum (`cfg.sale.max_line_qty_base`) | plus disabled, caption "সর্বোচ্চ" | one `Reject` haptic when hold hits the bound |
| Warn (over stock) | well `warning.container`, warning icon, caption beside it | **sale still allowed** (F-SR-023) |
| Error (invalid typed value) | well `danger.container`, `danger` icon, caption | not committed |
| Read-only (derived Stock) | no buttons, value `type.numeral` | |
| Changed, unsaved | 2 dp `accent` underline on the well, `stateDescription` "পরিবর্তিত" | |

Reflow: from font scale 1.5, or when the row is narrower than 320 dp, the stepper moves to its own line under the title.

### 6.6 Status chip (`AronStatusChip`)

Visual height `size.chip` 28 (`heightIn(min)`, the label may take 2 lines, **never `nowrap`**), padding 10 horizontal, `radius.chip`, 16 icon, `space.2` after it, label `type.label`; touch target 48. Chip rows and card headers are `FlowRow` with 8 dp gaps. Steady and small, in the same place on every main screen (Home keeps it in its status row because of the full form); tapping opens the sync detail sheet (screens-sr s19). **Accessible name = the visible label** (including the count and the Dhaka time), `onClickLabel` "সিঙ্কের অবস্থা দেখুন", also in dot form.

| State (SR) | Container / content | Icon and label |
|---|---|---|
| **Synced (nominal)** | **no container**; `text.secondary` | check, "১০:৪২" (compact) or "সিঙ্ক হয়েছে ১০:৪২" (full) |
| Offline | `offline.container` / `offline.onContainer` | cloud-off, "অফলাইন" |
| Offline with waiting records | same colours | "অফলাইন · ৩ অপেক্ষায়" |
| Syncing | `accent.container` / `accent.onContainer` | sync arc, "সিঙ্ক হচ্ছে" |
| N waiting, online and backing off | `warning.container` / `warning.onContainer` | upload, "৩টি অপেক্ষায়"; tap retries |
| Sync stuck beyond the config threshold | `warning` | "আবার চেষ্টা করুন" |
| Phone storage nearly full (data at risk) | `danger.container` / `danger.onContainer` | warning, "ফোনে জায়গা কম" (the only danger case) |
| Printer (icon chip, top bar of Stock, Memo, Review, Summary) | **neutral** `text.secondary` printer icon, slashed when disconnected; `warning` disc only on Stock (print required) and after a failed print | tap reconnects |
| Device status (Sales submit; UI-SR-38) | same connectivity states | derived from the last server contact |

Behaviour: forms are **full** (Home), **compact** (icon plus one word or the time) and **dot** (28 dp circle, from font scale 1.3). Each form has a **fixed slot width** (compact about 112 dp, the longest label reserved) so a state change cross-fades in place with no relayout and no title reflow (no `animateContentSize`). The chip state is throttled: `sample(1 s)`, `distinctUntilChanged` and a minimum dwell of 1.5 s per state; count digits are never animated. **The syncing arc turns 1 rotation per 1.2 s linear for at most 3 revolutions (3.6 s) after entering Syncing, then holds static** beside the word "সিঙ্ক হচ্ছে" (the word carries the meaning; an unbounded 16 dp loop keeps the whole draw pipeline at 60 to 90 fps, an estimated 100 to 200 mW); static icon when reduced motion or tier C. TalkBack: polite live region, at most once per 10 s, never while the user types.

### 6.7 Bars: action bar (`AronBottomBar`) and top bar

**Action bar.** `kind = ActionBar` (`surface.glass.field` 96 percent), **its own layout slot below the list** (never an overlay: rows never scroll under it, and a 24 dp `ScrollEdgeFade` (s6.18) ends the list), height `size.bottomBar` 80 (12 + 56 + 12) plus the navigation inset; compact height (window under 700 dp) 72 and the primary 48. The primary action lives here. Slots: optional summary at the start (`type.caption` label over `type.numeral` value), secondary (40 percent), primary (at least 144). Gap between buttons at least 12. A disabled primary shows its reason as a caption line above (**reserve the 104 dp height on screens that can show it** — Stock, Sale, Attendance, Sales submit — instead of animating the bar; the list's bottom padding never changes during a screen). Rides above the keyboard (`imePadding`); with the keyboard open and under 480 dp of free height only the primary shows.

**Budget (tokens s6).** The pinned bar, dock included, is at most 25 percent of the window height (200 dp on 800). From **font scale 1.3 the bar is one column**: a one-line summary (caption and `MoneyText` on one baseline) above a full-width primary, about 160 to 175 dp at 2.0. A secondary that does not fit leaves the bar and becomes the last item of the scroll content, a 48 dp text button. The v1 draft's "+44 dp" for a stacked summary was wrong: caption 20 + numeral 38 + gap 8 is +66 at 1.0 (162, 179, 191 and 232 dp at 1.0, 1.3, 1.5 and 2.0), which left Sale about 1.3 rows at 2.0.

**Top bar.** `kind = TopBar`, `size.topBar` 64 plus the status inset; back 48 at the start, title `type.title` (a dynamic outlet name may take 2 lines and then ends with an ellipsis, the full name is on the next card), the status chip and the printer icon at the end; optional `type.caption` subtitle. Overlay: the Home header collapses into it. **Read the scroll offset only in layout or draw lambdas** (`Modifier.layout`, `offset { }`, `graphicsLayer { }`) and expose one `derivedStateOf` boolean for the recipe swap, so the title, chip and gear do not recompose per scroll frame.

| State | Visual |
|---|---|
| Default | summary, secondary, primary |
| Primary disabled | disabled style plus the reason caption (6.1) |
| Busy | primary busy; secondary disabled |
| Keyboard open | rides above the IME; shrinks as above |
| Offline | no change; selling never waits on the network |

### 6.8 Bottom sheet (`AronBottomSheet`) and 6.9 Dialog (`AronDialog`): `AronOverlayHost`

Both are drawn by **one in-tree `AronOverlayHost` at the activity root** (scrim plus `GlassSurface(Sheet or Dialog)`, `BackHandler`, focus trap and `isTraversalGroup` for TalkBack, IME insets), not by `ModalBottomSheet` and `AlertDialog`: those each create a new window and surface (an estimated 80 to 150 ms hitch on the first open of a 2 GB phone, and a platform dim that does not match the 32 percent scrim token). Same spring and scrim on every tier, opens on the same frame as the tap. **Gate:** sheet open to first frame, p95 at most 150 ms on the D-12 class with the baseline profile; if the owner prefers the Material3 composables, that gate decides and the host is the fallback.

**Sheet.** Fill `surface.solid`, top corners `radius.sheet` 28, drag handle 36 x 4 `text.secondary` at 70 percent (24 dp touch zone), padding 16 (24 below the handle), max height 85 percent. Enter with `motion.sheet` spring as a **translation** (no alpha); exit `motion.base` `ease.in`. Dismiss: swipe down, back, scrim tap, a 48 dp close button when TalkBack is on. A sheet with unsaved input asks first. A pinned footer inside a sheet uses the action bar spec. Use for: sync detail, outlet pick-lists, reasons, filters, force sale, credit, printer picker, route sheet, hold check-in. Not for confirmations.

| Sheet state | Visual and behaviour |
|---|---|
| Expanded | content scrolls inside; handle visible |
| Dragging | follows the finger; no shadow change |
| Dismissing | release past 30 percent or velocity above 1,000 dp/s closes |
| Busy | content inert, primary busy, no overlay |
| Error | `AronBanner` (danger) at the top |

**Dialog.** Fill `surface.solid`, `radius.sheet`, width `size.dialog.width` 312, padding 24, title `type.title`, body `type.body` (scrolls above 60 percent of the screen), actions at the end; two buttons side by side below font scale 1.3, stacked with the primary on top from 1.3. Enter `motion.base` (scale 0.94 to 1 plus `ModulateAlpha`), exit 150 ms. Initial focus on the title; never on the destructive button. Tier C: no shadow, `border.solid`.

| Variant | Content | Rules |
|---|---|---|
| Confirm | title, body, text button + primary | scrim tap and back cancel |
| Destructive | `danger.onContainer` 24 icon above the title, `danger.fill` primary | `dismissible = false` except cancel; "স্থায়ী বন্ধ", discard memo |
| Input | short reason list (radio rows) | edit-memo reasons |
| Busy | buttons busy, body unchanged | |

**Allowed only for** irreversible or destructive choices and for a system permission explanation. **Never** for offline, sync delay, printer not connected, validation or empty results: those are chips, banners and inline captions.

### 6.10 Banner (`AronBanner`)

In flow at the top of the content (never floating), min height 56, padding 12 and 16, `radius.card`, 24 icon, title `type.bodyStrong`, text `type.caption` or `type.body`, optional text button, optional 48 dismiss. Opaque `*.container` fill, `*.onContainer` text, 1 dp edge of the same colour at 20%; no shadow.

| Variant | Container | Example (reference) |
|---|---|---|
| Info | `accent` | "আপনার পুরো দিনের বিক্রয় বিবরণ এখানে থাকবে"; the sunlight suggestion |
| Success | `success` | day closed and counts equal |
| Warning | `warning` | the sync-before-submit notice; stale bundle |
| Danger | `danger` | storage full, wrong password |
| Offline note | `offline` | "this list needs a connection" (AMO and TSO) |

Default persistent for offline and degraded states, dismissible for tips (remembered per day). With an action: a text button at the end, stacked under the text from font scale 1.5. Long text collapses to 3 lines with "আরও দেখুন". Enter and leave: height plus `ModulateAlpha` over `motion.base` (reduced: none). Polite live region; assertive only for danger.

### 6.11 Empty state (`AronEmptyState`) and skeleton

**Empty state.** Centred in the free space (not under the bar), at least `space.8` from the top. A 96 dp vector (at most 2 KB): a 96 dp `accent.container` circle with a 40 dp `accent.onContainer` glyph. Title `type.heading` centred, body `type.body` `text.secondary` at most 280 dp wide, optional action, and a "synced at" caption so an empty list is not mistaken for a stale one (UI-SR-44). Up to 4 lines of Bangla. Variants: first use ("আপনার এএমও (AMO) কোনো কাজ বরাদ্দ করেনি।"), filtered ("no match" plus clear), error (`danger.onContainer` glyph, retry), needs connection (`offline`, AMO and TSO), permission (action opens the system setting).

**Skeleton** (`Modifier.skeleton(visible)`): blocks with the exact geometry and radius of the real content, fill `state.skeleton`, shown only after 150 ms of loading. Sweep: a 40%-wide `state.skeleton.sweep` band, 1,200 ms linear, **at most 2 cycles**, then static; tier A and B only and never in reduced motion; read in the draw phase only.

### 6.12 Text field (`AronTextField`)

Height `size.field` 56 (`heightIn(min)`), `radius.chip`, `surface.solid.raised`, 1 dp `border.input` (4.22:1), text `type.body`, placeholder `text.placeholder` (4.80:1), label above in `type.caption` `text.secondary` (no floating labels), helper or error line below. Focus: 2 dp `border.focus` ring. Error: border `danger`, a line below with a 16 dp warning icon and the text in `danger.onContainer` that says what to do; never colour alone. Trailing action (password "দেখান") is a 48 dp text button. Username fields: ASCII keyboard, no auto-capitalise, autofill hint, not pre-filled on shared phones. Numeric fields accept Bengali and ASCII digits.

### 6.13 Switch row (`AronSwitchRow`)

The **whole 56 dp row is the toggle** (`toggleable(role = Role.Switch)`, merged text, `stateDescription` such as "রোদ মোড চালু" or "বন্ধ"; the v1 draft's 52 x 32 switch was a separate target and its name did not match the visible label). The switch is 52 x 32: off = `surface.solid.raised` track with a 2 dp `border.input` ring and a `border.input` thumb; on = `accent` track (sunlight `accent.hi`), a 20 dp `surface.solid` thumb carrying a **check glyph** (not colour alone). Haptic `ToggleOn` or `ToggleOff` (s8). Used for sunlight, haptics, credit.

### 6.14 Radio row (`AronRadioRow`)

56 dp, `radius.chip`, `surface.solid.raised` with a 1 dp `border.input`; a 22 dp dot at the start; selected = `accent.container` fill, a 2 dp `accent` edge, a check in the dot and `accent.onContainer` text. Group `selectableGroup` with `Role.RadioButton`; exactly one selected where required; a sticky primary stays disabled with its reason until one is chosen.

### 6.15 Segmented control and tabs (`AronSegmented`)

**Height 48 dp** (40 visual plus 4 dp padding; the hit area is the full 48), track `radius.chip` 16 in `surface.solid.raised` with a 1 dp divider. **Selected:** a thumb in `accent.container` with a 1.5 dp `accent` ring (5.86:1 on the track), the label in `accent.onContainer` (7.96:1) and a 16 dp check before it, as on chips; **unselected:** `text.secondary` on the track. The v1 draft's white thumb on the raised track was 1.09:1 (1.13 in dark) and both labels were Bold, so the state was nearly invisible. **Segment widths are weighted by the measured label width** plus 8 dp padding, not equal thirds ("ভিজিট হয়েছে ৬" is 91 dp at 1.0 and 108 dp with three digits, against 84 dp of an equal third), and the thumb follows the real segment bounds (`onGloballyPositioned`, offset and width animated in the draw phase over `motion.base`). **From font scale 1.5 the control is a vertical stack of 48 dp radio rows** (or a sheet picker). Tabs (Tasks) are the same component with `Role.Tab`.

### 6.16 Meter (`AronMeter`)

6 dp high (sunlight 8 dp plus a 1.5 dp `text.primary` outline), `radius.full`, track `state.skeleton`, fill `accent`; the value is always printed beside it. Semantics: `progressBarRangeInfo(current = 6, range = 0f..60f)` plus `stateDescription` "৬০টির মধ্যে ৬টি আউটলেট ভ্রমণ হয়েছে", merged with its label (not `role = img` with "৬/৬০", which TalkBack reads as "six slash sixty"). No severity colour until thresholds are named (Q-WD-01).

### 6.17 Filter dock and alphabet dock (`AronFilterDock`)

A strip of chips at the bottom of a list screen: Route (search plus alphabet, the only bottom chrome), Sale (search plus categories), Stock (category totals). Chips are 40 dp visual inside a 48 dp hit area with a **48 dp pitch** (a 28 dp chip plus 20 dp gap in a wrapped row; the v1 preview's negative margin overlapped neighbouring targets by 12 dp), `radius.chip` 18, **opaque `surface.solid.raised`**, selected = `accent.container` with the `accent` ring and a check. The dock sits above the action bar, **hides on scroll down and returns on scroll up** (`translationY` only, scroll direction read in the scroll callback), and from font scale 1.3 moves into the list as its first item so the 25 percent bar budget holds. One chip per first Bangla letter for Bangla names, `#` for others; Latin is case-insensitive.

### 6.18 Overlay host, scroll-edge fade, range view, press and hold

- **`AronOverlayHost`** (s6.8). **`ScrollEdgeFade`**: one gradient rect of 24 dp at the end of the list, the page colour from alpha 0 to 1 (`bg.solid` of the theme and tier), drawn above the list and below the action bar.
- **`RangeView`** replaces the map in the SR app (screens-sr s6): a Compose `Canvas` of about 100 lines, no dependency, works in airplane mode and never blocks Force Sale. It draws the outlet pin, the radius circle scaled to fit, the phone's position dot, a bearing arrow, a scale bar and the label "আনুমানিক ১৮০ মি দূরে" from data already on the phone. "Maps অ্যাপে খুলুন" is a `geo:` intent (zero size, uses the external app, needs the network only there). The Maps SDK stays in the AMO and TSO flavours; N-041 and docs/17 s8.2b must be made to agree (README decision 6).
- **`AronPressAndHoldButton`**: 72 dp, full width, `radius.full`, track `accent.container` with a 1.5 dp `accent` ring, fill `accent.hi` sweeping over **1,200 ms** (`holdMillis`); the label is drawn twice, `accent.onContainer` on the track and `text.onAccent` clipped to the fill, so it is legible at every progress. Early release retreats in 150 ms with the caption "আরও একটু ধরে রাখুন"; TalkBack and Enter confirm directly; reduced motion keeps the fill (it is progress).

## 7. Bangla typography rules

1. **Font.** `AronFonts.Bengali` for all Bangla; it also carries Basic Latin, digits, the minus sign, `·` and `৳`, so mixed strings never fall back to a system font. The Latin subset has no `৳`: draw it from the Bengali font even in English.
2. **Sizes and line height.** Floor 14 sp for Bangla (13 Latin), taka sign 14 sp. Line height at least 1.41 em for every Bangla style: body 16/26, caption 14/20, label 14/20, heading 18/26, title 22/32, numeral 26/38, display 34/48. **Sunlight** has its own scale (tokens s7.1): body 18/28, caption and label 16/24, numeral 28/40.
3. **Line style.** `LineHeightStyle(Alignment.Center, Trim.None)`; never `Trim.Both` or `Trim.FirstLineTop`, which clip matras. Single-line containers keep at least 4 dp vertical padding around a 20 sp line.
4. **No tracking, no italic, no caps.** `letterSpacing = 0.sp`; `FontStyle.Italic` is banned (a synthetic slant breaks the headline stroke).
5. **Weights.** Shipping v1: 400 and 700 (the two bundled files). Roles name `weight.semi` (600) for heading, bodyStrong and label, rendered as 700 until the variable subset passes its gates (tokens s7, s10 item 24); lint allows 400, 500, 600 and 700 only through type tokens. In sunlight Bangla body and caption are Bold (the Regular headline stroke is 2 px against 4 px for Bold at 14 sp and density 2.0, and it is what vanishes first in glare); `text.disabled` is never used for information.
6. **Wrapping.** Labels wrap; containers use `heightIn(min = ...)`. `TextOverflow.Ellipsis` only on single-line or 2-line dynamic names (outlet and SKU names, the top-bar title) and never on button labels, tile labels, chip text, column headers or money. Breaks at spaces only inside a tile label. **Identifiers** (`DHK-344-003`, SKU codes, dates) are drawn with U+2011 in place of the hyphen (display only; the stored value stays ASCII) so they never break at it, and carry `LocaleSpan(en)` or `VerbatimTtsAnnotation` so TalkBack reads them in the English voice. Every key label fits in 2 lines at font scale 2.0.
7. **Digits.** Bengali digits for display via `LocaleDigits.localize`; identifiers (codes, usernames, phone numbers, versions) stay ASCII; typed numbers accept both scripts and normalise to ASCII.
8. **Money and quantity: numbers never wrap or clip** (tokens s7.3). `MoneyText` and `QtyText` are single line, no ellipsis; the taka sign is a `SpanStyle` in the same `Text` (0.8 em, floor 14 sp, U+00A0 before it), `.00` at 0.75 em on display and numeral money; size is `min(base x fontScale, base x 1.5)` shrunk to fit with `TextAutoSize.StepBased(minFontSize = 0.7 x base, step 1 sp)` or the kit's `FitText`, floors 24, 18 and 16 sp. A true minus `−` (U+2212) for deductions plus the word or "(-)" in the label. Each quantity carries its unit label. Dense columns put `(৳)` in the header and keep the sign on every total. **TalkBack** reads money as "<amount> টাকা" without `৳` and group commas, to be verified with Google bn-BD TTS in the device lab (tokens s10 item 10).
9. **One word per term.** One canonical Bangla label per money term (gross, offer discount, DRP discount, QC deduction, net payable; UI-SR-07), from one string catalogue.
10. **Alphabet filter.** Case-insensitive for Latin and one chip per first Bangla letter (UI-SR-23), horizontally scrollable, selected chip `accent.container` plus a check (s6.17).
11. **Font scale.** Honour the system scale up to 2.0. Large-text roles (`display`, `numeral`, `title`, `heading`, `hero`) are capped at 1.5 times their base (large text under WCAG, and what Android 14 non-linear scaling already does); body, bodyStrong, caption and label scale to 2.0. Reflow thresholds: 1.3 (bars and hero stack, 2 tile columns, filter dock inline, dialogs stack), 1.5 (segmented control vertical, steppers on their own line, tables reflow to a name line over "qty x price = value"), 1.9 (1 tile column).
12. **Golden tests.** Strings: "ডিসকাউন্ট এবং অন্যান্য (-)", "অ্যাটেনডেন্স", "ক্যাটাগরি", "সর্বমোট", "টাস্ক ডেলিগেশন", "রিটেইলার নির্বাচন করুন", "বিক্রয় সারসংক্ষেপ", "শেষ ভিজিট ২ দিন আগে", "মেয়াদ পেরিয়েছে". **Amounts** 4,391.00, 12,456.50, 1,24,567.50 and 12,34,567.00 at font scales 1.0, 1.5 and 2.0. A native-Bangla reviewer signs the glossary (docs/20 role NB).

## 8. Haptics

Through `LocalHapticFeedback`, wrapped in `AronHaptics` (best available type per API level, no-op when unsupported, no `Vibrator` call, no extra permission). One haptic per user action, none for scrolling, tile or row taps, or connectivity changes.

| Event | Type | Below API 30 |
|---|---|---|
| Stepper tap | `VirtualKey` | `VirtualKey` |
| Stepper hold repeat (**at most every 150 ms**: an eccentric-motor vibrator driven every 80 ms is near-continuous) | `SegmentFrequentTick` | `VirtualKey` |
| Stepper reaches a bound (once) | `Reject` | `LongPress` |
| Press-and-hold button commits | `LongPress` | `LongPress` |
| Action saved: stock, sale, check-in, memo printed | `Confirm` (once) | `VirtualKey` |
| Rejection: geofence fail, validation, print failed | `Reject` (once) | `LongPress` |
| Switch (sunlight, look, haptics) | `ToggleOn` / `ToggleOff` | `VirtualKey` |

Honour the system touch-feedback setting and the Settings switch. Cheap motors cannot tell Confirm from Reject: a haptic is never the only signal.

## 9. Reduced-motion variant and tier C as a performance profile

Level: `None` if `ANIMATOR_DURATION_SCALE == 0f`; `Reduced` if the user's "Reduce motion", battery saver or thermal `SEVERE` is on **or the tier is C for any reason except the user choosing Simple on a fast phone** (a phone in C because of a failed probe, low RAM or config `off` must not keep springs, header collapse, shared elements, skeleton sweep, press scale or count crossfades). Provided as `LocalReducedMotion`; the kit reads it explicitly.

| Motion | Full | Reduced | None |
|---|---|---|---|
| Press feedback | scale 0.96 to 0.92 over 90 ms plus overlay | overlay only | overlay only |
| Screen and shared-element transition | `motion.base`, translation 8 dp plus `ModulateAlpha` | 120 ms `ModulateAlpha` fade, no transform | cut |
| Sheet | `motion.sheet` spring, translation | 120 ms fade in place | cut |
| Dialog | 220 ms scale and `ModulateAlpha` | 120 ms fade | cut |
| Chip state change | 150 ms cross-fade in a fixed slot | 120 ms fade | instant |
| Syncing arc | up to 3 revolutions, then static | static icon plus the word | static |
| Skeleton sweep | 2 cycles then static | static | static |
| Home header collapse, count updates | follows the finger; cross-fade 150 ms | static header; instant counts | static |
| Banner enter and leave | height plus fade | none | none |

Never animate layout bounds when reduced. **In tier C** sheets and dialogs have no shadow (the 1 dp border and scrim carry the edge) and surfaces are a plain `drawRoundRect` fill plus stroke, not `drawWithCache`.

## 10. What must be measured (A06 and the D-12 class)

Protocol: release build, brightness fixed at 50%, airplane mode for scripted runs, 40% battery or more, not charging, room temperature, three runs, median. Run on the A06, A07, Honor X5c Plus **and the D-12 class (Redmi 9A class, A03 Core class, Android 8.x with 2 GB) at 360 x 640, at tiers B and C**; results go to `docs/status/device-checks.md`. Gates marked docs/31 are binding; **lead-set** gates are proposals the runs confirm or correct. Figures marked est. in this file are arithmetic or experience, not measurements.

| # | What | How | Gate | Source |
|---|---|---|---|---|
| 1 | Frame time: 200-outlet fling, 60-SKU scroll, sheet open, tile press, tiers B and C, font scales 1.0 and 1.3, Bangla strings | Macrobenchmark `FrameTimingMetric` plus `dumpsys gfxinfo <pkg> framestats` (also to check whether `TOTAL_DURATION` covers the GPU on API 30 and below) | janky frames at most 2%; p95 at most 14 ms and p99 at most 24 ms at 60 Hz; no burst of 3 missed frames | docs/31, lead-set numbers |
| 2 | Tier A negative control | A probe and the same scripts with A forced | expected to fail A | lead-set |
| 3 | Probe accuracy | probe p95 versus scripted p95 | within 20% | lead-set |
| 4 | GPU overdraw | "Debug GPU overdraw" on Home, Stock, Sale | main region at most 2x over 90% of its area, a card at most 3x, nothing 4x | lead-set |
| 5 | Background cost | baked window background versus live fills | the cheaper wins | lead-set |
| 6 | Cold start, **fresh install with no compile** and on a warmed phone | `am start -W`, `reportFullyDrawn` | at most 2.5 s on the primary phone (fresh-install number recorded too) | docs/31 (D-73) |
| 7 | Battery, UI cost | 60-minute scripted UI loop, tier B versus C | B minus C at most 0.5 percentage points per hour | lead-set |
| 8 | Battery, day | N-060 protocol, 8-hour scripted day | non-screen drain at most 6% of 5,000 mAh | docs/31 |
| 9 | Memory | `dumpsys meminfo` after the scripted day | Graphics at most 90 MB, total PSS recorded | lead-set |
| 10 | Touch feedback | 240 fps camera on tile, button, stepper | pressed state within 100 ms | lead-set |
| 11 | Sale save | save a 60-line sale, with hold-repeat in the same script | one Room transaction under 300 ms; hold-repeat writes once | docs/31 |
| 12 | Gradient banding | baked WebP at 30%, 60%, 100% brightness | no visible bands at arm's length, else the dithered 8 x 8 | lead-set |
| 13 | Sunlight legibility and panel luminance | measure white luminance (cd/m2) of each phone at full brightness; outdoors in direct sun, 3 reps, light versus sunlight: read the day total, outlet count and chip; tap 15 targets one-handed; record the battery cost of forced full brightness | read total within 2 s; no mis-tap; sunlight at least as good as light; model inputs of tokens s1 corrected | lead-set, docs/32 D-UI-01 |
| 14 | Thermal | 30 minutes in sun at maximum brightness | tier falls to C only at `SEVERE` | lead-set |
| 15 | Bangla and scale | golden screenshots at font scales 1.0, 1.3, 1.5, 2.0 (Home also 1.15); light, dark, sunlight; tiers A, B, C; **heights 640 and 800**; the s7.12 strings and amounts | no clipping, no truncated key label, 48 dp targets | docs/32 s4 |
| 16 | Blur not loaded | unit test: the blur class is not initialised on B and C | pass | docs/32 s4 |
| 17 | APK delta | CI size gate | kit change at most +150 KB per ABI (baked backgrounds 66 KB, fonts unchanged, the baseline profile budgeted separately at about 100 to 300 KB) | docs/31, lead-set |
| 18 | Accessibility | TalkBack pass (stops per SKU row 1, order header, status chips, hero, dock, tiles, cards), Accessibility Scanner, contrast ledger test | every control labelled with its visible text, **targets 48 x 48 dp including alphabet chips, Sale category chips, print-check chips, the geo text button, the credit switch row, banner dismiss and expandable rows**, ledger green | docs/32 s4 |
| 19 | Haptics | feel Confirm versus Reject on the A06 motor | note whether they differ | lead-set |
| 20 | Sheet open | open the sync sheet, force-sale sheet and memo selector 20 times | first frame p95 at most 150 ms with the baseline profile | lead-set |
| 21 | List cost | 200-outlet fling with Bangla strings, nodes per row, composition time | at most 3 Text nodes per outlet row, 5 per SKU row; no frame over 2 T | lead-set |
| 22 | Fade cost | Perfetto trace of a screen change, sheet and dialog | no `saveLayer` and no offscreen `RenderNode` | lead-set |
| 23 | Money reading | TalkBack with Google bn-BD TTS on the four golden amounts | read as an amount in taka, not digits and symbols | lead-set |
| 24 | Variable font gate | subset size, cold start, 60-SKU Bangla scroll, API 26 to 28 rendering, versus the static pair | at most 278 KB, no worse than 5%, no fault; else keep 700 | lead-set |

## 11. Startup, build and kit-lane requirements

1. **Baseline Profile and Startup Profile.** Add `androidx.profileinstaller` and a `baselineprofile` module (Macrobenchmark `BaselineProfileRule`) that drives Login, Home, a Stock scroll of 60 rows, a Sale scroll, a sheet open and Memo, plus a startup profile; R8 full mode stays on. The fleet is installed by MDM and sideload, so Play cloud profiles and install-time AOT do not apply and a fresh install runs interpreted until the nightly dexopt: the lab and pilot provisioning scripts run `cmd package compile -f -m speed-profile com.aktcl.aron.sr` after install. Day 1 is the worst case; gate cold start on a fresh install (item 6).
2. **Pre-warm** in `Application.onCreate` on a background thread: the four `Typeface`s, `aronTypography()` and the first-layout of one Bangla string (two 139 KB Bengali files load on the first text layout).
3. **v0 kit divergences to rewrite** (the kit lane rewrites `GlassSurface` and `GlassPolicy` against s2 to s4; the v0 files are not iterated on): `Glass.kt` clips every surface with `clip(shape)`, stacks `background` and `border` modifiers instead of one `drawWithCache`, uses `Brush.verticalGradient(White, Color.Transparent)` for the sheen (dirty grey band on light cards), and `GlassPolicy` grants tier A at SDK 31 and 4,096 MB with no flavour, `MEDIA_PERFORMANCE_CLASS`, probe or low-RAM check; `GlassPolicyTest` models the A06 as 2,048 MB, which no A06 reports. `Kit.kt` wraps two Material3 `OutlinedButton`s per stepper; `KitStates.kt` uses `AlertDialog`.
4. **Static analysis.** Detekt rules: `graphicsLayer` with `alpha` must set `compositingStrategy`; no raw `FontWeight`; no `Color.Transparent` in a gradient; no `Modifier.alpha`.

## 12. Open items (defaults taken, to log in DECISIONS.md)

1. **Blur library for tier A** needs a request file (docs/32 s4). Default: SR has no blur dependency; AMO and TSO may enable A for the top bar only.
2. **Probe thresholds, the device-class rule and the RAM floor** (`cfg.app.ui_glass_min_ram_mb` 3,000) are lead-set; on the D-12 class (2 GB) the default is therefore tier C. README decision 3.
3. **`UiModeManager.contrast` (API 34) and the two `Settings.Secure` keys** are used for "reduce transparency"; verify on each phone and Samsung's vendor toggle.
4. **`preferredRefreshRate = 60f`** on list screens trades smoothness on 90 Hz panels for battery; confirm on the A07.
5. **`cfg.app.ui_glass` and `cfg.app.ui_glass_min_ram_mb`** must be registered in docs/19.
6. **Maps in the SR app.** `RangeView` plus a `geo:` intent replaces the map; N-041 (`docs/25`) and docs/17 s8.2b disagree today and the android-sr-a request offers options A (SDK in SR) and B (intent only).
7. **Screens** are specified in `screens-sr.md`; this file defines surfaces and components. Deferred programme surfaces (target, loyalty, discounts) are not designed (docs/27).

## 13. AMO and TSO apps (same kit; the full screens are a separate design pass)

The AMO and TSO apps reuse the kit unchanged: the same `AronTheme`, tokens, tiers (tier A allowed where the blur library is approved, top bar only, content still 96 percent solid), top bar, status chip, list rows, banners, sheets and dialogs. What differs: (1) **online-only lists** (Final Submit, maps, team) show an `offline` banner "needs internet" and a disabled action with its reason when offline; (2) the Maps SDK is allowed (docs/17 s8.2b), loaded only on tap; (3) the data budget gates are 2 MB (AMO) and 1.5 MB (TSO) a day (D-506). Screens to design, each from kit parts: **AMO** task assign (outlet pick, text field, due date, primary "বরাদ্দ করুন"), outlet-change verification (request card, photo, geo, Approve and Reject with a reason sheet), team list with attendance and last-visit status (flat rows, status chips); **TSO** device-bind OTP issue (a 4-digit code card with a countdown, `Confirm` haptic), Leave, Set Plan, Visit Query, Assign Task, Feedback (queued with a client UUID and a sync badge). No captures of these screens exist in `docs/ui-reference` (only `sr/`); the lead logs that assumption in DECISIONS.md.
