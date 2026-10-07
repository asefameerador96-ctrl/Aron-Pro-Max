# Android glass: `GlassSurface`, tier selection and the component kit

Status: v1 draft for owner review, 2026-10-07. Binding inputs: `docs/32-design-system.md` (Calm Glass), `docs/31` s1 (speed, battery, size, smoothness gates). Values come from `docs/design/tokens.md` (names like `surface.glass.strong`, `elev.card`, `motion.fast` are token names; Kotlin names are in tokens s9). Code is for the `core-ui` kit (`android/core-ui`, task N-023); all snippets are illustrative pseudocode. Reference phones: Galaxy A06, A07, Honor X5c Plus at 360 x 800 dp. Reference labels are quoted from `docs/ui-reference/sr/*.md`; real code reads string resources (the hard-coded string scan applies).

## 1. Scope and hard rules

1. **One painter.** `GlassSurface` is the only code that paints glass. Screens never call `Modifier.blur`, `RenderEffect`, `Modifier.shadow` or `graphicsLayer` for look.
2. **No runtime blur on tier B or C.** Tier A blur exists only on bars and sheets (tokens s3).
3. **No container alpha.** Never `Modifier.alpha` or `graphicsLayer { alpha }` on a surface that has children: it forces an offscreen layer. Alpha lives in the colour (`Color.copy(alpha)`).
4. **No offscreen anything on B and C.** No `CompositingStrategy.Offscreen`, no `BlendMode` other than SrcOver, no noise or grain textures, no `RenderEffect`.
5. **No shadow inside a lazy item.** Long lists use flat rows (s6.3).
6. **Same layout in every tier.** Tier changes fills, strokes and shadows only; sizes, radii, type and hit areas never change.
7. **Offline is a chip.** Never a dialog, never a blocking state (docs/32 principle 6).
8. **Every colour, radius, type style, duration comes from tokens.** A screen that needs a new value asks for a token.

## 2. `GlassSurface`: behaviour per tier

```kotlin
@Composable fun GlassSurface(
    modifier: Modifier = Modifier,
    kind: GlassKind = GlassKind.Card,          // Card, Bar, Sheet, Dialog
    shape: Shape = AronTheme.shapes.forKind(kind),
    shadow: AronShadow = AronTheme.elevation.forKind(kind),   // Page for tiles and rows
    edge: GlassEdge = GlassEdge.Hairline,      // Hairline, Top (bars), None
    content: @Composable BoxScope.() -> Unit,
)
```

`LocalGlassTier` (A, B, C) is read once; the surface never decides the tier itself (s4).

| Layer (paint order) | Tier A | Tier B | Tier C and sunlight |
|---|---|---|---|
| Shadow (one RenderNode, `clip = false`) | `elev.*` of the kind | same | card none; sheet and dialog keep it; sunlight none |
| Blur of the content behind | bar 16 dp, sheet 28 dp (Haze or equal, tokens s3) | none | none |
| Body fill | per tokens s3 (card gradient, bar and sheet `surface.glass.strong` 86%) | card gradient; bar, sheet 92%; dialog 94% | `surface.solid` |
| Sheen (light) or bottom shade (dark) | card only | card only | none |
| Edge stroke | `border.hairline.top` to `.bottom` | same | `border.solid` (1 dp; sunlight 1.5 dp) |
| Draw ops per card | 4 (shadow, 2 fills, 1 stroke) | 4 | 2 |
| Offscreen layers | 1 per blurred bar or sheet, at most 2 per screen | 0 | 0 |

Implementation rules:
- Paint with one `Modifier.drawWithCache` per surface: Brushes and the shape are built once per (size, theme, tier) and the draw block only issues `drawRoundRect` calls. Children are drawn after, unclipped (`clipContent = false`); a child that needs rounding (a pack thumbnail) carries its own `radius.chip` and at least 8 dp inset, so no clip path is needed.
- Non-uniform shapes (sheet top corners) cache one `Path` per size.
- Tier A blur: `LocalGlassBackdrop` holds a blur state only when the tier is A. The scrolling content calls `Modifier.glassSource()`, which **returns the modifier unchanged on B and C**, so no layer is recorded. The blur class lives in its own file reached only from `if (tier == A)`, so it is never class-loaded on B and C (docs/32 s4). API below 31 has no `RenderEffect`: tier A is not offered there.
- Recommendation for the lead (logged in s11): the SR flavour does not depend on the blur library at all (maximum tier B), which keeps the SR APK lean; AMO and TSO may enable A. The library and its version need a request file.
- A tier change never relayouts: fills cross-fade over `motion.fast`; see s4 for when it is applied.
- One-shot highlight (tier A only): when a primary button turns from disabled to enabled, a single 600 ms white 25% sheen sweeps across it once. No looping highlight anywhere.

## 3. Glass-lite without runtime blur (tier B recipe)

Why it can look like glass with no blur: (1) cards sit on the page gradient plus two soft glows, which is smooth, so blurring it changes nothing visible; (2) the translucent fill picks up whichever glow is behind it, which reads as light through glass; (3) a lit top edge, a faint bottom edge and a cool soft shadow give depth; (4) where content really moves behind a surface (bars, sheets) the fill is 92% opaque, leaving an 8% ghost as the deliberate hint of translucency.

Paint stack of a tier B card (all SrcOver; values from tokens s3):

| Layer | Light | Dark |
|---|---|---|
| L0 shadow | `elev.card`: 4 dp, ambient 6% / spot 10% of `#0B1B33` | 4 dp, 25% / 35% black |
| L1 body | vertical gradient `#FFFFFF` 66% to 58% | `#FFFFFF` 12% flat |
| L2 sheen or shade | `#FFFFFF` 30% to 0% over the top 45% of the height (gradient clamps, so it costs one fill) | `#000000` 0% to 14% over the bottom 55% (darkening raises contrast for light text; a white sheen would drop `text.secondary` to 3.9:1 once glows are behind, so dark never gets one) |
| L3 edge | 1 dp stroke inset 0.5 dp, vertical gradient `#FFFFFF` 75% to `#0B1B33` 10% | `#FFFFFF` 22% to 6% |

```kotlin
fun Modifier.glassCard(spec: GlassSpec, r: Float) = drawWithCache {      // pseudocode
    val body = spec.bodyBrush(size); val sheen = spec.sheenBrush(size); val edge = spec.edgeBrush(size)
    val half = 0.5.dp.toPx(); val corner = CornerRadius(r)
    onDrawBehind {
        drawRoundRect(body, cornerRadius = corner)
        drawRoundRect(sheen, cornerRadius = corner)
        drawRoundRect(edge, Offset(half, half), Size(size.width - 2 * half, size.height - 2 * half),
            CornerRadius(r - half), style = Stroke(1.dp.toPx()))
    }
}   // applied after Modifier.shadow(..., clip = false)
```

The page background is `AronBackground` (tokens s2.1), drawn once at the activity root; every screen is transparent over it. Default implementation: render the gradient and both glows **once** into an `ImageBitmap` at 360 x 800 px (density 1.0, about 1.1 MB, ordered dither of half a level to prevent banding), draw it with `drawImage(filterQuality = Low)` (one textured quad per frame instead of three full-screen shader fills), and re-render only on theme or size change. Fallback if the A06 shows no cost from live fills: draw the gradient and two `drawCircle` radial fills directly. After the first frame call `window.setBackgroundDrawable(null)` (the window background is `bg.solid` until then, so there is no flash), otherwise every pixel is filled twice. Tier C and sunlight draw flat `bg.solid`.

Budget on tier B: at most 8 shadowed cards on screen; at most 16 shadowless glass surfaces; paint depth at most 3 over the main content region; glass never nests.

## 4. Choosing the tier at runtime

`tier = min(all caps below)`, ordered A above B above C. Unknown or unproven means B (the field default, docs/32).

| # | Input | Source | Effect |
|---|---|---|---|
| 1 | `cfg.app.ui_glass` | config bundle: `auto`, `lite`, `off` | `off` gives C; `lite` caps at B; `auto` adds no cap. Config can only lower the tier |
| 2 | User setting "Look" | Settings: Auto, Light glass, Simple | Simple gives C, Light glass caps at B; never raises above the config cap |
| 3 | Sunlight theme | user switch | C |
| 4 | Battery saver | `PowerManager.isPowerSaveMode`, `ACTION_POWER_SAVE_MODE_CHANGED` | C |
| 5 | Thermal | `PowerManager.currentThermalStatus` and its listener (API 29+) | `SEVERE` or worse gives C; `MODERATE` caps at B (a phone in the sun throttles) |
| 6 | Accessibility contrast | `UiModeManager.contrast` at 0.5 or more (API 34+); string keys `high_text_contrast_enabled` and `accessibility_display_inversion_enabled` read with `Settings.Secure.getInt` | C |
| 7 | Device class (ceiling for A) | `SDK_INT >= 31` and `Build.VERSION.MEDIA_PERFORMANCE_CLASS >= 33` (declared by premium phones; expected 0 on the A06 class, read it on the three phones) and total RAM 4 GB or more (docs/32) | without all three the ceiling is B |
| 8 | Frame-time probe | s5, stored result | A needs a passing A probe; B needs a passing B probe, else C |
| 9 | Passive jank monitor | s5 | one step down, sticky until the app updates or the probe passes again |
| 10 | Build flavour | SR: A not compiled in | SR maximum is B |

Android has no cross-OEM "reduce transparency" flag (Samsung's vendor toggle has no public key), so rows 2, 4, 5 and 6 are how "reduce transparency" is honoured. Remove-animations (`ANIMATOR_DURATION_SCALE == 0`) does not change the tier; it changes motion only (s9).

```kotlin
fun resolveTier(i: TierInputs): GlassTier {                              // pure function, unit-tested
    if (i.cfg == OFF || i.user == SIMPLE || i.sunlight || i.batterySaver || i.contrastHigh ||
        i.thermal >= SEVERE || !i.probe.bPassed) return C
    var t = if (i.flavourHasBlur && i.deviceClassHigh && i.probe.aPassed) A else B
    if (i.cfg == LITE || i.user == LIGHT_GLASS || i.thermal >= MODERATE) t = minOf(t, B)
    return t.stepDown(i.passiveSteps)        // sticky downgrades from the jank monitor: A to B, B to C
}
```

Applying a change: the splash and first frame are tier-neutral (flat `bg.solid`); the tier is resolved off the main thread during the splash (at most 100 ms), so there is no visible switch. After that, re-evaluate on process start, `ACTION_POWER_SAVE_MODE_CHANGED`, the thermal listener, `ACTION_CONFIGURATION_CHANGED` (uiMode, contrast), a new config bundle and a user setting change. **Downgrades apply at once** with a `motion.fast` cross-fade of fills; **upgrades apply only at the next navigation or restart**, never mid-gesture, and never to A without a passing probe. The chosen tier is a one-byte attribute on the sync heartbeat so the admin portal can show the fleet split (A, B, C counts) and a spike of C is visible.

## 5. Frame-time probe

Purpose: measure our own surfaces on this phone instead of trusting a device list.

- **Where.** First run, on the screen where the user already waits for the first bundle. `GlassProbe` is visible (a GPU does no work for hidden content): 40 glass cards with 3 rows each scroll programmatically for 1.5 s (`animateScrollBy`), then a sheet opens and closes twice (with blur if A is being probed). Total at most 2.5 s, no network, no input needed.
- **How.** `Window.addOnFrameMetricsAvailableListener` (API 24+), `FrameMetrics.TOTAL_DURATION` per frame; interval `T = 1000 / display.refreshRate` ms (read the panel's real rate; it can be 60, 90 or 120 Hz).
- **Pass thresholds** (lead-set, calibrate on the three phones):

| Candidate | p95 | Janky frames (more than 1.5 T) | Worst frame |
|---|---|---|---|
| B | at most 0.85 T (14.2 ms at 60 Hz) | at most 3% | at most 4 T |
| A (blur active) | at most 0.65 T (10.8 ms at 60 Hz) | at most 1% | at most 2 T |

- **Order.** Probe B first; a fail gives C. If the device class allows A, probe A; a fail gives B.
- **Re-probe.** On app update, on a new `Build.FINGERPRINT`, from Settings ("Check display speed"), and at most once per 24 h. Skip when battery saver is on, thermal is `MODERATE` or worse, or the battery is under 15% and unplugged. Store `{tier, p95, jankyPct, fingerprint, versionCode, refreshHz, time}`.
- **Passive monitor (production).** Same listener, attached on list screens for a 60 s window, at most 3 windows per day. Janky frames above 8% in two windows on two different days, or above 15% once, lower the tier one step (sticky). Counters (a dozen integers) ride the sync heartbeat. In debug and pilot builds the listener runs always and a tiny overlay shows p95.
- **Negative control.** The A06 is expected to fail the A probe and pass the B probe; record both numbers in `docs/status/device-checks.md`.

## 6. Components and states

Sizes in dp, text styles and colours by token. Every interactive component: touch target at least `size.touch` (48), pressed feedback visible within 100 ms (`motion.press`), a visible focus ring (`border.focus`) for keyboard and switch access, `Role` and a TalkBack label from string resources. Tier differences are listed only where they exist.

### 6.1 Primary button (`AronPrimaryButton`)

Height `size.primary` 56, min width 120, full width in a bar, horizontal padding 24, `radius.full`, label `type.bodyStrong`, optional 24 icon with `space.2` gap. Grows (`heightIn(min = 56)`) at large font scale; label wraps to 2 lines, never truncates. One primary per screen. Examples: "সংরক্ষণ" (Save), "চেক ইন" (Check in), "বিক্রয় জমা" (Sales submit).

| State | Visual | Behaviour |
|---|---|---|
| Default | A and B: vertical gradient `accent.hi` to `accent`, 1 dp top highlight `#FFFFFF` 35%, shadow spot `accent` 28% at 6 dp. C: flat `accent`, no shadow. Label `text.onAccent` | |
| Pressed | scale 0.97 over 90 ms, fill `accent.pressed` flat, shadow 2 dp; release over `motion.fast` | haptic none on press; success feedback comes from the action |
| Focused | 2 dp `border.focus` ring, 2 dp gap | |
| Disabled | `state.disabled.fill`, label `state.disabled.label`, no shadow | **always shows why** in a caption (`type.caption`, `text.secondary`) under or beside it, for example "ডাটা সিঙ্ক হলে চালু হবে" |
| Busy | label stays, 20 dp indeterminate arc replaces the icon (static icon in reduced motion), click ignored, `stateDescription` set | UI debounce 400 ms; correctness is the server's idempotency, not the button |
| Destructive | flat `danger` fill, `text.onAccent` label (6.16:1 light, 7.45 dark, 8.17 sunlight; never a fixed white), same shape | only inside confirm dialogs |

### 6.2 Secondary button (`AronSecondaryButton`) and text button

Height 48 (56 when beside a primary in a bar), `radius.full`, label `type.bodyStrong` in `accent`. Fill is the tier's card recipe at button size with no shadow (A and B: gradient fill plus hairline; C: `surface.solid` with a 1.5 dp `accent` border so it never reads as disabled grey). Examples: "প্রিন্ট" (Print, with the printer status icon), "ডাটা সিঙ্ক করুন" (Sync data).

| State | Visual |
|---|---|
| Default | as above |
| Pressed | `state.pressed` overlay, scale 0.97 over 90 ms |
| Focused | `border.focus` ring |
| Disabled | `state.disabled.fill`, `state.disabled.label`, border removed |
| Busy | arc replaces the icon, click ignored |
| Destructive | label `danger` |

Text button (dialogs, banners): no fill, `accent` label, 48 high, `state.pressed` overlay.

### 6.3 List row (`AronListRow`)

Min height 64, padding 16 horizontal and 12 vertical. Slots: leading (optional) 48 pack thumbnail with `radius.chip`, or 24 icon, or the **reserved empty programme-dot slot** (docs/27); content: title `type.bodyStrong` up to 2 lines, caption `type.caption` `text.secondary` (outlet code and cluster, unit, time); trailing: a value (`type.bodyStrong`, right aligned), chevron 24, check, or a status chip. Divider `border.divider`, inset 16 (inset 76 with a thumbnail).

Grouping: a short list (20 rows or fewer) sits in one `GlassSurface(Card)` with dividers. A **long list (200 outlets, 60 SKUs) uses flat rows**: each row fills `surface.glass` flat (one `drawRoundRect`, no gradient, no stroke, no shadow), with `radius.card` only on the first and last row of a group (`RowPosition`: First, Middle, Last, Only). This is what keeps a 200-row fling inside the frame gate.

| State | Visual | Behaviour |
|---|---|---|
| Default | transparent on the card, or flat `surface.glass` in a long list | |
| Pressed | `state.pressed` overlay, instant, no scale | |
| Selected | `accent.container` fill, trailing 24 check icon, title unchanged | never colour alone |
| Done | trailing `success` check and caption (for example visited outlet) | |
| Disabled | title and caption `text.disabled` | |
| Loading | skeleton of the same geometry (s6.11) | no layout shift |
| Long Bangla | title wraps to 2 lines, row grows; never a fixed height | |

### 6.4 Tile grid tile (`AronTile`)

Min 96 wide x 96 high (3 columns on 360 dp: about 101 x 96; 2 columns at font scale 1.5 or more, tokens s6). `radius.card`, the tier's card recipe **without the shadow** (so a 14-tile Home paints no shadows). Content: 44 dp circular well in `accent.container` with a 24 dp `accent` icon, `space.2` gap, label `type.label` centred, up to 2 lines, never truncated. Tiles in one row share the tallest height (`IntrinsicSize.Max`). Order is fixed as in `docs/ui-reference/sr/home.md`; tiles the user may not use are hidden, not disabled.

| State | Visual | Behaviour |
|---|---|---|
| Default | card recipe, well and icon as above | |
| Pressed | scale 0.96 over 90 ms plus `state.pressed`, release `motion.fast` | opens on release |
| Badge | top-end 20 dp minimum circle, `danger` fill, `text.onAccent` `type.label` count, "৯৯+" cap, localized digits | `contentDescription` states the count, for example open tasks |
| Disabled | well `state.disabled.fill`, icon and label `text.disabled` | only while data is loading; shows a skeleton badge |
| Focused | `border.focus` ring outside the card | |
| C and sunlight | `surface.solid`, `border.solid`, well `accent.container` | |

### 6.5 Stepper, quantity (`AronStepper`)

Used for Issue on Stock and quantity on Sale. Layout: [ minus ] [ value well ] [ plus ] and the unit label. Height 48. Buttons 48 x 48, `radius.full`, 24 icon. Minus: `surface.solid.raised` fill, `text.primary` icon (not red: red is for errors); plus: `accent.container` fill, `accent` icon (sunlight: `accent` fill, white icon; minus gets a 1.5 dp border). The glyph, not the colour, tells them apart. Value well: min width 72, `radius.chip`, `surface.solid.raised`, 1 dp `border.input`, value in `type.numeral` centred. Unit label (`type.caption`): sticks, pieces or dozens, always shown (UI-SR-33), never left implicit.

| State | Visual | Behaviour |
|---|---|---|
| Default | as above | tap changes by 1 (or by the pack step when the SKU defines one) |
| Pressed | button scale 0.92 over 90 ms | haptic tick (s8) |
| Press and hold | after 400 ms repeat 6 steps/s, from 1.5 s 15 steps/s, from 3 s 30 steps/s; haptic at most every 80 ms | releases on lift or on reaching a bound |
| Typing | tap the value: well gets the `border.focus` ring, numeric keypad, select all; accepts Bengali and ASCII digits and normalises to ASCII | commit on done or blur |
| At minimum | minus disabled (`state.disabled.*`) | |
| At maximum (`cfg.sale.max_line_qty_base`) | plus disabled, caption "সর্বোচ্চ" | one `Reject` haptic when hold hits the bound |
| Warn (over stock) | well `warning.container`, warning icon, caption beside it | **sale still allowed** (F-SR-023); never blocks |
| Error (invalid typed value) | well `danger.container`, `danger` icon, caption | value not committed |
| Read-only (derived Stock) | no buttons, value `type.numeral`, well flat | |
| Changed, unsaved | 2 dp `accent` underline on the well and `stateDescription` "পরিবর্তিত" | |

Reflow: at font scale 1.5 or more, or when the row is narrower than 320 dp, the stepper moves to its own full-width line under the row title. TalkBack: the buttons are labelled "বাড়ান" and "কমান" with the SKU, and the well exposes increment and decrement custom actions.

### 6.6 Status chip (`AronStatusChip`)

Visual height `size.chip` 28, padding 10 horizontal, `radius.chip`, 16 icon, `space.2` after it (6), label `type.label`; opaque container colours; touch target 48 (invisible padding). Steady and small, in the same place on every main screen; tapping opens the sync detail sheet. Never a modal.

| State (SR) | Container / content | Icon and label (reference strings) |
|---|---|---|
| Offline | `offline.container` / `offline.onContainer` | cloud-off, "অফলাইন" |
| Offline with waiting records | same colours | "অফলাইন · ৩ অপেক্ষায়" (count in the label) |
| Syncing | `accent.container` / `accent.onContainer` | sync arc, "সিঙ্ক হচ্ছে" |
| Synced | `success.container` / `success.onContainer` | check, "সিঙ্ক হয়েছে ১০:৪২" (Dhaka time) |
| N waiting, online and backing off | `warning.container` / `warning.onContainer` | upload, "৩টি অপেক্ষায়"; tap retries |
| Sync stuck beyond the config threshold | `warning` | "আবার চেষ্টা করুন" |
| Phone storage nearly full (data at risk) | `danger.container` / `danger.onContainer` | warning, "ফোনে জায়গা কম"; the only danger case |
| Printer not connected (icon chip in the top bar of Stock, Memo, Summary; UI-SR-20) | `warning.onContainer` icon on a 36 dp `warning.container` circle, diagonal line over the printer | tap reconnects; connected state shows a plain `text.secondary` printer icon |
| Device status (Sales submit; UI-SR-38) | same four connectivity states | derived from the last server contact, not the radio flag |

Behaviour: state changes cross-fade over `motion.fast`, width animates with `animateContentSize` over `motion.fast` (none when reduced). The syncing arc turns 1 rotation per 1.2 s linear only while syncing (static icon when reduced motion or tier C). TalkBack: polite live region, announced at most once per 10 s and never while the user is typing.

### 6.7 Bottom action bar (`AronBottomBar`) and top bar

`kind = Bar`, height `size.bottomBar` 80 (12 + 56 + 12) plus the navigation inset; one-hand reach: the primary action lives here. Slots: optional summary at the start (`type.caption` label over `type.numeral` value, for example the memo total), secondary (equal width or 40%), primary (at least 144, takes the rest). Gap between buttons at least 12 so a destructive neighbour cannot be mis-tapped. If summary plus two buttons do not fit, the summary moves to a line above (+44 dp). Rides above the keyboard (`imePadding`); with the keyboard open and under 480 dp of free height, only the primary shows. The list's bottom `contentPadding` is the bar height plus `space.4`, so the last row scrolls clear. Tier look: tokens s3 (`bar`); top edge is the hairline top stroke. The top bar (`size.topBar` 64 plus status inset) uses the same recipe with the edge at the bottom, title `type.title`, the sync status chip and the printer icon at the end.

| State | Visual |
|---|---|
| Default | summary, secondary, primary |
| Primary disabled | primary disabled style plus the reason caption (s6.1) |
| Busy | primary busy; secondary disabled |
| Keyboard open | rides above the IME; shrinks as above |
| Offline | no change; selling never waits on the network |

Examples: Stock = "সংরক্ষণ" primary, "প্রিন্ট" secondary. Sales submit = "বিক্রয় জমা" primary (disabled until every record is acknowledged, reason shown), "ডাটা সিঙ্ক করুন" secondary.

### 6.8 Bottom sheet (`AronBottomSheet`, wraps `ModalBottomSheet`)

`kind = Sheet`, top corners `radius.sheet` 28, drag handle 36 x 4 dp `text.secondary` at 70% (24 dp touch zone, 8 dp from the top), content padding 16 (24 below the handle), max height 85%. Enter with `motion.sheet` spring; exit over `motion.base` `ease.in`. Scrim by tier (tokens s3). Dismiss: swipe down, back, scrim tap, a 48 dp close button when TalkBack is on. A sheet with unsaved input asks first (dialog). A pinned footer inside a sheet uses the bar spec.

| State | Visual and behaviour |
|---|---|
| Expanded | content scrolls inside; handle visible |
| Dragging | follows the finger; no shadow change |
| Dismissing | release past 30% or velocity above 1,000 dp/s closes |
| Busy | content inert, primary busy, no overlay |
| Error | `AronBanner` (danger) at the top of the sheet |
| Tier A | blur 28 dp behind; B and C: opaque fill and scrim |

Use for: sync detail (from the chip), outlet pick-lists, reasons, filters. Not for confirmations (dialog).

### 6.9 Dialog (`AronDialog`)

`kind = Dialog`, `radius.sheet` 28, width `size.dialog.width` 312, padding 24, title `type.title`, body `type.body` (scrolls above 60% of the screen), actions at the end. Two buttons side by side below font scale 1.3, stacked with the primary on top at 1.3 or more. Enter `motion.base` (scale 0.94 to 1 plus fade), exit 150 ms fade. Initial focus on the title; never on the destructive button.

| Variant | Content | Rules |
|---|---|---|
| Confirm | title, body, text button + primary | scrim tap and back cancel |
| Destructive | `danger` 24 icon above the title, destructive primary (`danger` fill) | `dismissible = false` except cancel; example: "স্থায়ী বন্ধ" (permanent close), discard memo |
| Input | a short reason list (radio rows) | used for edit-memo reasons |
| Busy | buttons busy, body unchanged | |

**Allowed only for** irreversible or destructive choices and for a system permission explanation. **Never** for offline, sync delay, printer not connected, validation messages or empty results: those are chips, banners and inline captions.

### 6.10 Banner (`AronBanner`)

In flow at the top of the content (never floating), min height 56, padding 12 vertical and 16 horizontal, `radius.card`, 24 icon, title `type.bodyStrong`, text `type.body`, optional text button, optional 48 dismiss. Opaque `*.container` fill, `*.onContainer` text, 1 dp edge of the same colour at 20%; no shadow.

| Variant | Container | Example (reference) |
|---|---|---|
| Info | `accent` | "আপনার পুরো দিনের বিক্রয় বিবরণ এখানে থাকবে" |
| Success | `success` | day closed and counts equal |
| Warning | `warning` | the sync-before-submit notice with the action "ডাটা সিঙ্ক করুন" (the old red notice is a warning, not an error) |
| Danger | `danger` | storage full, sync rejected for a build block |
| Offline note | `offline` | "this list needs a connection" on AMO and TSO online lists |

| State | Behaviour |
|---|---|
| Default | persistent for offline and degraded states; dismissible for tips (dismissal remembered per day) |
| With action | text button at the end; stacks under the text at font scale 1.5 or more |
| Long text | collapses to 3 lines with "আরও দেখুন" |
| Enter and leave | `animateContentSize` over `motion.base` plus fade (reduced: none) |
| Access | polite live region; assertive only for danger |

### 6.11 Empty state (`AronEmptyState`) and skeleton

**Empty state.** Centred in the free space (not under the bar), at least `space.8` from the top. Illustration: a 96 dp vector (at most 2 KB) of a 96 dp `accent.container` circle with a 40 dp `accent` glyph (no raster). Title `type.heading` centred, body `type.body` `text.secondary` at most 280 dp wide, optional action (secondary or primary button), and a "synced at" caption (`type.caption`) so an empty list is not mistaken for a stale one (UI-SR-44). Up to 4 lines of Bangla; never clipped.

| Variant | Content |
|---|---|
| First use | "আপনার এএমও (AMO) কোনো কাজ বরাদ্দ করেনি।" (no tasks) plus synced-at time |
| Filtered | "no match" plus the action to clear the filter |
| Error | `danger` glyph, retry action |
| Needs connection | `offline` glyph, plus the status chip; AMO and TSO only |
| Permission | location or Bluetooth needed, action opens the system setting |

**Skeleton** (`Modifier.skeleton(visible)`): placeholder blocks with the exact geometry and radius of the real content (no layout shift), fill `state.skeleton`. It appears only after 150 ms of loading (local data usually loads before that, so it rarely shows on SR). Sweep: a 40%-wide `state.skeleton.sweep` band, 1,200 ms linear, **at most 2 cycles**, then static; tier A and B only, never in reduced motion. The animated value is read in the draw phase only (no recomposition per frame). Tier C and reduced: static blocks.

## 7. Bangla typography rules

1. **Font.** `AronFonts.Bengali` for all Bangla; it also carries Basic Latin, digits, the minus sign, `·` and `৳`, so mixed Bangla and SKU-code strings never fall back to a system font. The Latin subset is for English UI only and has no `৳`: draw `৳` from the Bengali font even in English.
2. **Sizes and line height.** Floor 14 sp for Bangla (13 sp Latin). Line height at least 1.41 em for every Bangla style (the font's win ascent plus descent is 1.403 em, so stacked marks never clip): body 16/26, caption 14/20, label 14/20, heading 18/26, title 22/32, numeral 26/38, display 34/48.
3. **Line style.** `LineHeightStyle(Alignment.Center, Trim.None)`; never `Trim.Both` or `Trim.FirstLineTop`, which clip matras. Single-line containers (chip 28 dp) keep at least 4 dp total vertical padding around a 20 sp line.
4. **No tracking, no italic, no caps.** `letterSpacing = 0.sp` (any tracking breaks joining); Bengali has no italic, so `FontStyle.Italic` is banned (a synthetic slant breaks the headline stroke); emphasis is Bold or an icon.
5. **Weights.** Only Normal and Bold (the two bundled files). The headline stroke (matra) of Bangla regular is thin: in sunlight the kit maps `text.secondary` to 12:1 (`#2B3648`) and never uses `text.disabled` for information.
6. **Wrapping.** Labels wrap; containers use `heightIn(min = ...)`, never a fixed height. `TextOverflow.Ellipsis` is allowed only on single-line names (outlet or SKU names) and never on button labels, tile labels, chip text, column headers or money, because older ICU can cut inside a conjunct. Every key label must fit in 2 lines at font scale 2.0.
7. **Digits.** Bengali digits for display via `LocaleDigits.localize`; identifiers (outlet codes, usernames, phone numbers, versions) stay ASCII and use `Latin`-style tabular figures; typed numbers accept both scripts and normalise to ASCII.
8. **Money and quantity.** `type.numeral` or `type.display` in Bold, `text.primary`, right-aligned in columns (digits are equal width in both bundled fonts, so columns align), two decimals, the taka sign `৳` at 0.6 em after the amount (current app, UI-SR-08), a true minus `−` (U+2212) for deductions plus the word or "(-)" in the label so a discount is never shown by sign or colour alone. Each quantity carries its unit label (sticks, pieces, dozens).
9. **One word per term.** One canonical Bangla label per money term (gross, offer discount, DRP discount, QC deduction, net payable; UI-SR-07). The string catalogue owns them, the kit shows them identically on Home, Summary, Memo and print preview.
10. **Alphabet filter.** Chips are case-insensitive for Latin and one chip per first Bangla letter for Bangla names (UI-SR-23), 48 dp wide minimum, horizontally scrollable, selected chip uses `accent.container` plus a check.
11. **Font scale.** Honour the system scale up to 2.0 (cap it there); reflow thresholds 1.3 and 1.5 (tokens s6); no fixed text heights; screenshots at 1.0, 1.3 and 2.0.
12. **Golden-test strings.** Conjunct-heavy and long: "ডিসকাউন্ট এবং অন্যান্য (-)", "অ্যাটেনডেন্স", "ক্যাটাগরি", "সর্বমোট", "টাস্ক ডেলিগেশন", "রিটেইলার নির্বাচন করুন", "বিক্রয় সারসংক্ষেপ". A native-Bangla reviewer signs the glossary (docs/20 role NB).

## 8. Haptics

Through `LocalHapticFeedback`, wrapped in `AronHaptics` (it picks the best available type per API level and no-ops when unsupported; no `Vibrator` call, so no extra permission). Rule: one haptic per user action, none for scrolling, none for tile or row taps, none for connectivity changes (offline stays calm).

| Event | Type | Below API 30 |
|---|---|---|
| Stepper tap | `VirtualKey` | `VirtualKey` |
| Stepper hold repeat (at most every 80 ms) | `SegmentFrequentTick` | `VirtualKey` |
| Stepper reaches a bound (once) | `Reject` | `LongPress` |
| Press-and-hold button commits | `LongPress` | `LongPress` |
| Action saved: stock, sale, check-in, memo printed | `Confirm` (once) | `VirtualKey` |
| Rejection: geofence fail, validation, print failed | `Reject` (once) | `LongPress` |
| Switch (sunlight, look, haptics) | `ToggleOn` / `ToggleOff` | `VirtualKey` |

Honour the system touch-feedback setting and add a Settings switch. Cheap eccentric-motor vibrators cannot tell Confirm from Reject: a haptic is never the only signal (always a visual and a word).

## 9. Reduced-motion variant

Level: `None` if `ANIMATOR_DURATION_SCALE == 0f`; `Reduced` if the user's "Reduce motion" switch, battery saver or thermal `SEVERE` is on; else `Full`. Provided as `LocalReducedMotion`; the kit reads it explicitly rather than relying on the framework.

| Motion | Full | Reduced | None |
|---|---|---|---|
| Press feedback | scale 0.96 to 0.92 over 90 ms plus overlay | overlay only, no scale | overlay only |
| Screen and shared-element transition | `motion.base` | 120 ms cross-fade, no transform | cut |
| Sheet | `motion.sheet` spring | 120 ms fade in place | cut |
| Dialog | 220 ms scale and fade | 120 ms fade | cut |
| Chip state change | 150 ms cross-fade plus width | 120 ms fade, no width animation | instant |
| Syncing arc | rotates while syncing | static icon plus the word | static |
| Skeleton sweep | 2 cycles then static | static | static |
| Banner enter and leave | height plus fade | none | none |
| One-shot highlight (tier A) | 600 ms sweep | none | none |

Never animate layout bounds when reduced.

## 10. What must be measured on the A06

Protocol: release build, display brightness fixed at 50%, airplane mode for the scripted runs, 40% battery or more and not charging, room temperature, three runs, median. Repeat on the A07 and the Honor X5c Plus before owner sign-off; results go to `docs/status/device-checks.md`. Gates marked docs/31 are binding; **lead-set** gates are proposals confirmed or corrected by these runs.

| # | What | How | Gate | Source |
|---|---|---|---|---|
| 1 | Frame time: 200-outlet fling, 60-SKU scroll, sheet open, tile press, tiers B and C | Macrobenchmark `FrameTimingMetric`, plus `dumpsys gfxinfo <pkg> framestats` in the device check | janky frames at most 2%; p95 at most 14 ms and p99 at most 24 ms at 60 Hz (scale to the panel rate); no burst of 3 or more missed frames | docs/31, lead-set numbers |
| 2 | Tier A negative control | run the A probe and the same scripts with A forced on | expected to fail A thresholds; keeps the device-class rule honest | lead-set |
| 3 | Probe accuracy | compare probe p95 to scripted p95 | within 20% | lead-set |
| 4 | GPU overdraw | Developer options "Debug GPU overdraw" on Home, Stock, Sale list | main region at most 2x over 90% of its area, a glass card at most 3x, nothing 4x | lead-set |
| 5 | Background cost | trace `AronBackground` as bitmap quad versus live fills | the cheaper wins; record the numbers | lead-set |
| 6 | Cold start | `am start -W` and `reportFullyDrawn`, tier-neutral splash | at most 2.5 s | docs/31 (D-73) |
| 7 | Battery, UI cost | 60-minute scripted UI loop (scroll, sheets, taps), tier B versus tier C, `dumpsys battery` charge counter where available | B minus C at most 0.5 percentage points per hour | lead-set |
| 8 | Battery, day | N-060 protocol, 8-hour scripted day | non-screen drain at most 6% of 5,000 mAh | docs/31 |
| 9 | Memory | `dumpsys meminfo` after the scripted day | Graphics at most 90 MB, total PSS recorded (provisional, set from the first run) | lead-set |
| 10 | Touch feedback | 240 fps camera or screen record on tile, button, stepper | pressed state visible within 100 ms | lead-set |
| 11 | Sale save | save a 60-line sale | one Room transaction under 300 ms, unaffected by animation | docs/31 |
| 12 | Gradient banding | inspect the page at 30%, 60% and 100% brightness | no visible bands at arm's length, else the dithered bitmap path | lead-set |
| 13 | Sunlight legibility | outdoors in direct sun, 3 reps, default light versus sunlight theme: read the day total, outlet count and chip; tap 15 targets one-handed | read total within 2 s; no mis-tap; sunlight at least as good as light | lead-set |
| 14 | Thermal | 30 minutes in sun at maximum brightness | tier falls to C only at `SEVERE`; UI stays usable | lead-set |
| 15 | Bangla and scale | golden screenshots at 1.0, 1.3 and 2.0; light, dark, sunlight; tiers A, B, C; the s7 strings | no clipping, no truncated key label, 48 dp targets | docs/32 s4 |
| 16 | Blur not loaded | unit test: the blur class is not initialised on B and C | pass | docs/32 s4 |
| 17 | APK delta | CI size gate | kit change at most +150 KB per ABI; fonts unchanged | docs/31, lead-set |
| 18 | Accessibility | TalkBack pass, Accessibility Scanner, contrast ledger test | every control labelled, targets 48 dp, ledger green | docs/32 s4 |
| 19 | Haptics | feel Confirm versus Reject on the A06 motor | note whether they differ; visual cue is always present anyway | lead-set |

## 11. Open items (defaults taken, to log in DECISIONS.md)

1. **Blur library for tier A** needs a request file (docs/32 s4). Default: SR flavour has no blur dependency (maximum B); AMO and TSO may enable A.
2. **Probe thresholds and the device-class rule** (`MEDIA_PERFORMANCE_CLASS >= 33`) are lead-set; read the real values on the three phones and correct.
3. **`UiModeManager.contrast` (API 34) and the two `Settings.Secure` string keys** are used for "reduce transparency"; verify behaviour on each phone and Samsung's vendor toggle.
4. **Background path** (cached bitmap versus live fills) is decided by item 5 of s10.
5. **`cfg.app.ui_glass`** must be registered in docs/19 (tokens s10 item 5); the thermal and battery inputs need no config.
6. **Screens** are specified elsewhere; this file defines surfaces and components only. Deferred programme surfaces (target, loyalty, discounts) are not designed (docs/27).
